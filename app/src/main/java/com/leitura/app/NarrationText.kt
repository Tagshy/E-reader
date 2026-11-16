package com.leitura.app

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

fun normalizeNarrationText(text: String): String = text
    .replace("\u00ad", "")
    .replace(Regex("(?<=\\p{L})-\\s*\\n\\s*(?=\\p{L})"), "")
    .replace(Regex("\\s+"), " ").trim()

data class SpeechChunk(val start: Int, val end: Int, val text: String)

/** Bounded phrases provide a safe fallback when an engine has no word callbacks. */
fun speechChunk(text: String, offset: Int, limit: Int = 350): SpeechChunk? {
    require(limit >= 2)
    var start = offset.coerceIn(0, text.length)
    while (start < text.length && text[start].isWhitespace()) start++
    if (start >= text.length) return null
    var end = (start + limit).coerceAtMost(text.length)
    if (end < text.length) {
        val boundary = (end - 1 downTo start + limit / 3).firstOrNull { text[it] in ".!?;:" }
            ?: (end - 1 downTo start + limit / 3).firstOrNull { text[it].isWhitespace() }
        if (boundary != null) end = boundary + 1
        if (end > start && Character.isHighSurrogate(text[end - 1])) end--
    }
    return SpeechChunk(start, end, text.substring(start, end))
}

fun offlinePortugueseVoices(voices: Set<Voice>): List<Voice> = voices.filter {
    it.locale.language == "pt" && !it.isNetworkConnectionRequired &&
        !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
}.sortedWith(compareBy<Voice> { if (it.locale.country == "BR") 0 else 1 }
    .thenByDescending { it.quality }.thenBy { it.name })

data class NarrationPosition(val page: Int = 0, val offset: Int = 0, val completed: Boolean = false)

class NarrationPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("narration", Context.MODE_PRIVATE)
    var voiceName: String
        get() = prefs.getString("voice", "") ?: ""
        set(value) { prefs.edit().putString("voice", value).apply() }
    var speed: Float
        get() = prefs.getFloat("speed", 1f).coerceIn(0.5f, 2f)
        set(value) { prefs.edit().putFloat("speed", value.coerceIn(0.5f, 2f)).apply() }
    fun position(uri: String): NarrationPosition? {
        if (!prefs.contains("page:$uri")) return null
        return NarrationPosition(prefs.getInt("page:$uri", 0).coerceAtLeast(0),
            prefs.getInt("offset:$uri", 0).coerceAtLeast(0), prefs.getBoolean("done:$uri", false))
    }
    fun save(uri: String, position: NarrationPosition) {
        prefs.edit().putInt("page:$uri", position.page).putInt("offset:$uri", position.offset)
            .putBoolean("done:$uri", position.completed).apply()
    }
}

/** Extract native PDF text first. Image-only pages use the bundled, local Latin OCR model. */
class NarrationTextSource(private val context: Context) {
    private val store = LibraryStore(context)
    private val recognizerDelegate = lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val recognizer by recognizerDelegate
    suspend fun page(book: Book, page: Int): String {
        val native = withContext(Dispatchers.IO) {
            PDFBoxResourceLoader.init(context.applicationContext)
            store.openInput(book).use { stream ->
                PDDocument.load(stream, MemoryUsageSetting.setupMixed(8L * 1024 * 1024)).use { pdf ->
                    check(pdf.currentAccessPermission.canExtractContent()) { "Este PDF não permite extrair texto." }
                    val stripper = PDFTextStripper().apply {
                        sortByPosition = true
                        startPage = page + 1; endPage = page + 1
                    }
                    normalizeNarrationText(stripper.getText(pdf))
                }
            }
        }
        if (native.isNotBlank()) return native
        val bitmap = withContext(Dispatchers.IO) { store.render(book, page, 1440) }
        // Keep the bitmap alive until the OCR task finishes, including when its caller cancels.
        return suspendCancellableCoroutine { continuation ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result ->
                    if (continuation.isActive) continuation.resume(normalizeNarrationText(result.text))
                    bitmap.recycle()
                }
                .addOnFailureListener { error ->
                    if (continuation.isActive) continuation.resumeWithException(error)
                    bitmap.recycle()
                }
        }
    }
    fun close() { if (recognizerDelegate.isInitialized()) recognizer.close() }
}
