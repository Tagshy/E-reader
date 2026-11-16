
package com.leitura.app

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
    if (selected != null) {
        PdfReader(selected, store, onBack = { selectedUri = null },
            onFavorite = { update(selected.copy(favorite = !selected.favorite)) },
            onProgress = { page, count ->
                books.find { it.uri == selected.uri }?.let {
                    update(it.copy(page = page, pageCount = count, lastRead = System.currentTimeMillis()))
                }
            })
        return
    }
    Scaffold(containerColor = Ink, snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                listOf("Biblioteca" to "▤", "Favoritos" to "★", "Recentes" to "◷", "Atualizações" to "↓").forEach { (name, symbol) ->
                    NavigationBarItem(selected = tab == name, onClick = { tab = name },
                        icon = { Text(symbol, fontSize = 22.sp) },
                        label = { Text(name, fontSize = 10.sp, maxLines = 1) })
                }
            }
        }) { padding ->
        if (tab == "Atualizações") UpdatesScreen(Modifier.padding(padding))
        else Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 22.dp)) {
            Spacer(Modifier.height(28.dp))
            Text("Olá, Walter", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
            Text("Um bom momento para uma nova história.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp, bottom = 24.dp))
            Surface(onClick = { picker.launch(arrayOf("application/pdf")) }, enabled = !importing,
                shape = RoundedCornerShape(22.dp), color = Color.Transparent,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Adicionar livro em PDF" }) {
                Row(Modifier.background(Brush.horizontalGradient(listOf(Color(0xFF7258DE), Color(0xFF346CC0))))
                    .padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("+", color = Color.White, fontSize = 32.sp, modifier = Modifier.padding(end = 18.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Adicionar livro", color = Color.White, style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                        Text("Escolha seus PDFs", color = Color(0xFFE8E5FF), style = MaterialTheme.typography.bodySmall)
                    }
                    Text("↗", color = Color.White, fontSize = 24.sp)
                }
            }
            if (importing) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                Text("Salvando PDFs para ler offline…", style = MaterialTheme.typography.bodySmall)
            }
            val visible = visibleBooks(books, "", tab)
            Row(Modifier.fillMaxWidth().padding(top = 26.dp, bottom = 18.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (tab == "Biblioteca") "Seus livros" else tab, style = MaterialTheme.typography.titleMedium)
                Text("${visible.size} livro(s)", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium)
            }
            if (visible.isEmpty()) Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("✦", color = Gold, fontSize = 42.sp, modifier = Modifier.padding(bottom = 18.dp))
                    Text(when (tab) {
                        "Favoritos" -> "Suas histórias favoritas, aqui"
                        "Recentes" -> "Continue de onde parou"
                        else -> "A próxima história é sua"
                    }, style = MaterialTheme.typography.titleMedium)
                    Text(when (tab) {
                        "Favoritos" -> "Toque na estrela de um livro para favoritar."
                        "Recentes" -> "Os livros que você abrir aparecerão aqui."
                        else -> "Adicione um PDF e leve sua leitura com você."
                    }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp))
                }
            } else LazyVerticalGrid(columns = GridCells.Adaptive(145.dp), contentPadding = PaddingValues(bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                items(visible, key = { it.uri }) { book -> BookCard(book, store,
                    onOpen = { selectedUri = book.uri }, onFavorite = { update(book.copy(favorite = !book.favorite)) },
                    onRemove = { removal = book }) }
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
