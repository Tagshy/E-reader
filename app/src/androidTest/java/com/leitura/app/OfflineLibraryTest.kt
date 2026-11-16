package com.leitura.app

import android.content.Context
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OfflineLibraryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var store: LibraryStore
    @Before fun setup() {
        context.getSharedPreferences("leitura", 0).edit().clear().commit()
        store = LibraryStore(context)
    }
    private fun pdf(): File {
        val file = File(context.cacheDir, "Livro de teste.pdf")
        PdfDocument().use { document ->
            repeat(3) { index ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(300, 450, index + 1).create())
                page.canvas.drawColor(android.graphics.Color.WHITE)
                page.canvas.drawText("Página ${index + 1}", 30f, 40f, android.graphics.Paint())
                document.finishPage(page)
            }
            file.outputStream().use { document.writeTo(it) }
        }
        return file
    }
    @Test fun copiedPdfSurvivesSourceDeletionAndRestoresProgress() {
        val source = pdf()
        val imported = store.import(Uri.fromFile(source))
        store.save(listOf(imported.copy(page = 1, favorite = true, lastRead = 123)))
        assertTrue(source.delete())
        val restored = LibraryStore(context).load().single()
        assertEquals(1, restored.page)
        assertTrue(restored.favorite)
        assertEquals(123L, restored.lastRead)
        assertEquals(3, store.count(restored))
        assertTrue(store.render(restored, 1, 600).width > 0)
        assertTrue(store.render(restored, 0, 300).height > 0)
    }
    @Test fun legacyUriAndPageArePreserved() {
        val source = pdf()
        val uri = Uri.fromFile(source).toString()
        context.getSharedPreferences("leitura", 0).edit()
            .putString("books", """[{"uri":"$uri","title":"Legado"}]""")
            .putInt("page:$uri", 2).commit()
        val legacy = store.load().single()
        assertEquals(2, legacy.page)
        val copy = store.import(Uri.parse(uri))
        store.save(listOf(legacy.copy(fileName = copy.fileName, pageCount = copy.pageCount)))
        source.delete()
        assertEquals(2, store.load().single().page)
        assertEquals(3, store.count(store.load().single()))
    }
    @Test fun corruptPdfDoesNotEnterLibrary() {
        val file = File(context.cacheDir, "broken.pdf").apply { writeText("not a PDF") }
        assertTrue(runCatching { store.import(Uri.fromFile(file)) }.isFailure)
        assertTrue(store.load().isEmpty())
    }
}
