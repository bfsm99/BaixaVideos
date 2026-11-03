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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

enum class Quality(val label: String) {
    BEST("Melhor"),
    P1080("1080p"),
    P720("720p"),
    P480("480p"),
    AUDIO("Só áudio (MP3)"),
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

    fun setUrl(url: String) = _state.update { it.copy(url = url.trim(), preview = null) }
    fun setQuality(q: Quality) = _state.update { it.copy(quality = q) }

    fun analyze() {
        val url = _state.value.url
        if (url.isBlank() || !_state.value.ready || _state.value.busy) return
        job = scope.launch {
            _state.update { it.copy(busy = true, progress = -1f, status = "Buscando informações…", preview = null) }
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
                val req = YoutubeDLRequest(s.url).apply {
                    addOption("--no-playlist")
                    addOption("--no-mtime")
                    addOption("--restrict-filenames")
                    addOption("-o", "${tmpDir.absolutePath}/%(title).120B [%(id)s].%(ext)s")
                    if (s.quality == Quality.AUDIO) {
                        addOption("-x")
                        addOption("--audio-format", "mp3")
                        addOption("--audio-quality", "0")
                    } else {
                        // Prefere H.264 + AAC: o formato mais compatível com players de celular
                        addOption("-f", "bv*+ba/b")
                        addOption("-S", sortFor(s.quality))
                        addOption("--merge-output-format", "mp4")
                        addOption("--remux-video", "mp4")
                        // HLS (vídeo em segmentos) baixado pelo FFmpeg gera timestamps corretos para avançar o vídeo
                        addOption("--downloader", "m3u8:ffmpeg")
                        addOption("--postprocessor-args", "ffmpeg:-movflags +faststart")
                    }
                }
                YoutubeDL.getInstance().execute(req, PROCESS_ID) { progress, eta, line ->
                    _state.update {
                        it.copy(
                            progress = progress / 100f,
                            status = if (progress > 0) "Baixando… ${progress.toInt()}%  (faltam ${eta}s)" else "Processando…",
                            log = line,
                        )
                    }
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
