package com.antiscam.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.antiscam.app.capture.CaptureStore
import com.antiscam.app.capture.ScamIntervention

/** A translucent foreground activity allows voice to use the microphone while the warning is visible. */
class ScamWarningActivity : ComponentActivity() {
    private var closing by mutableStateOf(false)
    private var voiceLevel by mutableFloatStateOf(0f)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.RECORDING_DEMO) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        setFinishOnTouchOutside(false)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            val incident by ScamIntervention.incident.collectAsStateWithLifecycle()
            val captureState by CaptureStore.state.collectAsStateWithLifecycle()
            LaunchedEffect(incident) {
                if (incident == null) finish()
            }
            BackHandler { closeWarning() }
            MaterialTheme(colorScheme = scamCatchColors) {
                WarningScreen(
                    status = if (closing) "Closing voice…" else captureState.voiceStatus,
                    level = voiceLevel,
                    closing = closing,
                    onClose = ::closeWarning,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!closing && ScamIntervention.incident.value != null) {
            // Reattach UI callbacks after resume. The coordinator permits one connection attempt per incident.
            ScamIntervention.attach(
                this,
                onStatus = {},
                onLevel = { level -> runOnUiThread { voiceLevel = level.coerceIn(0f, 1f) } },
            )
        }
    }

    override fun onStop() {
        ScamIntervention.suspendVoice()
        voiceLevel = 0f
        super.onStop()
    }

    private fun closeWarning() {
        if (closing) return
        closing = true
        voiceLevel = 0f
        ScamIntervention.dismiss { runOnUiThread { finish() } }
    }
}

// Colors and orb geometry follow website/scamcatch/public/styles.css.
private val scamCatchColors = darkColorScheme(
    primary = Color(0xFF7AB0FF),
    secondary = Color(0xFFF4C27A),
    background = Color(0xFF0C1124),
    surface = Color(0xFF10162B),
    onSurface = Color(0xFFEEF0F7),
    onSurfaceVariant = Color(0xFFA9B1CC),
    error = Color(0xFFFF6F61),
)

@Composable
private fun WarningScreen(status: String, level: Float, closing: Boolean, onClose: () -> Unit) {
    var visible by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val entrance by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(420, easing = FastOutSlowInEasing),
        label = "warning entrance",
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.82f * entrance))
            .safeDrawingPadding()
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .graphicsLayer {
                    alpha = entrance
                    scaleX = 0.94f + 0.06f * entrance
                    scaleY = 0.94f + 0.06f * entrance
                }
                .semantics { paneTitle = "Scam warning" },
            shape = RoundedCornerShape(32.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
        ) {
            Column(
                modifier = Modifier
                    .drawWithCache {
                        val glow = Brush.radialGradient(
                            colors = listOf(Color(0xFF2B4396), Color(0xFF17204A), Color(0xFF0D1228)),
                            center = Offset(size.width * 0.5f, size.height * 0.48f),
                            radius = size.height * 0.72f,
                        )
                        onDrawBehind { drawRect(glow) }
                    }
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_scamcatch_logo),
                            contentDescription = null,
                            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)),
                        )
                        Column {
                            Text(
                                "ScamCatch",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "SCAM INTERVENTION",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 9.sp,
                                letterSpacing = 1.sp,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                    FilledIconButton(
                        onClick = onClose,
                        enabled = !closing,
                        modifier = Modifier
                            .size(56.dp)
                            .semantics { contentDescription = "Close warning and stop voice" },
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Color(0xFFFF4D4F),
                            contentColor = Color.White,
                            disabledContainerColor = Color(0xFFB66E71),
                            disabledContentColor = Color.White,
                        ),
                    ) {
                        Canvas(Modifier.size(25.dp)) {
                            drawLine(Color.White, Offset.Zero, Offset(size.width, size.height), 4.dp.toPx(), StrokeCap.Round)
                            drawLine(Color.White, Offset(size.width, 0f), Offset(0f, size.height), 4.dp.toPx(), StrokeCap.Round)
                        }
                    }
                }
                Column(
                    modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        "We believe you are being scammed",
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        "Pause before you send money or share information.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    VoiceOrb(level = level, closing = closing)
                    Text(
                        status,
                        modifier = Modifier.heightIn(min = 44.dp).semantics { liveRegion = LiveRegionMode.Polite },
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        "Screen checks are paused. Close this warning to continue them.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceOrb(level: Float, closing: Boolean) {
    val voice by animateFloatAsState(
        targetValue = if (closing) 0f else level.coerceIn(0f, 1f),
        animationSpec = tween(80),
        label = "voice waveform",
    )
    val animation = rememberInfiniteTransition(label = "voice orb")
    val ripple by animation.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3_200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "voice ripple",
    )
    val breathe by animation.animateFloat(
        initialValue = 1f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(1_300), RepeatMode.Reverse),
        label = "voice glow",
    )
    // This is a voice indicator, not a button. Native voice starts when the warning opens.
    Canvas(Modifier.size(248.dp).semantics { contentDescription = "ScamCatch voice" }) {
        val radius = size.minDimension * 0.31f
        val blue = Color(0xFF7AB0FF)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(blue.copy(alpha = 0.4f), blue.copy(alpha = 0f)),
                radius = size.minDimension * 0.5f,
            ),
        )
        if (!closing) {
            repeat(2) { index ->
                val phase = (ripple + index * 0.5f) % 1f
                drawCircle(
                    color = blue.copy(alpha = (1f - phase) * 0.5f),
                    radius = radius * (1f + phase * 0.5f),
                    style = Stroke(2.dp.toPx()),
                )
            }
        }
        val coreRadius = radius * if (closing) 1f else breathe + voice * 0.06f
        drawCircle(
            brush = Brush.radialGradient(
                0f to Color(0xFFE3EDFF),
                0.24f to Color(0xFF9CC3FF),
                0.55f to Color(0xFF5A8CFF),
                1f to Color(0xFF2342B8),
                center = center - Offset(coreRadius * 0.32f, coreRadius * 0.44f),
                radius = coreRadius * 1.5f,
            ),
            radius = coreRadius,
        )
        drawCircle(blue.copy(alpha = 0.7f), radius = coreRadius + 5.dp.toPx(), style = Stroke(2.dp.toPx()))
        // The website uses seven symmetric bars at 30%, 55%, 80%, 100%, 80%, 55%, 30%.
        repeat(7) { index ->
            val proportion = when (index) {
                0, 6 -> 0.3f
                1, 5 -> 0.55f
                2, 4 -> 0.8f
                else -> 1f
            }
            val halfHeight = radius * 0.34f * proportion * (1f + voice * 0.65f)
            val x = center.x + (index - 3) * radius * 0.1f
            drawLine(
                color = Color.White.copy(alpha = 0.92f),
                start = Offset(x, center.y - halfHeight),
                end = Offset(x, center.y + halfHeight),
                strokeWidth = radius * 0.056f,
                cap = StrokeCap.Round,
            )
        }
    }
}
