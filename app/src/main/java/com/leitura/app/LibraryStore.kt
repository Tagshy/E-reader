package com.leitura.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.min

data class Book(val uri: String, val title: String, val fileName: String = "", val favorite: Boolean = false,
    val page: Int = 0, val pageCount: Int = 0, val lastRead: Long = 0) {
    val progress: Float get() = if (pageCount > 0 && lastRead > 0) ((page + 1f) / pageCount).coerceIn(0f, 1f) else 0f
}

fun visibleBooks(books: List<Book>, query: String, tab: String): List<Book> {
    val matching = books.filter { it.title.lowercase(Locale.ROOT).contains(query.trim().lowercase(Locale.ROOT)) }
    return when (tab) {
        "Favoritos" -> matching.filter { it.favorite }
        "Recentes" -> matching.filter { it.lastRead > 0 }.sortedByDescending { it.lastRead }
        else -> matching
    }
}

/** Private copies remain readable after the source moves or becomes unavailable. */
class LibraryStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("leitura", Context.MODE_PRIVATE)
    private val directory = File(context.filesDir, "books").apply { mkdirs() }
    private val covers = File(context.cacheDir, "covers").apply { mkdirs() }
    fun load(): List<Book> {
        val json = runCatching { JSONArray(prefs.getString("books", "[]")) }.getOrDefault(JSONArray())
        return (0 until json.length()).mapNotNull { index ->
            val b = json.optJSONObject(index) ?: return@mapNotNull null
            val uri = b.optString("uri")
            if (uri.isBlank()) return@mapNotNull null
            Book(uri, b.optString("title", "Livro"), b.optString("fileName"), b.optBoolean("favorite"),
                b.optInt("page", prefs.getInt("page:$uri", 0)).coerceAtLeast(0),
                b.optInt("pageCount").coerceAtLeast(0), b.optLong("lastRead"))
        }.distinctBy { it.uri }
    }
    fun save(books: List<Book>) {
        val json = JSONArray()
        books.forEach { b -> json.put(JSONObject().put("uri", b.uri).put("title", b.title)
            .put("fileName", b.fileName).put("favorite", b.favorite).put("page", b.page)
            .put("pageCount", b.pageCount).put("lastRead", b.lastRead)) }
        prefs.edit().putString("books", json.toString()).apply()
    }
    private fun localFile(book: Book): File? = book.fileName.takeIf {
        it.matches(Regex("[a-f0-9]{64}\\.pdf"))
    }?.let { File(directory, it) }
    private fun descriptor(book: Book): ParcelFileDescriptor = localFile(book)?.takeIf { it.exists() }?.let {
        ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY)
    } ?: context.contentResolver.openFileDescriptor(Uri.parse(book.uri), "r")
        ?: error("Arquivo indisponível. Importe novamente o PDF.")
    fun count(book: Book): Int = descriptor(book).use { fd -> PdfRenderer(fd).use { it.pageCount } }
    fun import(uri: Uri): Book {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "Livro.pdf"
        val temp = File.createTempFile("import-", ".pdf", directory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val source = context.contentResolver.openInputStream(uri) ?: error("Não foi possível ler o arquivo.")
            source.use { input -> temp.outputStream().use { output ->
                val buffer = ByteArray(65536)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                    output.write(buffer, 0, n)
                }
            } }
            val count = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { it.pageCount.also { size -> require(size > 0) { "PDF sem páginas." } } }
            }
            val fileName = digest.digest().joinToString("") { "%02x".format(it) } + ".pdf"
            val target = File(directory, fileName)
            if (!target.exists()) check(temp.renameTo(target)) { "Não foi possível salvar o PDF no app." }
            return Book(uri.toString(), name.replace(Regex("(?i)\\.pdf$"), ""), fileName, pageCount = count)
        } finally { temp.delete() }
    }
    fun render(book: Book, index: Int, width: Int): Bitmap {
        val cover = File(covers, "${book.fileName.ifBlank { book.uri.hashCode().toString() }}.png")
        if (width <= 320 && index == 0 && cover.exists()) BitmapFactory.decodeFile(cover.path)?.let { return it }
        val image = descriptor(book).use { fd -> PdfRenderer(fd).use { renderer ->
            renderer.openPage(index.coerceIn(0, renderer.pageCount - 1)).use { page ->
                val scale = min(width.coerceIn(100, 1440).toFloat() / page.width, 2400f / page.height)
                Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1),
                    (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888).also {
                    it.eraseColor(android.graphics.Color.WHITE)
                    page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        } }
        if (width <= 320 && index == 0) runCatching {
            cover.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return image
    }
    fun remove(book: Book, remaining: List<Book>) {
        if (remaining.none { it.fileName == book.fileName }) localFile(book)?.delete()
    }
}
