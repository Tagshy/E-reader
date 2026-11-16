
package com.leitura.app

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(11, 13, 18)
        window.navigationBarColor = android.graphics.Color.rgb(11, 13, 18)
        setContent { MaterialTheme(colorScheme = LeituraColors) { LeituraApp() } }
    }
}

@Composable
private fun LeituraApp() {
    val context = LocalContext.current
    val store = remember { LibraryStore(context.applicationContext) }
    val books = remember { mutableStateListOf<Book>().apply { addAll(store.load()) } }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var opening by rememberSaveable { mutableStateOf(true) }
    var selectedUri by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var tab by rememberSaveable { mutableStateOf("Biblioteca") }
    var importing by remember { mutableStateOf(false) }
    var removal by remember { mutableStateOf<Book?>(null) }
    fun update(book: Book) {
        val index = books.indexOfFirst { it.uri == book.uri }
        if (index >= 0) { books[index] = book; store.save(books) }
    }
    // Missing legacy sources retain their metadata and progress, allowing reimport.
    LaunchedEffect(Unit) {
        books.toList().filter { it.fileName.isBlank() }.forEach { legacy ->
            val copy = withContext(Dispatchers.IO) { runCatching { store.import(android.net.Uri.parse(legacy.uri)) }.getOrNull() }
            val current = books.find { it.uri == legacy.uri }
            if (copy != null && current != null) update(current.copy(fileName = copy.fileName, pageCount = copy.pageCount))
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            importing = true
            var failed = 0
            var added = 0
            for (uri in uris) {
                runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                val result = withContext(Dispatchers.IO) { runCatching { store.import(uri) } }
                result.onSuccess { book ->
                    val existing = books.find { it.uri == book.uri || it.fileName == book.fileName }
                    if (existing == null) { books.add(book); added++ }
                    else update(existing.copy(fileName = book.fileName, pageCount = book.pageCount))
                }.onFailure { failed++ }
            }
            store.save(books)
            importing = false
            snackbar.showSnackbar(if (failed > 0) "$added importado(s). $failed arquivo(s) não puderam ser abertos; verifique se são PDFs sem senha."
                else if (added > 0) "$added livro(s) disponível(is) offline." else "Os livros selecionados já estão na biblioteca.")
        }
    }
    BackHandler(selectedUri != null) { selectedUri = null }
    if (opening) { Opening { opening = false }; return }
    val selected = books.find { it.uri == selectedUri }
    Scaffold(containerColor = Ink, snackbarHost = { SnackbarHost(snackbar) }, topBar = {
        Surface(color = Ink) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (selected != null) {
                    TextButton(onClick = { selectedUri = null }) { Text("‹ Voltar") }
                    Text(selected.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    TextButton(onClick = { update(selected.copy(favorite = !selected.favorite)) }) {
                        Text(if (selected.favorite) "★" else "☆", fontSize = 26.sp,
                            modifier = Modifier.semantics { contentDescription = if (selected.favorite) "Remover dos favoritos" else "Adicionar aos favoritos" })
                    }
                } else {
                    BrandMark(Modifier.size(64.dp))
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text("Leitura", fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = Gold)
                        Text("UM UNIVERSO EM CADA PÁGINA", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("2.0", color = Gold, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }, bottomBar = {
        if (selected == null) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
            listOf("Biblioteca" to "▤", "Favoritos" to "★", "Recentes" to "◷").forEach { (name, symbol) ->
                NavigationBarItem(selected = tab == name, onClick = { tab = name },
                    icon = { Text(symbol, fontSize = 23.sp) }, label = { Text(name) })
            }
        }
    }) { padding ->
        if (selected != null) PdfReader(selected, store, Modifier.padding(padding), onProgress = { page, count ->
            books.find { it.uri == selected.uri }?.let { update(it.copy(page = page, pageCount = count, lastRead = System.currentTimeMillis())) }
        }) else Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Sua estante", style = MaterialTheme.typography.headlineMedium)
                    Text("${books.size} livro(s) · leitura offline", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                FilledTonalButton(enabled = !importing, onClick = { picker.launch(arrayOf("application/pdf")) }) { Text("+ PDF") }
            }
            OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                label = { Text("Buscar na biblioteca") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("Limpar") } })
            if (importing) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                Text("Salvando PDFs para ler offline…", style = MaterialTheme.typography.bodySmall)
            }
            val visible = visibleBooks(books, query, tab)
            Text("$tab · ${visible.size}", color = Gold, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(vertical = 18.dp))
            if (visible.isEmpty()) Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    BrandMark(Modifier.size(144.dp))
                    Text(when { query.isNotBlank() -> "Nenhum livro encontrado"; tab == "Favoritos" -> "Suas histórias favoritas, aqui"; tab == "Recentes" -> "Continue de onde parou"; else -> "A próxima história é sua" }, style = MaterialTheme.typography.titleMedium)
                    Text(when (tab) { "Favoritos" -> "Toque na estrela de um livro para favoritar."; "Recentes" -> "Os livros que você abrir aparecerão aqui."; else -> "Importe seus livros em PDF para começar." },
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
                }
            } else LazyVerticalGrid(columns = GridCells.Adaptive(145.dp), contentPadding = PaddingValues(bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                items(visible, key = { it.uri }) { book -> BookCard(book, store,
                    onOpen = { selectedUri = book.uri }, onFavorite = { update(book.copy(favorite = !book.favorite)) }, onRemove = { removal = book }) }
            }
        }
    }
    removal?.let { book -> AlertDialog(onDismissRequest = { removal = null },
        title = { Text("Remover da biblioteca?") }, text = { Text("${book.title}\n\nO arquivo original será preservado. A cópia do app e seu progresso serão removidos.") },
        confirmButton = { TextButton(onClick = {
            books.removeAll { it.uri == book.uri }; store.save(books)
            val remaining = books.toList()
            scope.launch(Dispatchers.IO) { store.remove(book, remaining) }
            removal = null
        }) { Text("Remover") } }, dismissButton = { TextButton(onClick = { removal = null }) { Text("Cancelar") } }) }
}

