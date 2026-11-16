package com.leitura.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

private data class Book(val uri: String, val title: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LeituraApp() }
    }
}

@Composable
private fun LeituraApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("leitura", 0) }
    val books = remember { mutableStateListOf<Book>().apply {
        val json = runCatching { JSONArray(prefs.getString("books", "[]")) }.getOrDefault(JSONArray())
        for (i in 0 until json.length()) {
            val item = json.optJSONObject(i) ?: continue
            add(Book(item.optString("uri"), item.optString("title")))
        }
    } }
    var selected by remember { mutableStateOf<Book?>(null) }
    var settings by remember { mutableStateOf(false) }
    var pageMode by remember { mutableStateOf(prefs.getString("pageMode", "Deslizar") ?: "Deslizar") }
    var darkMode by remember { mutableStateOf(prefs.getBoolean("dark", false)) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { uri ->
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            if (books.none { it.uri == uri.toString() }) {
                val title = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast(".pdf") ?: "Livro"
                books.add(Book(uri.toString(), title))
            }
        }
        val json = JSONArray()
        books.forEach { json.put(JSONObject().put("uri", it.uri).put("title", it.title)) }
        prefs.edit().putString("books", json.toString()).apply()
    }
    MaterialTheme(colorScheme = if (darkMode) darkColorScheme() else lightColorScheme()) {
        Scaffold(topBar = {
            Surface(tonalElevation = 2.dp) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (selected != null) "Leitura" else if (settings) "Configurações" else "Minha biblioteca", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    if (selected != null || settings) TextButton(onClick = { selected = null; settings = false }) { Text("Voltar") }
                    else TextButton(onClick = { settings = true }) { Text("Ajustes") }
                }
            }
        }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when {
                    settings -> Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        Text("Navegação de páginas", style = MaterialTheme.typography.titleMedium)
                        listOf("Deslizar", "Folhear (experimental)").forEach { option ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable {
                                pageMode = option; prefs.edit().putString("pageMode", option).apply()
                            }) {
                                RadioButton(selected = pageMode == option, onClick = { pageMode = option; prefs.edit().putString("pageMode", option).apply() })
                                Text(option)
                            }
                        }
                        Text("O efeito visual de folha ainda não está implementado. Ambas as opções usam gestos laterais nesta versão.", style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Modo escuro", modifier = Modifier.weight(1f))
                            Switch(checked = darkMode, onCheckedChange = { darkMode = it; prefs.edit().putBoolean("dark", it).apply() })
                        }
                    }
                    selected != null -> PdfReader(selected!!, prefs)
                    else -> Column(Modifier.fillMaxSize().padding(16.dp)) {
                        Button(onClick = { picker.launch(arrayOf("application/pdf")) }, modifier = Modifier.fillMaxWidth()) { Text("+ Importar livros em PDF") }
                        Spacer(Modifier.height(16.dp))
                        if (books.isEmpty()) Text("Sua biblioteca está vazia. Importe um PDF para começar.")
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(books, key = { it.uri }) { book ->
                                Card(onClick = { selected = book }, modifier = Modifier.fillMaxWidth()) {
                                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        PdfPage(book.uri, 0, Modifier.size(width = 72.dp, height = 100.dp))
                                        Spacer(Modifier.width(14.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                                            Text("Toque para ler", style = MaterialTheme.typography.bodySmall)
                                        }
                                        TextButton(onClick = {
                                            books.remove(book)
                                            val json = JSONArray()
                                            books.forEach { json.put(JSONObject().put("uri", it.uri).put("title", it.title)) }
                                            prefs.edit().putString("books", json.toString()).apply()
                                        }) { Text("Remover") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfReader(book: Book, prefs: android.content.SharedPreferences) {
    val context = LocalContext.current
    val count = remember(book.uri) { runCatching {
        context.contentResolver.openFileDescriptor(Uri.parse(book.uri), "r")?.use { fd -> PdfRenderer(fd).use { it.pageCount } } ?: 0
    }.getOrDefault(0) }
    var page by remember(book.uri) { mutableIntStateOf(prefs.getInt("page:${book.uri}", 0).coerceIn(0, (count - 1).coerceAtLeast(0))) }
    var drag by remember { mutableFloatStateOf(0f) }
    fun navigate(delta: Int) {
        page = (page + delta).coerceIn(0, (count - 1).coerceAtLeast(0))
        prefs.edit().putInt("page:${book.uri}", page).apply()
    }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (count == 0) Text("Não foi possível abrir este PDF.", Modifier.padding(20.dp))
        else {
            Box(Modifier.weight(1f).fillMaxWidth().pointerInput(book.uri, page) {
                detectHorizontalDragGestures(onDragStart = { drag = 0f }, onHorizontalDrag = { change, amount -> change.consume(); drag += amount }, onDragEnd = {
                    if (drag < -70f) navigate(1) else if (drag > 70f) navigate(-1)
                    drag = 0f
                })
            }, contentAlignment = Alignment.Center) {
                PdfPage(book.uri, page, Modifier.fillMaxSize().padding(8.dp))
            }
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { navigate(-1) }, enabled = page > 0) { Text("Anterior") }
                Text("${page + 1} / $count")
                TextButton(onClick = { navigate(1) }, enabled = page < count - 1) { Text("Próxima") }
            }
        }
    }
}

@Composable
private fun PdfPage(uri: String, index: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap = remember(uri, index) {
        runCatching {
            context.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { fd ->
                PdfRenderer(fd).use { renderer ->
                    renderer.openPage(index.coerceIn(0, renderer.pageCount - 1)).use { page ->
                        val scale = (1200f / page.width).coerceAtMost(2f)
                        val image = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                        image.eraseColor(android.graphics.Color.WHITE)
                        page.render(image, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        image
                    }
                }
            }
        }.getOrNull()
    }
    if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = "Página ${index + 1}", modifier = modifier, contentScale = ContentScale.Fit)
    else Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) { Text("PDF") }
}
