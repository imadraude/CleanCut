package com.cleancut.bgremover.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cleancut.bgremover.domain.model.BackgroundOption
import com.cleancut.bgremover.ui.util.ZoomTransformCalculator
import kotlin.math.roundToInt

/**
 * High-performance interactive preview canvas with hardware GPU transforms.
 * Uses lambda graphicsLayer to eliminate recompositions during pinch-to-zoom and pan gestures.
 * Renders background layers directly on the GPU without intermediate CPU bitmap baking.
 */
@Composable
fun ImagePreviewArea(
    displayBitmap: Bitmap,
    originalBitmap: Bitmap,
    backgroundOption: BackgroundOption,
    modifier: Modifier = Modifier
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var showOriginal by remember { mutableStateOf(false) }

    val displayImageBitmap = remember(displayBitmap) { displayBitmap.asImageBitmap() }
    val originalImageBitmap = remember(originalBitmap) { originalBitmap.asImageBitmap() }

    val isZoomed by remember { derivedStateOf { scale > 1.05f } }
    val zoomText by remember { derivedStateOf { "${(scale * 10).roundToInt() / 10f}×" } }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(20.dp)
            )
            .clipToBounds()
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val result = ZoomTransformCalculator.calculateTransform(
                        currentScale = scale,
                        currentOffset = offset,
                        centroid = centroid,
                        pan = pan,
                        zoom = zoom,
                        containerWidth = size.width.toFloat(),
                        containerHeight = size.height.toFloat()
                    )
                    scale = result.scale
                    offset = result.offset
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // Zoomable container: using lambda graphicsLayer defers state reads to draw phase,
        // avoiding full Composable recomposition on every finger movement (60/120 FPS guarantee).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
            contentAlignment = Alignment.Center
        ) {
            // Layer 1: Background underlay (only when viewing cutout, not original)
            if (!showOriginal) {
                when (backgroundOption) {
                    is BackgroundOption.Transparent -> {
                        CheckerboardBackground()
                    }
                    is BackgroundOption.SolidColor -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(backgroundOption.colorArgb))
                        )
                    }
                    is BackgroundOption.Image -> {
                        val bgImageBitmap = remember(backgroundOption.backgroundBitmap) {
                            backgroundOption.backgroundBitmap.asImageBitmap()
                        }
                        Image(
                            bitmap = bgImageBitmap,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }

            // Layer 2: Subject bitmap (original or transparent cutout)
            val currentImage = if (showOriginal) originalImageBitmap else displayImageBitmap
            Image(
                bitmap = currentImage,
                contentDescription = if (showOriginal) "Оригінальне зображення" else "Зображення без фону",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Overlay action controls: Reset Zoom and Toggle Compare
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            // Reset zoom pill with magnification indicator using derivedState to prevent recompositions
            ZoomResetPill(
                isZoomed = isZoomed,
                zoomText = zoomText,
                onReset = {
                    scale = 1f
                    offset = Offset.Zero
                },
                modifier = Modifier.align(Alignment.TopStart)
            )

            // Compare with original toggle pill
            Surface(
                shape = CircleShape,
                color = if (showOriginal) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface.copy(alpha = 0.90f),
                tonalElevation = 4.dp,
                border = BorderStroke(
                    1.dp,
                    if (showOriginal) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                ),
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { showOriginal = !showOriginal }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Visibility,
                        contentDescription = null,
                        tint = if (showOriginal) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (showOriginal) "Оригінал" else "Результат",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (showOriginal) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun ZoomResetPill(
    isZoomed: Boolean,
    zoomText: String,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (isZoomed) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f),
            tonalElevation = 4.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            modifier = modifier
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onReset)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.RestartAlt,
                    contentDescription = "Скинути масштаб",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = zoomText,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
