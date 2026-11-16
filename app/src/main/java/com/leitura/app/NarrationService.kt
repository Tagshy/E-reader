package com.leitura.app

import android.app.*
import android.content.*
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

enum class NarrationStatus { IDLE, PREPARING, PLAYING, PAUSED, ERROR, COMPLETED }
data class NarrationState(val uri: String = "", val title: String = "", val page: Int = 0,
    val total: Int = 0, val status: NarrationStatus = NarrationStatus.IDLE, val message: String = "") {
    val running: Boolean get() = status == NarrationStatus.PLAYING || status == NarrationStatus.PREPARING
}

object NarrationController {
    val state = MutableStateFlow(NarrationState())
    fun start(context: Context, book: Book, position: NarrationPosition) {
        send(context, Intent(context, NarrationService::class.java).setAction(NarrationService.START)
            .putExtra("uri", book.uri).putExtra("page", position.page).putExtra("offset", position.offset), true)
    }
    fun command(context: Context, action: String) {
        send(context, Intent(context, NarrationService::class.java).setAction(action), action == NarrationService.RESUME)
    }
    private fun send(context: Context, intent: Intent, foreground: Boolean) {
        runCatching {
            if (foreground) ContextCompat.startForegroundService(context, intent) else context.startService(intent)
        }.onFailure {
            state.value = state.value.copy(status = NarrationStatus.ERROR,
                message = "Não foi possível iniciar o áudio. Abra o Leitura e tente novamente.")
        }
    }
}

/** Speech belongs to this service, never to an Activity or a composable.
 * All cursor changes and TTS callbacks are serialized on the main thread.
 */
class NarrationService : Service() {
    companion object {
        const val START = "com.leitura.app.narration.START"
        const val PAUSE = "com.leitura.app.narration.PAUSE"
        const val RESUME = "com.leitura.app.narration.RESUME"
        const val STOP = "com.leitura.app.narration.STOP"
        const val NEXT = "com.leitura.app.narration.NEXT"
        const val PREVIOUS = "com.leitura.app.narration.PREVIOUS"
        const val OPTIONS = "com.leitura.app.narration.OPTIONS"
        const val NOTIFICATION = 291
        private const val CHANNEL = "narration"
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var preferences: NarrationPreferences
    private lateinit var source: NarrationTextSource
    private lateinit var audio: AudioManager
    private lateinit var focus: AudioFocusRequest
    private lateinit var session: MediaSession
    private lateinit var wake: PowerManager.WakeLock
    private var engine: TextToSpeech? = null
    private var ready: CompletableDeferred<Unit>? = null
    private var enginePackage: String? = null
    private var work: Job? = null
    private var watchdog: Job? = null
    private var book: Book? = null
    private var page = 0
    private var offset = 0
    private var count = 0
    private var generation = 0
    private var utterance: String? = null
    private var chunkStart = 0
    private var chunkEnd = 0
    private var cachedPage = -1
    private var cachedText = ""
    private var spoken = false
    private var focusHeld = false

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { pause() }
    }

