package com.leitura.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Movie
import android.os.SystemClock
import android.view.View
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

val Gold = Color(0xFFE4B45D)
val Ink = Color(0xFF0B0D12)
val LeituraColors = darkColorScheme(primary = Gold, onPrimary = Color(0xFF281B05), background = Ink,
    surface = Color(0xFF12161E), surfaceVariant = Color(0xFF202630), onSurface = Color(0xFFF3EDE2),
    onSurfaceVariant = Color(0xFFB9BBC4), secondary = Gold, outline = Color(0xFF555963))

@Composable
fun BrandMark(modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.leitura_logo), "Emblema dourado Leitura", modifier)
}

@Composable
fun Opening(onFinished: () -> Unit) {
    LaunchedEffect(Unit) { delay(2400); onFinished() }
    Column(Modifier.fillMaxSize().background(Color(0xFF080706)).systemBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        AndroidView(factory = { AnimatedEmblem(it) }, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
        Text("SUA PRÓXIMA HISTÓRIA COMEÇA AQUI", style = MaterialTheme.typography.labelSmall, color = Gold)
        Spacer(Modifier.height(28.dp))
        TextButton(onClick = onFinished) { Text("Entrar na biblioteca") }
    }
}

@Suppress("DEPRECATION")
private class AnimatedEmblem(context: Context) : View(context) {
    private val movie = resources.openRawResource(R.raw.leitura_opening).use { Movie.decodeStream(it) }
    private val start = SystemClock.uptimeMillis()
    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val animation = movie ?: return
        animation.setTime(((SystemClock.uptimeMillis() - start) % animation.duration().coerceAtLeast(1)).toInt())
        val scale = minOf(width.toFloat() / animation.width(), height.toFloat() / animation.height())
        canvas.save()
        canvas.translate((width - animation.width() * scale) / 2, (height - animation.height() * scale) / 2)
        canvas.scale(scale, scale)
        animation.draw(canvas, 0f, 0f)
        canvas.restore()
        if (isAttachedToWindow) postInvalidateOnAnimation()
    }
}
