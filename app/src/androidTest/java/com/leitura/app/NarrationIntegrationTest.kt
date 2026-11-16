package com.leitura.app

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NarrationIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun nativeBook(): Book {
        PDFBoxResourceLoader.init(context)
        val file = File(context.cacheDir, "narration-test.pdf")
        PDDocument().use { pdf ->
            repeat(3) { index ->
                val page = PDPage(); pdf.addPage(page)
                PDPageContentStream(pdf, page).use { content ->
                    content.beginText()
                    content.setFont(PDType1Font.HELVETICA, 18f)
                    content.newLineAtOffset(50f, 700f)
                    content.showText("Pagina " + (index + 1) + ". Uma historia para ouvir. Fim desta pagina.")
                    content.endText()
                }
            }
            pdf.save(file)
        }
        val book = LibraryStore(context).import(Uri.fromFile(file)).copy(title = "Livro de áudio")
        file.delete()
        return book
    }

    private fun awaitState(description: String, predicate: (NarrationState) -> Boolean) = runBlocking {
        withTimeout(30000) {
            while (!predicate(NarrationController.state.value)) {
                val state = NarrationController.state.value
                check(state.status != NarrationStatus.ERROR) { "$description: " + state.message }
                delay(50)
            }
        }
    }

    @Test fun nativeTextAndPositionsRemainLocalAndIndependent() = runBlocking {
        val book = nativeBook()
        val source = NarrationTextSource(context)
        try {
            val text = source.page(book, 1)
            assertTrue(text.contains("Pagina 2"))
            val preferences = NarrationPreferences(context)
            preferences.save(book.uri, NarrationPosition(1, 12))
            assertEquals(NarrationPosition(1, 12), preferences.position(book.uri))
            val other = book.uri + "/other"
            preferences.save(other, NarrationPosition(0, 5))
            assertEquals(1, preferences.position(book.uri)!!.page)
            assertEquals(0, preferences.position(other)!!.page)
            assertEquals(0, book.page)
        } finally { source.close() }
    }

    @Test fun scannedPageUsesBundledOfflineRecognition() = runBlocking {
        val file = File(context.cacheDir, "scanned-narration.pdf")
        val pdf = PdfDocument()
        val bitmap = Bitmap.createBitmap(1200, 1600, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawText("UMA HISTORIA PARA OUVIR", 70f, 220f, Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 62f; color = Color.BLACK })
        }
        try {
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(1200, 1600, 1).create())
            page.canvas.drawBitmap(bitmap, 0f, 0f, null)
            pdf.finishPage(page)
            file.outputStream().use { pdf.writeTo(it) }
        } finally { pdf.close(); bitmap.recycle() }
        val book = LibraryStore(context).import(Uri.fromFile(file))
        file.delete()
        val source = NarrationTextSource(context)
        try {
            val text = source.page(book, 0).uppercase()
            assertTrue("OCR returned: $text", text.contains("HISTORIA") && text.contains("OUVIR"))
        } finally { source.close() }
    }

    @Test fun backgroundSpeechPausesResumesAdvancesAndPublishesMediaControls() {
        context.getSharedPreferences("narration", 0).edit().clear().commit()
        val book = nativeBook()
        LibraryStore(context).save(listOf(book))
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            try {
                ContextCompat.startForegroundService(context, Intent(context, NarrationService::class.java)
                    .setAction(NarrationService.START).putExtra("uri", book.uri).putExtra("page", 0)
                    .putExtra("test_engine", context.packageName + ".test"))
                awaitState("start") { it.status == NarrationStatus.PLAYING }
                assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications
                    .any { it.id == NarrationService.NOTIFICATION })
                activity.moveToState(Lifecycle.State.CREATED) // Playback must survive without a visible reader.
                awaitState("next page in background") { it.page >= 1 && it.status == NarrationStatus.PLAYING }
                context.startService(Intent(context, NarrationService::class.java).setAction(NarrationService.PAUSE))
                awaitState("pause") { it.status == NarrationStatus.PAUSED }
                val saved = NarrationPreferences(context).position(book.uri)!!
                assertTrue(saved.page >= 1)
                activity.moveToState(Lifecycle.State.RESUMED)
                ContextCompat.startForegroundService(context, Intent(context, NarrationService::class.java).setAction(NarrationService.RESUME))
                awaitState("resume") { it.status == NarrationStatus.PLAYING && it.page == saved.page }
                awaitState("finish") { it.status == NarrationStatus.COMPLETED }
                assertTrue(NarrationPreferences(context).position(book.uri)!!.completed)
            } finally {
                context.startService(Intent(context, NarrationService::class.java).setAction(NarrationService.STOP))
                awaitState("stop") { it.status == NarrationStatus.IDLE }
            }
        }
    }
}
