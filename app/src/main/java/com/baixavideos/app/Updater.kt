package com.baixavideos.app

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class Release(val versionCode: Int, val name: String, val apkUrl: String, val notes: String)

data class UpdateState(
    val available: Release? = null,
    val downloading: Boolean = false,
    val progress: Float = 0f,
    val error: String? = null,
)

/**
 * Procura novas versões nas Releases do GitHub (publicadas pelo workflow) e instala-as.
 * O repositório vem de BuildConfig.UPDATE_REPO, preenchido automaticamente no build do GitHub.
 */
object Updater {
    private val _state = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = _state
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val enabled: Boolean get() = BuildConfig.UPDATE_REPO.isNotBlank()

    fun check() {
        if (!enabled) return
        scope.launch {
            try {
                val conn = open("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                if (conn.responseCode != 200) return@launch
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val code = json.getString("tag_name").removePrefix("v").toIntOrNull() ?: return@launch
                if (code <= BuildConfig.VERSION_CODE) return@launch
                val assets = json.getJSONArray("assets")
                val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                    .firstOrNull { it.getString("name").endsWith(".apk") } ?: return@launch
                val release = Release(code, json.optString("name"), apk.getString("browser_download_url"), json.optString("body"))
                _state.update { it.copy(available = release, error = null) }
            } catch (_: Exception) {
                // Sem internet ou GitHub indisponível: tenta de novo na próxima abertura
            }
        }
    }

    fun downloadAndInstall(context: Context) {
        val release = _state.value.available ?: return
        if (_state.value.downloading) return
        val app = context.applicationContext
        scope.launch {
            _state.update { it.copy(downloading = true, progress = 0f, error = null) }
            try {
                val dir = File(app.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
                val file = File(dir, "BaixaVideos-${release.versionCode}.apk")
                val conn = open(release.apkUrl)
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val pct = if (total > 0) (done * 100 / total).toInt() else -1
                            if (pct != lastPct) {
                                lastPct = pct
                                _state.update { it.copy(progress = pct / 100f) }
                            }
                        }
                    }
                }
                _state.update { it.copy(downloading = false, progress = 1f) }
                install(app, file)
            } catch (e: Exception) {
                _state.update { it.copy(downloading = false, error = "Falha ao baixar a atualização: ${e.message}") }
            }
        }
    }

    private fun install(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }
}
