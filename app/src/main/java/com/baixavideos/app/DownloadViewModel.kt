package com.baixavideos.app

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import android.net.Uri
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

enum class Quality(val label: String) {
    BEST("Melhor"),
    P1080("1080p"),
    P720("720p"),
    P480("480p"),
    AUDIO("Só áudio (MP3)"),
    GIF("GIF animado"),
}

data class VideoPreview(
    val title: String,
    val thumbnail: String?,
    val uploader: String?,
    val site: String?,
    val durationSec: Int,
)

data class UiState(
    val ready: Boolean = false,
    val busy: Boolean = false,
    val url: String = "",
    val quality: Quality = Quality.BEST,
    val preview: VideoPreview? = null,
    /** Link direto para um ficheiro .gif (o yt-dlp não baixa imagens). */
    val directGif: Boolean = false,
    val progress: Float = -1f,
    val status: String = "Preparando o motor de download…",
    val log: String = "",
)

/**
 * Estado e lógica de download, fora da Activity: o download continua no
 * DownloadService mesmo com o app em segundo plano ou fechado.
 */
object Downloads {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private const val PROCESS_ID = "baixavideos"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var initStarted = false
    private var job: Job? = null

    fun init(context: Context) {
        synchronized(this) {
            if (initStarted) return
            initStarted = true
        }
        val app = context.applicationContext
        scope.launch {
            try {
                YoutubeDL.getInstance().init(app)
                FFmpeg.getInstance().init(app)
                _state.update { it.copy(ready = true, status = "Pronto. Cole um link para começar.") }
            } catch (e: Exception) {
                _state.update { it.copy(status = "Falha ao iniciar: ${e.message}") }
            }
        }
    }

    fun setUrl(url: String) = _state.update { it.copy(url = url.trim(), preview = null, directGif = false) }
    fun setQuality(q: Quality) = _state.update { it.copy(quality = q) }

