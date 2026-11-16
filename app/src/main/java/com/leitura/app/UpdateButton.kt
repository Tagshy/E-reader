package com.leitura.app

import java.io.File
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun UpdateButton() {
    val context = LocalContext.current
    val updater = remember { ReleaseUpdater(context) }
    val scope = rememberCoroutineScope()
    var update by remember { mutableStateOf<AppUpdate?>(null) }
    var dialog by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var downloaded by remember { mutableStateOf<File?>(null) }
    var message by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(Unit) {
        update = withContext(Dispatchers.IO) { runCatching { updater.checkForUpdate() }.getOrNull() }
    }
    TextButton(onClick = {
        dialog = true
        if (update == null && !busy) scope.launch {
            busy = true; message = "Consultando atualizações…"
            runCatching { withContext(Dispatchers.IO) { updater.checkForUpdate() } }
                .onSuccess { update = it; message = if (it == null) "Você está usando a versão mais recente disponível." else "" }
                .onFailure { message = "Não foi possível consultar atualizações. Verifique sua conexão e tente novamente." }
            busy = false
        }
    }) { Text(if (update != null) "Atualizar" else "Atualizações") }
    if (dialog) AlertDialog(onDismissRequest = { if (!busy) dialog = false },
        title = { Text(update?.let { "Leitura " + it.versionName + " disponível" } ?: "Atualizações") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Versão instalada: " + BuildConfig.VERSION_NAME)
            if (message.isNotBlank()) Text(message)
            if (update != null && downloaded == null && !busy)
                Text("Baixe a nova versão e confirme a atualização no Android. Seus livros, favoritos e progresso serão mantidos com a mesma assinatura.")
            if (busy) {
                val amount = progress
                if (amount != null) LinearProgressIndicator(progress = { amount }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (downloaded != null)
                Text("APK verificado. Se o Android pedir autorização para instalar atualizações deste app, permita e depois toque em Instalar novamente.")
        } },
        confirmButton = {
            val available = update
            if (available != null) TextButton(enabled = !busy, onClick = {
                val file = downloaded
                if (file == null) scope.launch {
                    busy = true; message = "Baixando atualização…"
                    runCatching {
                        withContext(Dispatchers.IO) { updater.download(available) { value -> scope.launch { progress = value } } }
                    }.onSuccess { downloaded = it; message = "Arquivo e assinatura verificados. Toque em Instalar." }
                        .onFailure { message = it.message ?: "Falha no download. Tente novamente." }
                    busy = false; progress = null
                } else runCatching {
                    if (updater.canInstall()) context.startActivity(updater.installIntent(file))
                    else context.startActivity(updater.permissionIntent())
                }.onFailure { message = "Não foi possível abrir o instalador. Tente novamente." }
            }) { Text(if (downloaded == null) "Baixar atualização" else "Instalar") }
            else TextButton(enabled = !busy, onClick = { dialog = false }) { Text("OK") }
        },
        dismissButton = {
            if (update != null) TextButton(enabled = !busy, onClick = { dialog = false }) { Text("Depois") }
        })
}
