@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.hosani69.gameslibrary

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/** Lets the user pick the square part of the picture to keep: drag to move the frame, pinch to resize it. */
@Composable
fun CropScreen(photo: Bitmap, onRotate: () -> Unit, onCancel: () -> Unit, onDone: (left: Float, top: Float, side: Float) -> Unit) {
    BackHandler(onBack = onCancel)
    val image = remember(photo) { photo.asImageBitmap() }
    val w = photo.width.toFloat()
    val h = photo.height.toFloat()
    val biggest = max(w, h)
    // The frame, in picture pixels.
    var cx by remember(photo) { mutableFloatStateOf(w / 2) }
    var cy by remember(photo) { mutableFloatStateOf(h / 2) }
    var side by remember(photo) { mutableFloatStateOf(min(w, h)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Crop") },
                navigationIcon = { TextButton(onClick = onCancel) { Text("Cancel") } },
                actions = {
                    Button(onClick = { onDone(cx - side / 2, cy - side / 2, side) }, modifier = Modifier.padding(end = 8.dp)) { Text("Done") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                Canvas(
                    Modifier.fillMaxSize().pointerInput(photo) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val scale = size.width / biggest
                            side = (side * zoom).coerceIn(biggest * 0.1f, biggest)
                            cx = (cx + pan.x / scale).coerceIn(0f, w)
                            cy = (cy + pan.y / scale).coerceIn(0f, h)
                        }
                    },
                ) {
                    val scale = size.width / biggest
                    val ox = (size.width - w * scale) / 2
                    val oy = (size.height - h * scale) / 2
                    drawImage(
                        image,
                        dstOffset = IntOffset(ox.toInt(), oy.toInt()),
                        dstSize = IntSize((w * scale).toInt(), (h * scale).toInt()),
                    )
                    val l = ox + (cx - side / 2) * scale
                    val t = oy + (cy - side / 2) * scale
                    val s = side * scale
                    val shade = Color.Black.copy(alpha = 0.55f)
                    // Darken everything outside the frame.
                    drawRect(shade, Offset.Zero, Size(size.width, max(0f, t)))
                    drawRect(shade, Offset(0f, t + s), Size(size.width, max(0f, size.height - t - s)))
                    drawRect(shade, Offset(0f, t), Size(max(0f, l), s))
                    drawRect(shade, Offset(l + s, t), Size(max(0f, size.width - l - s), s))
                    drawRect(Color.White, Offset(l, t), Size(s, s), style = Stroke(2.dp.toPx()))
                }
            }
            Text(
                "Drag to move the frame, pinch to make it bigger or smaller.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onRotate) { Text("Rotate") }
                OutlinedButton(onClick = { cx = w / 2; cy = h / 2; side = min(w, h) }) { Text("Reset") }
                OutlinedButton(onClick = { cx = w / 2; cy = h / 2; side = biggest }) { Text("Whole picture") }
            }
        }
    }
}
