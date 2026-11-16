package com.leitura.app

import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NarrationControls(book: Book, page: Int) {
    val context = LocalContext.current
    var opened by remember { mutableStateOf(false) }
    val state by NarrationController.state.collectAsState()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        FilledTonalButton(onClick = { opened = true }) { Text("Ouvir livro") }
        if (state.uri == book.uri && state.running) TextButton(
            onClick = { NarrationController.command(context, NarrationService.PAUSE) }) { Text("Pausar áudio") }
    }
    if (opened) ModalBottomSheet(onDismissRequest = { opened = false }, containerColor = Ink) {
        NarrationPanel(book, page, Modifier.navigationBarsPadding())
    }
}

@Composable
private fun NarrationPanel(book: Book, page: Int, modifier: Modifier) {
    val context = LocalContext.current
    val preferences = remember { NarrationPreferences(context) }
    val state by NarrationController.state.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    var voiceName by remember { mutableStateOf(preferences.voiceName) }
    var speed by remember { mutableFloatStateOf(preferences.speed) }
    var voiceError by remember { mutableStateOf("") }
    val voices by produceState<List<Voice>?>(null, refresh) {
        value = null; voiceError = ""
        val initialized = CompletableDeferred<Int>()
        val engine = TextToSpeech(context) { initialized.complete(it) }
        try {
            val result = withTimeout(20000) { initialized.await() }
            if (result == TextToSpeech.SUCCESS) value = offlinePortugueseVoices(engine.voices.orEmpty())
            else { value = emptyList(); voiceError = "O mecanismo de voz não está disponível." }
        } catch (_: TimeoutCancellationException) { value = emptyList(); voiceError = "O mecanismo de voz demorou para responder. Tente consultar novamente." }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { value = emptyList(); voiceError = "Não foi possível consultar as vozes." }
        finally { engine.shutdown() }
    }
    val active = state.uri == book.uri
    val saved = preferences.position(book.uri)
    val available = voices
    val currentVoice = available?.find { it.name == voiceName } ?: available?.firstOrNull()
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)
        .padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Ouvir livro", style = MaterialTheme.typography.headlineSmall)
        Text(book.title, style = MaterialTheme.typography.titleMedium)
        Text("Continue ouvindo com a tela apagada ou usando outro aplicativo.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (active && state.message.isNotBlank()) Text(state.message, color = Gold)
        if (available == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Consultando vozes offline…")
        } else if (available.isEmpty()) {
            Text(voiceError.ifBlank { "Nenhuma voz offline de português instalada." })
            Text("Baixe os dados de uma voz de português nas configurações de fala do Android e volte ao Leitura.")
            OutlinedButton(onClick = {
                val install = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
                runCatching { context.startActivity(install) }
                    .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) } }
            }) { Text("Configurar vozes no Android") }
            TextButton(onClick = { refresh++ }) { Text("Consultar vozes novamente") }
        } else {
            Text("Voz offline", style = MaterialTheme.typography.labelLarge, color = Gold)
            // Buttons avoid an unbounded nested menu on phones with many installed voices.
            available.forEachIndexed { index, voice ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = voice.name == currentVoice?.name, onClick = {
                        voiceName = voice.name; preferences.voiceName = voice.name
                        if (state.running) NarrationController.command(context, NarrationService.OPTIONS)
                    })
                    Text("Voz " + (index + 1) + " · " + voice.locale.getDisplayCountry(java.util.Locale("pt", "BR")),
                        modifier = Modifier.weight(1f))
                }
            }
            Text("Velocidade: " + String.format(java.util.Locale("pt", "BR"), "%.2f×", speed))
            Slider(value = speed, onValueChange = { speed = it }, valueRange = 0.5f..2f, steps = 5,
                onValueChangeFinished = {
                    preferences.speed = speed
                    if (state.running) NarrationController.command(context, NarrationService.OPTIONS)
                })
            Button(onClick = {
                preferences.voiceName = currentVoice?.name.orEmpty(); preferences.speed = speed
                NarrationController.start(context, book, NarrationPosition(page, 0))
            }, modifier = Modifier.fillMaxWidth()) { Text("Ouvir a partir da página " + (page + 1)) }
            if (saved != null && !saved.completed) OutlinedButton(onClick = {
                preferences.voiceName = currentVoice?.name.orEmpty(); preferences.speed = speed
                preferences.position(book.uri)?.let { NarrationController.start(context, book, it) }
            }, modifier = Modifier.fillMaxWidth()) { Text("Retomar narração · página " + (saved.page + 1)) }
        }
        if (active && (state.running || state.status == NarrationStatus.PAUSED)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.running) FilledTonalButton(onClick = {
                    NarrationController.command(context, NarrationService.PAUSE)
                }) { Text("Pausar") }
                TextButton(onClick = { NarrationController.command(context, NarrationService.STOP) }) { Text("Encerrar áudio") }
            }
        }
        Text("A posição do áudio é salva separadamente da página de leitura. Em vozes sem marcação de palavras, a retomada pode repetir um pequeno trecho. PDFs escaneados podem apresentar erros de reconhecimento.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun NarrationMiniPlayer() {
    val context = LocalContext.current
    val state by NarrationController.state.collectAsState()
    if (state.uri.isBlank() || state.status == NarrationStatus.IDLE) return
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            Text(state.message, maxLines = 2, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.running) TextButton(onClick = {
                    NarrationController.command(context, NarrationService.PAUSE)
                }) { Text("Pausar áudio") }
                else if (state.status != NarrationStatus.COMPLETED) TextButton(onClick = {
                    val book = LibraryStore(context).load().find { it.uri == state.uri }
                    if (book != null) NarrationPreferences(context).position(book.uri)?.let {
                        NarrationController.start(context, book, it)
                    }
                }) { Text("Retomar áudio") }
                TextButton(onClick = { NarrationController.command(context, NarrationService.STOP) }) { Text("Encerrar") }
            }
        }
    }
}
