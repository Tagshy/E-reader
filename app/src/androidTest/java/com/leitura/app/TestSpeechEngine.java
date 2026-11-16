package com.leitura.app;

import android.media.AudioFormat;
import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeechService;
import android.speech.tts.Voice;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Standalone engine in the instrumentation APK: Android and Java APIs only. */
public class TestSpeechEngine extends TextToSpeechService {
    private volatile boolean stopped = false;

    @Override public String[] onGetLanguage() { return new String[] {"por", "BRA", ""}; }
    @Override public int onIsLanguageAvailable(String lang, String country, String variant) {
        return ("por".equals(lang) || "pt".equals(lang))
            ? TextToSpeech.LANG_COUNTRY_AVAILABLE : TextToSpeech.LANG_NOT_SUPPORTED;
    }
    @Override public int onLoadLanguage(String lang, String country, String variant) {
        return onIsLanguageAvailable(lang, country, variant);
    }
    @Override public List<Voice> onGetVoices() {
        return Arrays.asList(new Voice("test-pt-br", new Locale("pt", "BR"),
            Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, Collections.emptySet()));
    }
    @Override public String onGetDefaultVoiceNameFor(String lang, String country, String variant) {
        return "test-pt-br";
    }
    @Override public int onIsValidVoiceName(String name) {
        return "test-pt-br".equals(name) ? TextToSpeech.SUCCESS : TextToSpeech.ERROR;
    }
    @Override public int onLoadVoice(String name) { return onIsValidVoiceName(name); }
    @Override public void onStop() { stopped = true; }
    @Override public void onSynthesizeText(SynthesisRequest request, SynthesisCallback callback) {
        stopped = false;
        callback.start(16000, AudioFormat.ENCODING_PCM_16BIT, 1);
        byte[] frame = new byte[3200];
        for (int index = 0; index < 20; index++) {
            if (stopped) return;
            callback.audioAvailable(frame, 0, frame.length);
        }
        if (!stopped) callback.done();
    }
}