    override fun onCreate() {
        super.onCreate()
        preferences = NarrationPreferences(this)
        source = NarrationTextSource(this)
        audio = getSystemService(AudioManager::class.java)
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
            .setWillPauseWhenDucked(true).setOnAudioFocusChangeListener({ change ->
                if (change < 0) pause()
            }, handler).build()
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Leitura:Narration")
            .apply { setReferenceCounted(false) }
        session = MediaSession(this, "Leitura narration").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { resume() }
                override fun onPause() { pause() }
                override fun onStop() { finishService() }
                override fun onSkipToNext() { skip(1) }
                override fun onSkipToPrevious() { skip(-1) }
            }, handler)
            setSessionActivity(PendingIntent.getActivity(this@NarrationService, 0,
                Intent(this@NarrationService, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Narração de livros", NotificationManager.IMPORTANCE_LOW))
        ContextCompat.registerReceiver(this, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START -> {
                val requested = LibraryStore(this).load().find { it.uri == intent.getStringExtra("uri") }
                // Only the debug APK can use the installed instrumentation TTS engine.
                val testPackage = if (BuildConfig.DEBUG) intent.getStringExtra("test_engine")?.takeIf {
                    it == packageName + ".test"
                } else null
                if (requested == null) {
                    ensureForeground(); fail("Livro indisponível. Importe novamente o PDF.")
                } else begin(requested, NarrationPosition(intent.getIntExtra("page", 0), intent.getIntExtra("offset", 0)), testPackage)
            }
            RESUME -> resume()
            PAUSE -> pause()
            NEXT -> skip(1)
            PREVIOUS -> skip(-1)
            OPTIONS -> if (NarrationController.state.value.running) book?.let { begin(it, NarrationPosition(page, offset), enginePackage) }
            STOP -> finishService()
            else -> if (book == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun begin(selected: Book, position: NarrationPosition, requestedEngine: String? = null) {
        halt()
        book = selected
        page = position.page.coerceAtLeast(0)
        offset = position.offset.coerceAtLeast(0)
        cachedPage = -1; cachedText = ""; spoken = false; count = 0
        publish(NarrationStatus.PREPARING, "Preparando leitura offline…", notify = false)
        ensureForeground()
        val token = generation
        work = scope.launch {
            try {
                withTimeout(20000) { initializeEngine(requestedEngine) }
                val tts = engine ?: error("O mecanismo de voz não está disponível.")
                val voices = offlinePortugueseVoices(tts.voices.orEmpty())
                val selectedVoice = voices.find { it.name == preferences.voiceName } ?: voices.firstOrNull()
                    ?: error("Nenhuma voz offline de português instalada. Baixe uma voz nas configurações de fala do Android.")
                check(tts.setVoice(selectedVoice) == TextToSpeech.SUCCESS) { "Não foi possível carregar a voz escolhida." }
                check(tts.setSpeechRate(preferences.speed) == TextToSpeech.SUCCESS) { "Não foi possível ajustar a velocidade." }
                tts.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                count = withContext(Dispatchers.IO) { LibraryStore(this@NarrationService).count(selected) }
                page = page.coerceIn(0, count - 1)
                check(audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    "O áudio está sendo usado por outro aplicativo. Tente novamente."
                }
                focusHeld = true
                session.isActive = true
                advance(token)
            } catch (timeout: TimeoutCancellationException) { if (token == generation) fail("A preparação da voz demorou demais. Tente novamente.") }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (token == generation) fail(error.message ?: "Não foi possível narrar este PDF.") }
        }
    }

    private suspend fun initializeEngine(requested: String?) {
        if (engine == null || requested != enginePackage) {
            engine?.shutdown()
            enginePackage = requested
            val initialized = CompletableDeferred<Unit>()
            ready = initialized
            engine = TextToSpeech(this, { result ->
                if (result == TextToSpeech.SUCCESS) initialized.complete(Unit)
                else initialized.completeExceptionally(IllegalStateException("Instale ou ative um mecanismo de voz no Android."))
            }, requested)
            engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) { handler.post {
                    if (id == utterance) publish(NarrationStatus.PLAYING, "Narrando página " + (page + 1))
                } }
                override fun onRangeStart(id: String?, start: Int, end: Int, frame: Int) { handler.post {
                    if (id == utterance) { offset = (chunkStart + start).coerceAtMost(chunkEnd); persist() }
                } }
                override fun onDone(id: String?) { handler.post {
                    if (id == utterance) {
                        utterance = null
                        watchdog?.cancel(); watchdog = null
                        offset = chunkEnd; spoken = true; persist()
                        val token = generation
                        work = scope.launch {
                            try { advance(token) }
                            catch (timeout: TimeoutCancellationException) { if (token == generation) fail("A leitura desta página demorou demais. Tente novamente.") }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: Exception) { if (token == generation) fail(error.message ?: "Falha na leitura do PDF.") }
                        }
                    }
                } }
                @Deprecated("Android compatibility")
                override fun onError(id: String?) { speechError(id) }
                override fun onError(id: String?, errorCode: Int) { speechError(id) }
            })
        }
        ready?.await()
    }

    private fun speechError(id: String?) { handler.post {
        if (id == utterance) fail("A voz não conseguiu ler este trecho. Confira se os dados offline da voz estão instalados.")
    } }

    private suspend fun advance(token: Int) {
        val selected = book ?: return
        while (page < count && token == generation) {
            if (cachedPage != page) {
                publish(NarrationStatus.PREPARING, "Preparando página " + (page + 1) + "…")
                wake.acquire(30 * 60 * 1000L)
                cachedText = withTimeout(60000) { source.page(selected, page) }
                if (token != generation) return
                cachedPage = page
            }
            val chunk = speechChunk(cachedText, offset)
            if (chunk != null) {
                chunkStart = chunk.start; chunkEnd = chunk.end; offset = chunk.start
                utterance = "$generation:$page:" + chunk.start
                persist()
                val id = utterance
                watchdog?.cancel()
                watchdog = scope.launch {
                    delay(120000)
                    if (id == utterance) fail("A voz parou de responder. Tente retomar a narração.")
                }
                wake.acquire(30 * 60 * 1000L)
                publish(NarrationStatus.PLAYING, "Narrando página " + (page + 1))
                check(engine?.speak(chunk.text, TextToSpeech.QUEUE_FLUSH, Bundle(), utterance) == TextToSpeech.SUCCESS) {
                    "Não foi possível iniciar a voz offline."
                }
                return
            }
            page++; offset = 0
        }
        if (token == generation) {
            page = (count - 1).coerceAtLeast(0); offset = cachedText.length
            persist(completed = true)
            halt()
            publish(NarrationStatus.COMPLETED,
                if (spoken) "Você chegou ao fim do livro." else "Nenhum outro trecho legível foi encontrado.")
            stopForeground(STOP_FOREGROUND_DETACH)
        }
    }

    private fun persist(completed: Boolean = false) {
        book?.let { preferences.save(it.uri, NarrationPosition(page, offset, completed)) }
    }
    private fun halt() {
        generation++
        utterance = null
        watchdog?.cancel(); watchdog = null
        work?.cancel(); work = null
        engine?.stop()
        if (::wake.isInitialized && wake.isHeld) wake.release()
        if (focusHeld) { audio.abandonAudioFocusRequest(focus); focusHeld = false }
    }
    private fun pause() {
        if (!NarrationController.state.value.running) return
        persist(); halt()
        publish(NarrationStatus.PAUSED, "Narração pausada")
        stopForeground(STOP_FOREGROUND_DETACH)
    }
    private fun resume() {
        val selected = book
        if (selected == null) {
            ensureForeground(); fail("Abra um livro e escolha de onde deseja ouvir."); return
        }
        begin(selected, NarrationPosition(page, offset), enginePackage)
    }
    private fun skip(delta: Int) {
        val selected = book ?: return
        val target = (page + delta).coerceIn(0, (count - 1).coerceAtLeast(0))
        begin(selected, NarrationPosition(target, 0), enginePackage)
    }
    private fun fail(message: String) {
        persist(); halt()
        engine?.shutdown(); engine = null; ready = null
        publish(NarrationStatus.ERROR, message)
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }
    private fun finishService() {
        persist(); halt()
        NarrationController.state.value = NarrationState()
        session.isActive = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun action(action: String, request: Int): PendingIntent = PendingIntent.getService(this, request,
        Intent(this, NarrationService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun notification(): Notification {
        val state = NarrationController.state.value
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_narration).setContentTitle(state.title.ifBlank { "Leitura" })
            .setContentText(state.message).setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC).setOngoing(state.running)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_previous, "Página anterior", action(PREVIOUS, 1)).build())
            .addAction(Notification.Action.Builder(if (state.running) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (state.running) "Pausar" else "Continuar", action(if (state.running) PAUSE else RESUME, 2)).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_next, "Próxima página", action(NEXT, 3)).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Encerrar", action(STOP, 4)).build())
            .setDeleteIntent(action(STOP, 4))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }
    private fun ensureForeground() {
        session.isActive = true
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification(),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NOTIFICATION, notification())
    }
    private fun publish(status: NarrationStatus, message: String, notify: Boolean = true) {
        val selected = book
        NarrationController.state.value = NarrationState(selected?.uri.orEmpty(), selected?.title.orEmpty(), page, count, status, message)
        session.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, selected?.title ?: "Leitura")
            .putString(MediaMetadata.METADATA_KEY_ARTIST, "Página " + (page + 1)).build())
        val playback = when (status) {
            NarrationStatus.PLAYING -> PlaybackState.STATE_PLAYING
            NarrationStatus.PREPARING -> PlaybackState.STATE_BUFFERING
            NarrationStatus.PAUSED -> PlaybackState.STATE_PAUSED
            NarrationStatus.ERROR -> PlaybackState.STATE_ERROR
            else -> PlaybackState.STATE_STOPPED
        }
        session.setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
            .setState(playback, PlaybackState.PLAYBACK_POSITION_UNKNOWN, preferences.speed).build())
        if (notify) getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification())
    }

    override fun onDestroy() {
        val wasRunning = NarrationController.state.value.running
        if (wasRunning) persist()
        halt()
        source.close(); engine?.shutdown()
        if (wasRunning) NarrationController.state.value = NarrationController.state.value.copy(
            status = NarrationStatus.PAUSED, message = "Narração interrompida. Toque em Retomar para continuar.")
        session.release()
        unregisterReceiver(noisy)
        scope.cancel()
        super.onDestroy()
    }
}
