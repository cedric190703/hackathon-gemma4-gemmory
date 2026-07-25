package com.gemmory.ui.theme

import android.app.Activity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import kotlin.math.max

val GemmaBlue = Color(0xFF4285F4)
val GemmaSky = Color(0xFF8AB4F8)
val GemmaPeriwinkle = Color(0xFFAECBFA)
val GemmaInk = Color(0xFF020713)
val GemmaNavy = Color(0xFF071120)
val GemmaPanel = Color(0xFF0B1628)
val GemmaGrid = Color(0xFF5C8DFF)

private val LightColors = lightColorScheme(
    primary = GemmaBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E7FF),
    onPrimaryContainer = Color(0xFF062B62),
    secondary = Color(0xFF345F91),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD5E5FF),
    onSecondaryContainer = Color(0xFF061D36),
    tertiary = Color(0xFF5F5F9F),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE2DFFF),
    onTertiaryContainer = Color(0xFF191A4D),
    background = Color(0xFFF7FAFF),
    onBackground = Color(0xFF121821),
    surface = Color(0xFFF7FAFF),
    onSurface = Color(0xFF121821),
    surfaceVariant = Color(0xFFE2E8F3),
    onSurfaceVariant = Color(0xFF405064),
    outline = Color(0xFF718094),
    outlineVariant = Color(0xFFC0CAD8),
)

private val DarkColors = darkColorScheme(
    primary = GemmaBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF123A70),
    onPrimaryContainer = Color(0xFFD8E7FF),
    secondary = GemmaSky,
    onSecondary = GemmaInk,
    secondaryContainer = Color(0xFF173554),
    onSecondaryContainer = Color(0xFFD8E7FF),
    tertiary = GemmaPeriwinkle,
    onTertiary = Color(0xFF151743),
    tertiaryContainer = Color(0xFF2C315F),
    onTertiaryContainer = Color(0xFFE6E4FF),
    background = GemmaInk,
    onBackground = Color(0xFFE8F0FE),
    surface = GemmaNavy,
    onSurface = Color(0xFFE8F0FE),
    surfaceVariant = GemmaPanel,
    onSurfaceVariant = Color(0xFFC7D5EA),
    inverseSurface = Color(0xFFE8F0FE),
    inverseOnSurface = GemmaInk,
    inversePrimary = Color(0xFF0B57D0),
    outline = Color(0xFF5E7290),
    outlineVariant = Color(0xFF1E3552),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF4F1112),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun GemmoryTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkColors
        else -> LightColors
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.navigationBarColor = GemmaInk.toArgb()
            window.statusBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightNavigationBars = false
                isAppearanceLightStatusBars = false
            }
        }
    }

    MaterialTheme(colorScheme = colorScheme, content = content)
}

@Composable
fun GemmaBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colorScheme.background)
            .gemmaGrid(),
    ) {
        content()
    }
}

private fun Modifier.gemmaGrid(): Modifier = this.then(
    Modifier.drawGemmaBackground(),
)

private fun Modifier.drawGemmaBackground(): Modifier = drawBehind {
    val maxDimension = max(size.width, size.height)
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(
                GemmaBlue.copy(alpha = 0.20f),
                GemmaSky.copy(alpha = 0.07f),
                Color.Transparent,
            ),
            center = Offset(size.width * 0.34f, size.height * 0.08f),
            radius = maxDimension * 0.82f,
        ),
    )
    drawRect(
        brush = Brush.linearGradient(
            colors = listOf(
                Color.Transparent,
                GemmaInk.copy(alpha = 0.20f),
                Color.Black.copy(alpha = 0.42f),
            ),
            start = Offset(0f, 0f),
            end = Offset(size.width, size.height),
        ),
    )

    val spacing = 18.dp.toPx()
    val radius = 1.dp.toPx()
    var y = spacing
    while (y < size.height) {
        var x = spacing
        while (x < size.width) {
            drawCircle(
                color = GemmaGrid.copy(alpha = 0.18f),
                radius = radius,
                center = Offset(x, y),
            )
            x += spacing
        }
        y += spacing
    }
}

@Composable
fun GemmaMark(
    modifier: Modifier = Modifier,
    color: Color = GemmaSky,
) {
    Canvas(modifier) {
        val width = size.width
        val height = size.height
        val minDimension = minOf(width, height)
        val center = Offset(width / 2f, height / 2f)
        val guideStroke = Stroke(width = minDimension * 0.018f)
        val markStroke = Stroke(
            width = minDimension * 0.06f,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )

        drawCircle(
            color = color.copy(alpha = 0.46f),
            radius = minDimension * 0.28f,
            center = center,
            style = guideStroke,
        )
        drawLine(
            color = color.copy(alpha = 0.42f),
            start = Offset(center.x, height * 0.12f),
            end = Offset(center.x, height * 0.88f),
            strokeWidth = minDimension * 0.018f,
        )
        drawLine(
            color = color.copy(alpha = 0.42f),
            start = Offset(width * 0.12f, center.y),
            end = Offset(width * 0.88f, center.y),
            strokeWidth = minDimension * 0.018f,
        )

        val sparkle = Path().apply {
            moveTo(center.x, height * 0.18f)
            cubicTo(width * 0.46f, height * 0.36f, width * 0.36f, height * 0.46f, width * 0.18f, center.y)
            cubicTo(width * 0.36f, height * 0.54f, width * 0.46f, height * 0.64f, center.x, height * 0.82f)
            cubicTo(width * 0.54f, height * 0.64f, width * 0.64f, height * 0.54f, width * 0.82f, center.y)
            cubicTo(width * 0.64f, height * 0.46f, width * 0.54f, height * 0.36f, center.x, height * 0.18f)
        }
        drawPath(sparkle, color = GemmaBlue, style = markStroke)
        drawPath(sparkle, color = GemmaPeriwinkle.copy(alpha = 0.68f), style = guideStroke)
    }
}
