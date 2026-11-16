package com.leitura.app

import java.io.File
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A dedicated destination: no update dialog is shown on the library. */
@Composable
fun UpdatesScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val updater = remember { ReleaseUpdater(context) }
    val scope = rememberCoroutineScope()
    var update by remember { mutableStateOf<AppUpdate?>(null) }
    var busy by remember { mutableStateOf(false) }
    var downloaded by remember { mutableStateOf<File?>(null) }
    var message by remember { mutableStateOf("Consulte as novidades quando quiser.") }
    var progress by remember { mutableStateOf<Float?>(null) }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("Atualizações", fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
        Text("Uma história que continua melhorando.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(Modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Versão instalada", style = MaterialTheme.typography.labelLarge, color = Gold)
                Text(BuildConfig.VERSION_NAME, style = MaterialTheme.typography.titleLarge)
                update?.let { Text("Nova versão: " + it.versionName, style = MaterialTheme.typography.titleMedium) }
                Text(message)
                if (busy) {
                    val amount = progress
                    if (amount != null) LinearProgressIndicator(progress = { amount }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Button(enabled = !busy, onClick = {
                    scope.launch {
                        busy = true; message = "Consultando atualizações…"
                        runCatching { withContext(Dispatchers.IO) { updater.checkForUpdate() } }
                            .onSuccess {
                                update = it; downloaded = null
                                message = if (it == null) "Você está usando a versão mais recente disponível."
                                    else "Uma nova versão está pronta para baixar."
                            }
                            .onFailure { message = "Não foi possível consultar atualizações. Verifique sua conexão e tente novamente." }
                        busy = false
                    }
                }) { Text("Verificar atualizações") }
                val available = update
                if (available != null) FilledTonalButton(enabled = !busy, onClick = {
                    val file = downloaded
                    if (file == null) scope.launch {
                        busy = true; message = "Baixando atualização…"
                        runCatching {
                            withContext(Dispatchers.IO) {
                                updater.download(available) { value -> scope.launch { progress = value } }
                            }
                        }.onSuccess { downloaded = it; message = "Arquivo e assinatura verificados. Toque em Instalar." }
                            .onFailure { message = it.message ?: "Falha no download. Tente novamente." }
                        busy = false; progress = null
                    } else runCatching {
                        if (updater.canInstall()) context.startActivity(updater.installIntent(file))
                        else {
                            message = "Autorize a instalação para o Leitura e volte para tocar em Instalar."
                            context.startActivity(updater.permissionIntent())
                        }
                    }.onFailure { message = "Não foi possível abrir o instalador. Tente novamente." }
                }) { Text(if (downloaded == null) "Baixar atualização" else "Instalar") }
            }
        }
        Text("Seus livros continuam disponíveis offline. A internet é usada apenas para consultar e baixar novas versões.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}