    fun analyze() {
        val url = _state.value.url
        if (url.isBlank() || !_state.value.ready || _state.value.busy) return
        job = scope.launch {
            _state.update { it.copy(busy = true, progress = -1f, status = "Buscando informações…", preview = null) }
            if (looksLikeGif(url) || isGifByContentType(url)) {
                _state.update { it.copy(busy = false, directGif = true, preview = gifPreview(url), status = "GIF encontrado. Toque em Baixar.") }
                return@launch
            }
            try {
                val req = YoutubeDLRequest(url).apply { addOption("--no-playlist") }
                val info = YoutubeDL.getInstance().getInfo(req)
                val preview = VideoPreview(
                    title = info.title ?: "Sem título",
                    thumbnail = info.thumbnail,
                    uploader = info.uploader,
                    site = info.extractorKey,
                    durationSec = info.duration,
                )
                _state.update { it.copy(busy = false, preview = preview, status = "Escolha a qualidade e baixe.") }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, status = "Não foi possível ler o link: ${shortError(e)}") }
            }
        }
    }

    /** Marca o download como iniciado e liga o serviço em primeiro plano que o executa. */
    fun startDownload(context: Context) {
        synchronized(this) {
            val s = _state.value
            if (s.url.isBlank() || s.busy || !s.ready) return
            _state.update { it.copy(busy = true, progress = 0f, status = "Iniciando download…", log = "") }
        }
        val app = context.applicationContext
        ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java))
    }

    /** Chamado pelo DownloadService. */
    fun launchDownload(context: Context): Job {
        val s = _state.value
        val tmpDir = File(context.getExternalFilesDir(null), "tmp")
        return scope.launch {
            tmpDir.deleteRecursively()
            tmpDir.mkdirs()
            try {
                if (s.directGif || looksLikeGif(s.url)) {
                    val file = downloadDirect(s.url, tmpDir)
                    _state.update { it.copy(status = "Salvando em Downloads…", progress = -1f) }
                    val saved = MediaSaver.saveToDownloads(context, file)
                    _state.update { it.copy(busy = false, progress = 1f, status = "Concluído! Salvo em $saved") }
                    return@launch
                }
                _state.update { it.copy(status = "Preparando o download…", progress = -1f) }
                // Tamanho estimado do formato escolhido, para calcular o progresso quando o FFmpeg baixa
                val estimate = try {
                    val info = YoutubeDL.getInstance().getInfo(
                        YoutubeDLRequest(s.url).apply {
                            addOption("--no-playlist")
                            addFormatOptions(s.quality)
                        }
                    )
                    info.fileSize.takeIf { it > 0 } ?: info.fileSizeApproximate.takeIf { it > 0 }
                } catch (_: Exception) {
                    null
                }

                val req = YoutubeDLRequest(s.url).apply {
                    addOption("--no-playlist")
                    addOption("--no-mtime")
                    addOption("--restrict-filenames")
                    addOption("-o", "${tmpDir.absolutePath}/%(title).120B [%(id)s].%(ext)s")
                    addFormatOptions(s.quality)
                    if (s.quality == Quality.AUDIO) {
                        addOption("-x")
                        addOption("--audio-format", "mp3")
                        addOption("--audio-quality", "0")
                    } else if (s.quality == Quality.GIF) {
                        // Converte o vídeo em GIF: 12 fps, 480 px de largura e paleta própria para boas cores
                        addOption("--recode-video", "gif")
                        addOption(
                            "--postprocessor-args",
                            "VideoConvertor:-an -sn -vf fps=12,scale=480:-2:flags=lanczos,split[a][b];[a]palettegen[p];[b][p]paletteuse -loop 0",
                        )
                    } else {
                        addOption("--merge-output-format", "mp4")
                        addOption("--remux-video", "mp4")
                        // HLS (vídeo em segmentos) baixado pelo FFmpeg: timestamps corretos, sem artefactos ao avançar
                        addOption("--downloader", "m3u8:ffmpeg")
                        addOption("--postprocessor-args", "ffmpeg:-movflags +faststart")
                    }
                }

                // Partilhados entre a thread do yt-dlp e o monitor
                val part = AtomicInteger(0)
                val lastNativeProgress = AtomicLong(0L)
                val postProcessing = AtomicBoolean(false)

                // O FFmpeg não reporta progresso: mede o tamanho dos ficheiros em tmpDir
                val monitor = launch {
                    var lastBytes = 0L
                    var lastTime = System.currentTimeMillis()
                    var speed = 0.0
                    while (isActive) {
                        delay(1000)
                        val now = System.currentTimeMillis()
                        val bytes = tmpDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                        val instant = (bytes - lastBytes).coerceAtLeast(0) * 1000.0 / (now - lastTime).coerceAtLeast(1)
                        speed = if (speed == 0.0) instant else speed * 0.7 + instant * 0.3
                        lastBytes = bytes
                        lastTime = now
                        if (bytes == 0L || postProcessing.get() || now - lastNativeProgress.get() < 3000) continue
                        _state.update { it.copy(progress = estimateProgress(bytes, estimate), status = sizeProgressText(bytes, estimate, speed, part.get())) }
                    }
                }
                try {
                    YoutubeDL.getInstance().execute(req, PROCESS_ID) { progress, eta, line ->
                        if (line.contains("[download] Destination:")) part.incrementAndGet()
                        val step = postProcessingStep(line)
                        if (step != null && !line.startsWith("[info]") && !line.startsWith("[youtube]")) postProcessing.set(true)
                        if (progress >= 0f) lastNativeProgress.set(System.currentTimeMillis())
                        _state.update {
                            when {
                                progress >= 0f -> {
                                    it.copy(progress = progress / 100f, status = progressText(progress, eta, line, part.get()), log = line)
                                }
                                step != null -> it.copy(progress = -1f, status = step, log = line)
                                else -> it.copy(log = line)
                            }
                        }
                    }
                } finally {
                    monitor.cancel()
                }
                _state.update { it.copy(status = "Salvando em Downloads…", progress = -1f) }
                val saved = tmpDir.listFiles().orEmpty()
                    .filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
                    .map { MediaSaver.saveToDownloads(context, it) }
                if (saved.isEmpty()) error("nenhum arquivo foi gerado")
                _state.update { it.copy(busy = false, progress = 1f, status = "Concluído! Salvo em ${saved.joinToString()}") }
            } catch (e: YoutubeDL.CanceledException) {
                _state.update { it.copy(busy = false, progress = -1f, status = "Download cancelado.") }
            } catch (e: Exception) {
                val cancelled = !_state.value.busy
                _state.update {
                    it.copy(busy = false, progress = -1f, status = if (cancelled) "Download cancelado." else "Erro: ${shortError(e)}")
                }
            } finally {
                tmpDir.deleteRecursively()
            }
        }.also { job = it }
    }

    fun cancel() {
        _state.update { it.copy(busy = false, progress = -1f, status = "Download cancelado.") }
        YoutubeDL.getInstance().destroyProcessById(PROCESS_ID)
        job?.cancel()
    }

    /** Atualiza o yt-dlp — resolve a maioria dos erros quando um site muda. */
    fun updateEngine(context: Context) {
        if (_state.value.busy || !_state.value.ready) return
        val app = context.applicationContext
        scope.launch {
            _state.update { it.copy(busy = true, progress = -1f, status = "Atualizando o yt-dlp…") }
            try {
                val result = YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel._STABLE)
                val version = YoutubeDL.getInstance().version(app)
                val msg = if (result == YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE) "já estava atualizado" else "atualizado"
                _state.update { it.copy(busy = false, status = "yt-dlp $msg (versão $version).") }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, status = "Falha ao atualizar: ${shortError(e)}") }
            }
        }
    }

    // Linha típica do yt-dlp: "[download]  45.3% of ~ 120.50MiB at  2.31MiB/s ETA 00:40 (frag 12/80)"
    private val totalRegex = Regex("""of\s+~?\s*([\d.]+)\s*([KMGT]?i?B)""")
    private val speedRegex = Regex("""at\s+([\d.]+\s*[KMGT]?i?B/s)""")

    private fun progressText(percent: Float, etaSec: Long, line: String, part: Int): String {
        val what = if (part >= 2) "Baixando áudio" else "Baixando"
        val parts = mutableListOf("$what… ${"%.1f".format(percent)}%")
        totalRegex.find(line)?.let { m ->
            val total = m.groupValues[1].toDoubleOrNull()
            val unit = m.groupValues[2]
            if (total != null) parts += "${"%.1f".format(total * percent / 100)} de ${"%.1f".format(total)} $unit"
        }
        speedRegex.find(line)?.let { parts += it.groupValues[1].replace(" ", "") }
        if (etaSec > 0) parts += "faltam ${formatEta(etaSec)}"
        return parts.joinToString(" · ")
    }

    /** Progresso calculado pelo tamanho já escrito em disco (downloads feitos pelo FFmpeg). */
    private fun sizeProgressText(bytes: Long, estimate: Long?, speed: Double, part: Int): String {
        val what = if (part >= 2) "Baixando áudio" else "Baixando"
        val parts = mutableListOf<String>()
        if (estimate != null && estimate > 0) {
            parts += "$what… ${"%.1f".format(estimateProgress(bytes, estimate) * 100)}%"
            parts += "${formatBytes(bytes)} de ~${formatBytes(estimate)}"
        } else {
            parts += "$what… ${formatBytes(bytes)}"
        }
        if (speed > 0) parts += "${formatBytes(speed.toLong())}/s"
        if (estimate != null && speed > 0 && estimate > bytes) parts += "faltam ${formatEta(((estimate - bytes) / speed).toLong())}"
        return parts.joinToString(" · ")
    }

    private fun estimateProgress(bytes: Long, estimate: Long?): Float =
        if (estimate == null || estimate <= 0) -1f else (bytes.toFloat() / estimate).coerceIn(0f, 0.99f)

    private fun formatBytes(b: Long): String = when {
        b >= 1L shl 30 -> "%.2f GB".format(b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
        else -> "%.0f KB".format(b / 1024.0)
    }

    private fun YoutubeDLRequest.addFormatOptions(q: Quality) {
        if (q == Quality.AUDIO) {
            addOption("-f", "ba/b")
        } else if (q == Quality.GIF) {
            // Só o vídeo, em baixa resolução: o GIF final tem 480 px
            addOption("-f", "bv*/b")
            addOption("-S", "res:480")
        } else {
            // Prefere H.264 + AAC: o formato mais compatível com players de celular
            addOption("-f", "bv*+ba/b")
            addOption("-S", sortFor(q))
        }
    }

    private fun looksLikeGif(url: String): Boolean =
        Uri.parse(url).path?.lowercase()?.endsWith(".gif") == true

    /** Para links sem ".gif" no nome: pergunta ao servidor que tipo de ficheiro é. */
    private fun isGifByContentType(url: String): Boolean = try {
        val conn = openConnection(url).apply { requestMethod = "HEAD" }
        conn.contentType?.startsWith("image/gif") == true
    } catch (_: Exception) {
        false
    }

    private fun gifPreview(url: String) = VideoPreview(
        title = gifFileName(url),
        thumbnail = url,
        uploader = null,
        site = "GIF",
        durationSec = 0,
    )

    private fun gifFileName(url: String): String {
        val name = Uri.parse(url).lastPathSegment?.substringBeforeLast('.')
            ?.replace(Regex("[^A-Za-z0-9._-]"), "_")?.take(100)
        return "${if (name.isNullOrBlank()) "gif_${System.currentTimeMillis()}" else name}.gif"
    }

    /** Baixa um ficheiro .gif diretamente, com progresso. */
    private fun downloadDirect(url: String, dir: File): File {
        val file = File(dir, gifFileName(url))
        val conn = openConnection(url)
        val total = conn.contentLengthLong.takeIf { it > 0 }
        val start = System.currentTimeMillis()
        conn.inputStream.use { input ->
            file.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                var lastUpdate = 0L
                while (true) {
                    if (!_state.value.busy) throw YoutubeDL.CanceledException()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    val now = System.currentTimeMillis()
                    if (now - lastUpdate > 300) {
                        lastUpdate = now
                        val speed = done * 1000.0 / (now - start).coerceAtLeast(1)
                        _state.update { it.copy(progress = estimateProgress(done, total), status = sizeProgressText(done, total, speed, 1)) }
                    }
                }
            }
        }
        return file
    }

    private fun openConnection(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            // Alguns sites recusam pedidos sem User-Agent de navegador
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36")
        }

    private fun formatEta(sec: Long): String =
        if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec % 3600 / 60, sec % 60)
        else "%d:%02d".format(sec / 60, sec % 60)

    /** Etapas depois do download, que não têm percentagem. */
    private fun postProcessingStep(line: String): String? = when {
        line.startsWith("[Merger]") -> "Juntando vídeo e áudio…"
        line.startsWith("[FixupM3u8]") || line.startsWith("[Fixup") -> "Corrigindo o vídeo…"
        line.startsWith("[VideoRemuxer]") -> "Convertendo para MP4…"
        line.startsWith("[VideoConvertor]") -> "Criando o GIF… (pode demorar)"
        line.startsWith("[ExtractAudio]") -> "Convertendo para MP3…"
        line.startsWith("[info]") || line.startsWith("[youtube]") -> "Preparando o download…"
        else -> null
    }

    private fun sortFor(q: Quality): String = when (q) {
        Quality.P1080 -> "res:1080,vcodec:h264,acodec:m4a"
        Quality.P720 -> "res:720,vcodec:h264,acodec:m4a"
        Quality.P480 -> "res:480,vcodec:h264,acodec:m4a"
        else -> "vcodec:h264,res,acodec:m4a"
    }

    private fun shortError(e: Exception): String {
        val msg = e.message ?: e.javaClass.simpleName
        return msg.lines().lastOrNull { it.contains("ERROR") }?.substringAfter("ERROR:")?.trim()
            ?: msg.take(300)
    }
}

class DownloadViewModel(app: Application) : AndroidViewModel(app) {

    val state: StateFlow<UiState> = Downloads.state

    init {
        Downloads.init(app)
    }

    fun setUrl(url: String) = Downloads.setUrl(url)
    fun setQuality(q: Quality) = Downloads.setQuality(q)
    fun analyze() = Downloads.analyze()
    fun download() = Downloads.startDownload(getApplication())
    fun cancel() = Downloads.cancel()
    fun updateEngine() = Downloads.updateEngine(getApplication())
}
