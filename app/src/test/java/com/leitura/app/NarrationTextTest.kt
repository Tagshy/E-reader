package com.leitura.app

import org.junit.Assert.*
import org.junit.Test

class NarrationTextTest {
    @Test fun normalizationJoinsLineHyphenationAndPreservesAccents() {
        assertEquals("Uma história extraordinária.", normalizeNarrationText("Uma histó\u00adria\nextraor-\ndinária."))
    }
    @Test fun chunksCoverAllTextWithoutSplittingUnicodeOrSkippingWords() {
        val text = normalizeNarrationText(("Ação e emoção 📖. " + "a".repeat(100) + " ").repeat(12))
        val chunks = mutableListOf<SpeechChunk>()
        var cursor = 0
        while (true) {
            val chunk = speechChunk(text, cursor, 40) ?: break
            assertTrue(chunk.end > cursor)
            assertTrue(chunk.text.length <= 40)
            assertFalse(Character.isHighSurrogate(chunk.text.last()))
            chunks.add(chunk); cursor = chunk.end
        }
        assertEquals(text.replace(" ", ""), chunks.joinToString("") { it.text }.replace(" ", ""))
        assertNull(speechChunk(text, text.length))
        assertEquals(0, speechChunk(text, -10)!!.start)
    }
    @Test fun resumeStartsAtSavedCharacterAndBoundsOldOffsets() {
        val text = "Primeira frase. Segunda frase."
        assertEquals("Segunda frase.", speechChunk(text, 15)!!.text)
        assertNull(speechChunk(text, 99999))
        assertNull(speechChunk("   ", 0))
    }
}
