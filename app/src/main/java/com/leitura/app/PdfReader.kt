package com.leitura.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.math.sin

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** Keep only the reader immersive; restore system chrome and sleep policy on exit. */
@Composable
private fun ReadingMode(controls: Boolean) {
    val view = LocalView.current
    val owner = LocalLifecycleOwner.current
    DisposableEffect(view, owner, controls) {
        val window = view.context.activity()?.window
        val previousKeepOn = view.keepScreenOn
        view.keepScreenOn = true
        fun applyMode() {
            window?.let {
                WindowCompat.getInsetsController(it, view).apply {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    if (controls) show(WindowInsetsCompat.Type.systemBars())
                    else hide(WindowInsetsCompat.Type.systemBars())
                }
            }
        }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) applyMode() }
        owner.lifecycle.addObserver(observer)
        applyMode()
        onDispose {
            owner.lifecycle.removeObserver(observer)
            view.keepScreenOn = previousKeepOn
            window?.let { WindowCompat.getInsetsController(it, view).show(WindowInsetsCompat.Type.systemBars()) }
        }
    }
}

@Composable
fun PdfReader(book: Book, store: LibraryStore, onBack: () -> Unit, onFavorite: () -> Unit,
    onProgress: (Int, Int) -> Unit) {
    var count by remember(book.uri, book.fileName) { mutableStateOf<Int?>(null) }
    var page by rememberSaveable(book.uri) { mutableIntStateOf(book.page) }
    var controls by rememberSaveable(book.uri) { mutableStateOf(false) }
    var hint by remember(book.uri) { mutableStateOf(true) }
    var turning by remember(book.uri) { mutableStateOf(false) }
    val currentProgress by rememberUpdatedState(onProgress)
    ReadingMode(controls)
    BackHandler { if (controls) controls = false else onBack() }
    LaunchedEffect(book.uri, book.fileName) {
        count = withContext(Dispatchers.IO) { runCatching { store.count(book) }.getOrDefault(0) }
    }
    LaunchedEffect(book.uri) { delay(4000); hint = false }
    val total = count
    LaunchedEffect(page, total, book.uri) {
        if (total != null && total > 0) {
            val bounded = page.coerceIn(0, total - 1)
            if (page != bounded) page = bounded else currentProgress(page, total)
        }
    }
    fun move(delta: Int): Boolean {
        val size = total ?: return false
        if (turning || size < 1) return false
        val target = (page + delta).coerceIn(0, size - 1)
        if (target == page) return false
        page = target; hint = false
        return true
    }
    val threshold = with(LocalDensity.current) { 48.dp.toPx() }
    Box(Modifier.fillMaxSize().background(Color(0xFF080A0D))) {
        when {
            total == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            total == 0 -> Column(Modifier.align(Alignment.Center).padding(28.dp)) {
                Text("Não foi possível abrir este PDF. Ele pode estar protegido por senha ou indisponível. Importe novamente o arquivo original.")
                TextButton(onClick = onBack) { Text("‹ Voltar") }
            }
            else -> {
                Box(Modifier.fillMaxSize()
                    .semantics {
                        contentDescription = "Leitor, página ${page + 1} de $total"
                        stateDescription = if (turning) "Carregando página" else "Pronto para leitura"
                        customActions = listOf(
                            CustomAccessibilityAction("Próxima página") { move(1) },
                            CustomAccessibilityAction("Página anterior") { move(-1) },
                            CustomAccessibilityAction("Mostrar controles") { controls = true; true })
                    }
                    .pointerInput(book.uri, page, total, turning) {
                        detectTapGestures { position ->
                            when {
                                position.x < size.width * 0.22f -> move(-1)
                                position.x > size.width * 0.78f -> move(1)
                                else -> { controls = !controls; hint = false }
                            }
                        }
                    }
                    .pointerInput(book.uri, page, total, turning) {
                        var drag = 0f
                        detectHorizontalDragGestures(onDragStart = { drag = 0f },
                            onHorizontalDrag = { change, amount -> change.consume(); drag += amount },
                            onDragCancel = { drag = 0f },
                            onDragEnd = {
                                if (drag < -threshold) move(1) else if (drag > threshold) move(-1)
                                drag = 0f
                            })
                    }) {
                    TurningPage(book, page.coerceIn(0, total - 1), store) { turning = it }
                }
                if (hint && !controls) Surface(Modifier.align(Alignment.BottomCenter).padding(20.dp),
                    color = Color(0xDD171B24), shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)) {
                    Text("Deslize para virar • Toque no centro para opções",
                        style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(14.dp))
                }
                if (controls) {
                    Surface(Modifier.align(Alignment.TopCenter).fillMaxWidth(), color = Ink.copy(alpha = 0.96f)) {
                        Row(Modifier.statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = onBack) { Text("‹ Voltar") }
                            Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            TextButton(onClick = onFavorite, modifier = Modifier.semantics {
                                contentDescription = if (book.favorite) "Remover dos favoritos" else "Adicionar aos favoritos"
                            }) { Text(if (book.favorite) "★" else "☆") }
                            TextButton(onClick = { controls = false }) { Text("Ocultar") }
                        }
                    }
                    Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), color = Ink.copy(alpha = 0.96f)) {
                        Column(Modifier.navigationBarsPadding().padding(12.dp)) {
                            if (total > 1) Slider(value = page.toFloat(), enabled = !turning,
                                onValueChange = { page = it.roundToInt().coerceIn(0, total - 1) },
                                valueRange = 0f..(total - 1).toFloat(),
                                modifier = Modifier.semantics { contentDescription = "Ir para página" })
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { move(-1) }, enabled = page > 0 && !turning) { Text("‹ Anterior") }
                                Text("${page + 1} / $total", style = MaterialTheme.typography.labelLarge)
                                TextButton(onClick = { move(1) }, enabled = page < total - 1 && !turning) { Text("Próxima ›") }
                            }
                            NarrationControls(book, page)
                        }
                    }
                }
            }
        }
    }
}

