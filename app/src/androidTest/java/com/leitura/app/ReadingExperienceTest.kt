package com.leitura.app

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReadingExperienceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun immersiveReaderTurnsBothWaysAndPersistsProgress() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("leitura", 0).edit().clear().commit()
        val original = File(context.cacheDir, "reading-experience.pdf")
        val pdf = PdfDocument()
        try {
            repeat(3) { index ->
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(400, 600, index + 1).create())
                page.canvas.drawColor(android.graphics.Color.WHITE)
                page.canvas.drawText("Pagina ${index + 1}", 40f, 100f, Paint().apply { textSize = 28f })
                pdf.finishPage(page)
            }
            original.outputStream().use { pdf.writeTo(it) }
        } finally { pdf.close() }
        val store = LibraryStore(context)
        val book = store.import(Uri.fromFile(original)).copy(title = "Livro de teste")
        store.save(listOf(book))
        original.delete() // All reading below must use the app's offline copy.
        fun waitForPage(number: Int) {
            compose.waitUntil(20000) {
                compose.onAllNodes(
                    hasContentDescription("Leitor, página $number de 3") and
                        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Pronto para leitura")
                ).fetchSemanticsNodes().isNotEmpty()
            }
            compose.waitForIdle()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(3000)
            compose.waitForIdle()
            compose.onNodeWithText("Livro de teste").performClick()
            waitForPage(1)
            println("READER_READY_PAGE_1")
            compose.onNodeWithText("‹ Voltar").assertDoesNotExist()
            scenario.onActivity { activity ->
                fun keepsScreenOn(view: android.view.View): Boolean = view.keepScreenOn ||
                    (view is android.view.ViewGroup && (0 until view.childCount).any { keepsScreenOn(view.getChildAt(it)) })
                assertTrue(keepsScreenOn(activity.window.decorView))
                val insets = ViewCompat.getRootWindowInsets(activity.window.decorView)
                if (insets != null) assertFalse(insets.isVisible(WindowInsetsCompat.Type.statusBars()))
            }
            compose.onNodeWithContentDescription("Leitor, página 1 de 3").performTouchInput { swipeLeft() }
            waitForPage(2)
            assertEquals(1, store.load().single().page)
            println("READER_READY_PAGE_2")
            // The reader's controls are opt-in; reveal them at the center.
            compose.onNodeWithContentDescription("Leitor, página 2 de 3").performTouchInput { click(center) }
            compose.onNodeWithText("2 / 3").assertExists()
            compose.onNodeWithText("Próxima ›").performClick()
            waitForPage(3)
            assertEquals(2, store.load().single().page)
            println("READER_READY_PAGE_3")
            compose.onNodeWithText("Próxima ›").assertIsNotEnabled()
            compose.onNodeWithText("‹ Anterior").performClick()
            waitForPage(2)
            assertEquals(1, store.load().single().page)
            println("READER_READY_PAGE_2")
            compose.onNodeWithText("‹ Voltar").performClick()
            compose.onNodeWithText("Olá, Walter").assertExists()
            compose.onNodeWithText("Livro de teste").performClick()
            waitForPage(2)
            compose.onNodeWithText("‹ Voltar").assertDoesNotExist()
            scenario.recreate()
            compose.waitForIdle()
            assertEquals(1, store.load().single().page)
        }
    }
}