@Composable
private fun BookCard(book: Book, store: LibraryStore, onOpen: () -> Unit, onFavorite: () -> Unit, onRemove: () -> Unit) {
    Card(onClick = onOpen, shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Box {
            PdfPage(book, 0, store, 300, Modifier.fillMaxWidth().aspectRatio(0.69f).background(MaterialTheme.colorScheme.surfaceVariant))
            FilledTonalIconButton(onClick = onFavorite, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                Text(if (book.favorite) "★" else "☆", color = Gold, fontSize = 24.sp,
                    modifier = Modifier.semantics { contentDescription = if (book.favorite) "Remover ${book.title} dos favoritos" else "Favoritar ${book.title}" })
            }
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(progress = { book.progress }, modifier = Modifier.fillMaxWidth().height(3.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (book.lastRead > 0) "${(book.progress * 100).roundToInt()}% lido" else "Não iniciado",
                    modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = onRemove, contentPadding = PaddingValues(0.dp)) { Text("Remover", fontSize = 10.sp) }
            }
        }
    }
}

@Composable
private fun PdfReader(book: Book, store: LibraryStore, modifier: Modifier, onProgress: (Int, Int) -> Unit) {
    val count by produceState<Int?>(null, book.uri, book.fileName) {
        value = withContext(Dispatchers.IO) { runCatching { store.count(book) }.getOrDefault(0) }
    }
    var page by rememberSaveable(book.uri) { mutableIntStateOf(book.page) }
    val currentProgress by rememberUpdatedState(onProgress)
    val total = count
    LaunchedEffect(book.uri, page, total) {
        if (total != null && total > 0) {
            val bounded = page.coerceIn(0, total - 1)
            if (bounded != page) page = bounded else currentProgress(page, total)
        }
    }
    val threshold = with(LocalDensity.current) { 48.dp.toPx() }
    Column(modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        when {
            total == null -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            total == 0 -> Box(Modifier.weight(1f).padding(24.dp), contentAlignment = Alignment.Center) {
                Text("Não foi possível abrir este PDF. Ele pode estar protegido por senha ou indisponível. Importe novamente o arquivo original.")
            }
            else -> {
                Box(Modifier.weight(1f).fillMaxWidth().pointerInput(book.uri, page, total) {
                    var drag = 0f
                    detectHorizontalDragGestures(onDragStart = { drag = 0f }, onHorizontalDrag = { change, amount -> change.consume(); drag += amount },
                        onDragCancel = { drag = 0f }, onDragEnd = {
                            if (drag < -threshold) page = (page + 1).coerceAtMost(total - 1)
                            else if (drag > threshold) page = (page - 1).coerceAtLeast(0)
                            drag = 0f
                        })
                }, contentAlignment = Alignment.Center) {
                    Crossfade(targetState = page.coerceIn(0, total - 1), label = "Virar página") { index ->
                        PdfPage(book, index, store, 1440, Modifier.fillMaxSize().padding(8.dp))
                    }
                }
                if (total > 1) Slider(value = page.toFloat(), onValueChange = { page = it.roundToInt().coerceIn(0, total - 1) },
                    valueRange = 0f..(total - 1).toFloat(), modifier = Modifier.padding(horizontal = 24.dp).semantics { contentDescription = "Ir para página" })
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { page-- }, enabled = page > 0) { Text("‹ Anterior") }
                    Text("${page + 1} / $total", style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = { page++ }, enabled = page < total - 1) { Text("Próxima ›") }
                }
            }
        }
    }
}

@Composable
private fun PdfPage(book: Book, index: Int, store: LibraryStore, width: Int, modifier: Modifier) {
    var error by remember(book.uri, index, book.fileName) { mutableStateOf(false) }
    val bitmap by produceState<Bitmap?>(null, book.uri, book.fileName, index, width) {
        value = withContext(Dispatchers.IO) { runCatching { store.render(book, index, width) }.getOrNull() }
        error = value == null
    }
    val image = bitmap
    if (image != null) Image(image.asImageBitmap(), "${book.title}, página ${index + 1}", modifier, contentScale = ContentScale.Fit)
    else Box(modifier, contentAlignment = Alignment.Center) {
        if (error) Text(if (width <= 320) "PDF" else "Não foi possível exibir a página.") else CircularProgressIndicator(Modifier.size(24.dp))
    }
}
