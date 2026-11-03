package com.baixavideos.app

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage

class MainActivity : ComponentActivity() {

    private val vm: DownloadViewModel by viewModels()

    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                // Notificação com o progresso do download em segundo plano
                permissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ->
                permissions.launch(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
        }
        handleShare(intent)
        Updater.check()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFE53935),
                    secondary = Color(0xFFFF7043),
                    background = Color(0xFF121212),
                    surface = Color(0xFF1E1E1E),
                )
            ) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen(vm)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** Recebe links compartilhados de outros apps (navegador, YouTube etc.). */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val url = Regex("https?://\\S+").find(text)?.value ?: return
        vm.setUrl(url)
        vm.analyze()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainScreen(vm: DownloadViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    val update by Updater.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("BaixaVídeos", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "YouTube, Instagram, TikTok, X/Twitter e mais de 1000 sites.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        update.available?.let { release ->
            UpdateCard(release, update, onUpdate = { Updater.downloadAndInstall(context) })
        }

        OutlinedTextField(
            value = s.url,
            onValueChange = vm::setUrl,
            label = { Text("Link do vídeo") },
            singleLine = true,
            enabled = !s.busy,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { vm.analyze() }),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = !s.busy,
                onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                    if (text.isNotBlank()) {
                        vm.setUrl(text)
                        vm.analyze()
                    }
                },
            ) { Text("Colar") }
            Button(enabled = s.ready && !s.busy && s.url.isNotBlank(), onClick = vm::analyze) { Text("Analisar") }
        }

        s.preview?.let { p ->
            Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Column {
                    if (p.thumbnail != null) {
                        AsyncImage(
                            model = p.thumbnail,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                                .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
                        )
                    }
                    Column(Modifier.padding(12.dp)) {
                        Text(p.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val meta = listOfNotNull(p.site, p.uploader, formatDuration(p.durationSec)).joinToString(" • ")
                        if (meta.isNotEmpty()) {
                            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        Text("Qualidade", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Quality.entries.forEach { q ->
                FilterChip(
                    selected = s.quality == q,
                    onClick = { vm.setQuality(q) },
                    enabled = !s.busy,
                    label = { Text(q.label) },
                )
            }
        }

        if (s.busy) {
            Button(
                onClick = vm::cancel,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Cancelar") }
            if (s.progress >= 0f) {
                LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        } else {
            Button(
                enabled = s.ready && s.url.isNotBlank(),
                onClick = vm::download,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Baixar", fontWeight = FontWeight.Bold) }
        }

        Text(s.status, style = MaterialTheme.typography.bodyMedium)
        if (s.busy && s.progress >= 0f) {
            Text(
                "Pode sair do app: o download continua em segundo plano (veja a notificação).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (s.busy && s.log.isNotBlank()) {
            Text(s.log, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        TextButton(enabled = s.ready && !s.busy, onClick = vm::updateEngine) {
            Text("Atualizar yt-dlp (use se algum site parar de funcionar)")
        }
        Text(
            "Os arquivos ficam em Download/BaixaVideos. Baixe apenas conteúdo que você tem direito de salvar.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun UpdateCard(release: Release, update: UpdateState, onUpdate: () -> Unit) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Nova versão disponível (${release.versionCode})", fontWeight = FontWeight.SemiBold)
            if (release.notes.isNotBlank()) {
                Text(release.notes, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            if (update.downloading) {
                LinearProgressIndicator(progress = { update.progress.coerceAtLeast(0f) }, modifier = Modifier.fillMaxWidth())
                Text("Baixando atualização… mantenha o app aberto.", style = MaterialTheme.typography.bodySmall)
            } else {
                Button(onClick = onUpdate) { Text(if (update.progress >= 1f) "Instalar" else "Atualizar agora") }
            }
            update.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}

private fun formatDuration(sec: Int): String? {
    if (sec <= 0) return null
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
