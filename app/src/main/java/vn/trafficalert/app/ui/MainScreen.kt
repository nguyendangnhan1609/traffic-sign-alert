package vn.trafficalert.app.ui

import android.graphics.Paint
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import vn.trafficalert.app.FrameSize
import vn.trafficalert.app.MainViewModel
import vn.trafficalert.app.R
import vn.trafficalert.app.camera.FrameAnalyzer
import vn.trafficalert.core.Detection
import kotlin.math.roundToInt

private val SignRed = Color(0xFFD32F2F)

@Composable
fun MainScreen(vm: MainViewModel, onOpenSettings: () -> Unit) {
    val alert by vm.alert.collectAsState()
    val detections by vm.detections.collectAsState()
    val frameSize by vm.frameSize.collectAsState()
    val messages by vm.messages.collectAsState()

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            CameraPreview(vm)
            DetectionOverlay(detections, frameSize)

            // Thanh trên: tốc độ hiện tại và biển giới hạn đang áp dụng.
            Row(
                Modifier.fillMaxWidth()
                    .background(if (alert.isSpeeding) SignRed.copy(alpha = 0.85f) else Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    val speed = alert.currentSpeedKmh
                    Text(
                        if (speed != null) stringResource(R.string.speed_value, speed.roundToInt()) else stringResource(R.string.speed_unknown),
                        color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold,
                    )
                    when {
                        alert.isSpeeding -> Text(stringResource(R.string.speeding_warning), color = Color.White, fontWeight = FontWeight.Bold)
                        alert.speedLimitAcknowledged -> Text(stringResource(R.string.limit_acknowledged), color = Color.LightGray)
                        alert.activeSpeedLimitKmh == null -> Text(stringResource(R.string.limit_none), color = Color.LightGray)
                    }
                    alert.pendingNotice?.let { Text(it, color = Color(0xFFFFE082)) }
                }
                alert.activeSpeedLimitKmh?.let { SpeedLimitSign(it) }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.open_settings), tint = Color.White)
                }
            }

            if (messages.isNotEmpty()) {
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(8.dp)).padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    messages.forEach { Text(it, color = Color(0xFFFFCC80), fontSize = 14.sp) }
                }
            }
        }

        // Nửa dưới màn hình là một nút lớn để tài xế bấm mà không cần nhìn.
        Button(
            onClick = { vm.acknowledge() },
            modifier = Modifier.weight(1f).fillMaxWidth().padding(8.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32), contentColor = Color.White),
        ) {
            Text(stringResource(R.string.ack_button), fontSize = 48.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SpeedLimitSign(kmh: Int) {
    Box(
        Modifier.size(76.dp).background(Color.White, CircleShape).border(9.dp, SignRed, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text("$kmh", color = Color.Black, fontSize = if (kmh >= 100) 22.sp else 28.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CameraPreview(vm: MainViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
    }

    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var disposed = false
        future.addListener({
            // Màn hình đã rời đi trước khi camera sẵn sàng: không bind nữa.
            if (disposed) return@addListener
            val p = future.get()
            provider = p
            val selector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .build()
            val preview = Preview.Builder().setResolutionSelector(selector).build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(selector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { it.setAnalyzer(vm.analysisExecutor, FrameAnalyzer { bmp, t -> vm.onFrame(bmp, t) }) }
            p.unbindAll()
            p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            disposed = true
            provider?.unbindAll()
        }
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

/** Vẽ hộp bao lên trên preview (FIT_CENTER nên cùng phép co giãn với khung hình phân tích). */
@Composable
private fun DetectionOverlay(detections: List<Detection>, frame: FrameSize?) {
    val textPaint = remember {
        Paint().apply { color = android.graphics.Color.YELLOW; textSize = 36f; isAntiAlias = true }
    }
    Canvas(Modifier.fillMaxSize()) {
        if (frame == null || detections.isEmpty()) return@Canvas
        val scale = minOf(size.width / frame.width, size.height / frame.height)
        val w = frame.width * scale
        val h = frame.height * scale
        val ox = (size.width - w) / 2f
        val oy = (size.height - h) / 2f
        for (d in detections) {
            val left = ox + d.box.left * w
            val top = oy + d.box.top * h
            drawRect(
                color = Color.Yellow,
                topLeft = Offset(left, top),
                size = Size(d.box.width * w, d.box.height * h),
                style = Stroke(width = 4f),
            )
            drawContext.canvas.nativeCanvas.drawText(
                "${d.sign.code} ${(d.score * 100).roundToInt()}%", left, (top - 8f).coerceAtLeast(30f), textPaint,
            )
        }
    }
}
