package com.antiscam.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.antiscam.app.capture.CaptureStore
import com.antiscam.app.capture.ScamIntervention

/** A translucent foreground activity allows voice to use the microphone while the warning is visible. */
class ScamWarningActivity : ComponentActivity() {
    private var closing by mutableStateOf(false)
    private var voiceLevel by mutableFloatStateOf(0f)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setFinishOnTouchOutside(false)
        enableEdgeToEdge()
        setContent {
            val incident by ScamIntervention.incident.collectAsStateWithLifecycle()
            val captureState by CaptureStore.state.collectAsStateWithLifecycle()
            LaunchedEffect(incident) {
                if (incident == null) finish()
            }
            BackHandler { closeWarning() }
            MaterialTheme(colorScheme = lightColorScheme()) {
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
            .background(Color.Black.copy(alpha = 0.32f * entrance))
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
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF7FAF9)),
            elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(Modifier.fillMaxWidth()) {
                    Text(
                        "ANTISCAM",
                        modifier = Modifier.align(Alignment.CenterStart),
                        style = MaterialTheme.typography.labelLarge,
                        color = Color(0xFF48645D),
                    )
                    FilledIconButton(
                        onClick = onClose,
                        enabled = !closing,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .size(64.dp)
                            .semantics { contentDescription = "Close warning and stop voice" },
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Color(0xFFCB292F),
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
                    AssistantFace(level)
                    Text(
                        "We believe you are being scammed",
                        color = Color(0xFF1F3430),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        "Pause before you send money or share information.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color(0xFF425C55),
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        status,
                        modifier = Modifier.heightIn(min = 44.dp).semantics { liveRegion = LiveRegionMode.Polite },
                        color = Color(0xFF006B5E),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        "Screen checks are paused. Close this warning to continue them.",
                        color = Color(0xFF536A63),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun AssistantFace(level: Float) {
    val mouth by animateFloatAsState(targetValue = level.coerceIn(0f, 1f), animationSpec = tween(80), label = "voice mouth")
    val animation = rememberInfiniteTransition(label = "assistant blink")
    val eyes by animation.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 4_600
                1f at 0
                1f at 4_200
                0.1f at 4_290
                1f at 4_390
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "blink",
    )
    Canvas(Modifier.size(152.dp).semantics { contentDescription = "AI voice assistant" }) {
        val w = size.width
        val h = size.height
        val ink = Color(0xFF07584E)
        drawCircle(color = Color(0xFFD7F1E8))
        drawCircle(color = Color(0xFFEAF9F2), radius = w * 0.36f, center = Offset(w * 0.43f, h * 0.39f))
        val eyeHeight = h * 0.18f * eyes
        drawRoundRect(
            color = ink,
            topLeft = Offset(w * 0.31f, h * 0.42f - eyeHeight / 2),
            size = Size(w * 0.105f, eyeHeight),
            cornerRadius = CornerRadius(w * 0.07f),
        )
        drawRoundRect(
            color = ink,
            topLeft = Offset(w * 0.585f, h * 0.42f - eyeHeight / 2),
            size = Size(w * 0.105f, eyeHeight),
            cornerRadius = CornerRadius(w * 0.07f),
        )
        drawOval(Color(0xFFF0ADA8).copy(alpha = 0.65f), topLeft = Offset(w * 0.2f, h * 0.55f), size = Size(w * 0.14f, h * 0.065f))
        drawOval(Color(0xFFF0ADA8).copy(alpha = 0.65f), topLeft = Offset(w * 0.66f, h * 0.55f), size = Size(w * 0.14f, h * 0.065f))
        val mouthHeight = h * (0.045f + mouth * 0.145f)
        drawRoundRect(
            color = ink,
            topLeft = Offset(w * (0.415f - mouth * 0.025f), h * 0.67f - mouthHeight / 2),
            size = Size(w * (0.17f + mouth * 0.05f), mouthHeight),
            cornerRadius = CornerRadius(w * 0.09f),
        )
    }
}
