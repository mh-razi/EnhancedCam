package com.example.enhancedcam

import android.view.ViewGroup
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale
import kotlin.math.ln
import kotlin.math.roundToInt

@Composable
fun CameraScreen(vm: CameraViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val live by vm.liveFrame.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
    }
    LaunchedEffect(previewView) { vm.startCamera(lifecycleOwner, previewView) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        // Enhanced live preview overlay (CLAHE on a low-res analysis stream).
        val bmp = live
        if (ui.enhanced && bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        // ---- Top bar
        Row(
            Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(12.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0x99000000))
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Enhanced", color = Color.White, fontSize = 15.sp)
            Switch(
                checked = ui.enhanced,
                onCheckedChange = vm::setEnhanced,
                modifier = Modifier.padding(start = 10.dp)
            )
        }

        if (ui.thermalThrottled) {
            Text(
                "Device warm - live enhance paused, shorter bursts",
                color = Color(0xFFFFB74D),
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(top = 64.dp, start = 16.dp)
            )
        }

        // ---- Bottom controls
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(12.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0x99000000))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val statusText = when {
                ui.processingJobs > 0 && ui.status.isNotBlank() && !ui.isCapturing ->
                    "Processing (${ui.processingJobs})..."
                else -> ui.status
            }
            if (statusText.isNotBlank()) {
                Text(statusText, color = Color.White, fontSize = 13.sp)
            }

            val ranges = ui.ranges
            if (ui.manualEnabled && ranges != null) {
                Text("ISO ${ui.iso}", color = Color.White, fontSize = 13.sp)
                Slider(
                    value = ui.iso.toFloat(),
                    onValueChange = { vm.setIso(it.roundToInt()) },
                    valueRange = ranges.isoMin.toFloat()..ranges.isoMax.toFloat()
                )
                Text("Shutter ${formatShutter(ui.shutterNs)}", color = Color.White, fontSize = 13.sp)
                val lo = ln(ranges.expMinNs.toDouble())
                val hi = ln(ranges.expMaxNs.toDouble())
                val frac = ((ln(ui.shutterNs.toDouble()) - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f)
                Slider(value = frac, onValueChange = vm::setShutterFraction)
            }

            if (ui.enhanced) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Burst", color = Color.White, fontSize = 13.sp)
                    listOf(3, 4, 5).forEach { n ->
                        FilterChip(
                            selected = ui.burstCount == n,
                            onClick = { vm.setBurstCount(n) },
                            label = { Text("$n") }
                        )
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Manual", color = Color.White, fontSize = 14.sp)
                    Switch(
                        checked = ui.manualEnabled,
                        onCheckedChange = vm::setManualEnabled,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }

                val busy = ui.isCapturing || ui.processingJobs >= 2
                Box(
                    Modifier
                        .size(76.dp)
                        .clip(CircleShape)
                        .background(if (busy) Color(0xFF9E9E9E) else Color.White)
                        .border(4.dp, Color(0xFF212121), CircleShape)
                        .clickable(enabled = !busy) { vm.capture() },
                    contentAlignment = Alignment.Center
                ) {
                    if (ui.isCapturing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 3.dp
                        )
                    }
                }

                Box(Modifier.size(width = 96.dp, height = 1.dp)) // balances the row
            }
        }
    }
}

private fun formatShutter(ns: Long): String {
    val s = ns / 1_000_000_000.0
    return if (s >= 0.5) String.format(Locale.US, "%.1f s", s) else "1/${(1.0 / s).roundToInt()} s"
}
