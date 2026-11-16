package com.leitura.app

import android.media.AudioFormat
import android.speech.tts.*
import java.util.Locale

/** Installed only inside the instrumentation APK. Produces bounded silent PCM offline. */
class TestSpeechEngine : TextToSpeechService() {
    @Volatile private var stopped = false
    override fun onGetLanguage(): Array<String> = arrayOf("por", "BRA", "")
    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int =
        if (lang == "por" || lang == "pt") TextToSpeech.LANG_COUNTRY_AVAILABLE else TextToSpeech.LANG_NOT_SUPPORTED
    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int = onIsLanguageAvailable(lang, country, variant)
    override fun onGetVoices(): List<Voice> = listOf(Voice("test-pt-br", Locale("pt", "BR"),
        Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, emptySet()))
    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String = "test-pt-br"
    override fun onIsValidVoiceName(voiceName: String?): Int = if (voiceName == "test-pt-br") TextToSpeech.SUCCESS else TextToSpeech.ERROR
    override fun onLoadVoice(voiceName: String?): Int = onIsValidVoiceName(voiceName)
    override fun onStop() { stopped = true }
    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (callback == null) return
        stopped = false
        callback.start(16000, AudioFormat.ENCODING_PCM_16BIT, 1)
        val frame = ByteArray(3200)
        repeat(20) {
            if (stopped) return
            callback.audioAvailable(frame, 0, frame.size)
        }
        if (!stopped) callback.done()
    }
}