/** Render the incoming sheet before starting. Only two full-size bitmaps are retained.
 * The outgoing sheet pivots on its spine; the reverse gesture folds the incoming sheet back.
 * No mirrored text is displayed and a slow PDF never flashes an empty incoming page.
 */
@Composable
private fun TurningPage(book: Book, page: Int, store: LibraryStore, onTurning: (Boolean) -> Unit) {
    var displayed by remember(book.uri, book.fileName) { mutableStateOf<Bitmap?>(null) }
    var incoming by remember(book.uri, book.fileName) { mutableStateOf<Bitmap?>(null) }
    var displayedPage by remember(book.uri, book.fileName) { mutableIntStateOf(page) }
    var forward by remember { mutableStateOf(true) }
    var failure by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    val turn = remember { Animatable(0f) }
    val reportTurning by rememberUpdatedState(onTurning)
    LaunchedEffect(book.uri, book.fileName, page) {
        reportTurning(true); loading = true; failure = false
        try {
            val bitmap = withContext(Dispatchers.IO) { runCatching { store.render(book, page, 1440) }.getOrNull() }
            if (bitmap == null) { failure = true; return@LaunchedEffect }
            if (displayed == null || page == displayedPage) {
                displayed = bitmap; displayedPage = page
            } else {
                forward = page > displayedPage
                turn.snapTo(0f)
                incoming = bitmap
                loading = false
                turn.animateTo(1f, tween(durationMillis = 560, easing = FastOutSlowInEasing))
                displayed = bitmap; displayedPage = page
                incoming = null
                turn.snapTo(0f)
            }
        } finally { loading = false; reportTurning(false) }
    }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val old = displayed
        val next = incoming
        val ratio = (old ?: next)?.let { it.width.toFloat() / it.height } ?: 0.7f
        val sheetWidth = minOf(maxWidth, maxHeight * ratio)
        val sheetHeight = sheetWidth / ratio
        Box(Modifier.size(sheetWidth, sheetHeight)) {
            val base = if (next != null && forward) next else old
            if (base != null) Image(base.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            if (next != null && old != null) {
                val amount = turn.value
                // Perspective, a moving spine shadow and a paper highlight give the turn depth.
                val shade = (sin(amount * Math.PI).toFloat() * 0.26f).coerceIn(0f, 0.26f)
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
                    listOf(Color.Black.copy(alpha = shade), Color.Transparent))))
                Box(Modifier.fillMaxSize().graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0.5f)
                    rotationY = if (forward) -89.9f * amount else -89.9f * (1f - amount)
                    cameraDistance = 28f * density
                    shadowElevation = 18f * density * sin(amount * Math.PI).toFloat().coerceAtLeast(0f)
                }) {
                    Image((if (forward) old else next).asImageBitmap(), null,
                        Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = shade),
                            Color.White.copy(alpha = shade * 0.4f)))))
                }
            }
        }
        if (old == null && loading) CircularProgressIndicator()
        if (failure) Surface(color = Ink.copy(alpha = 0.94f)) {
            Text("Não foi possível exibir a página. Tente outra página ou reabra o livro.", Modifier.padding(24.dp))
        }
    }
}
