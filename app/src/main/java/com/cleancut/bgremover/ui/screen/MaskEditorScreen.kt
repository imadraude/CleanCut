package com.cleancut.bgremover.ui.screen

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.Redo
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.cleancut.bgremover.data.editor.BrushMode
import com.cleancut.bgremover.data.editor.MaskRefineEngine
import com.cleancut.bgremover.ui.components.CheckerboardBackground
import kotlin.math.max
import kotlin.math.roundToInt

enum class InteractionMode {
    DRAW,
    PAN_ZOOM
}

/**
 * Full-screen interactive mask refinement editor.
 * Provides manual brush editing (Erase / Restore / Hair Defringe) with Zoom & Pan and memory-efficient Undo/Redo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaskEditorScreen(
    originalBitmap: Bitmap,
    initialCutoutBitmap: Bitmap,
    onApply: (Bitmap) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val imgWidth = originalBitmap.width
    val imgHeight = originalBitmap.height

    // Initialize engine with initial pixels
    val origPixels = remember(originalBitmap) {
        IntArray(imgWidth * imgHeight).also {
            originalBitmap.getPixels(it, 0, imgWidth, 0, 0, imgWidth, imgHeight)
        }
    }
    val cutoutPixels = remember(initialCutoutBitmap) {
        IntArray(imgWidth * imgHeight).also {
            initialCutoutBitmap.getPixels(it, 0, imgWidth, 0, 0, imgWidth, imgHeight)
        }
    }

    val engine = remember { MaskRefineEngine(imgWidth, imgHeight, origPixels, cutoutPixels) }

    // Version key to force Bitmap & UI refreshes after strokes / undo / redo
    var historyRevision by remember { mutableIntStateOf(0) }
    var currentDisplayBitmap by remember(historyRevision) {
        mutableStateOf(engine.createCutoutBitmap())
    }

    // Editor settings
    var interactionMode by remember { mutableStateOf(InteractionMode.DRAW) }
    var brushMode by remember { mutableStateOf(BrushMode.ERASE) }
    var brushRadiusDp by remember { mutableFloatStateOf(24f) }
    var showMaskOverlay by remember { mutableStateOf(false) }

    // Transform state (Pan & Zoom)
    var scale by remember { mutableFloatStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }

    // Active touch indicator under finger
    var currentTouchCanvasOffset by remember { mutableStateOf<Offset?>(null) }

    val density = LocalDensity.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Підправити краї",
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "Скасувати"
                        )
                    }
                },
                actions = {
                    // Undo button
                    IconButton(
                        onClick = {
                            if (engine.undo()) {
                                historyRevision++
                            }
                        },
                        enabled = engine.canUndo
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Undo,
                            contentDescription = "Назад"
                        )
                    }

                    // Redo button
                    IconButton(
                        onClick = {
                            if (engine.redo()) {
                                historyRevision++
                            }
                        },
                        enabled = engine.canRedo
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Redo,
                            contentDescription = "Вперед"
                        )
                    }

                    // Apply button
                    IconButton(
                        onClick = {
                            onApply(engine.createCutoutBitmap())
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Check,
                            contentDescription = "Застосувати",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 3.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    // Brush size slider with live value indicator
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Розмір: ${brushRadiusDp.roundToInt()} px",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.width(100.dp)
                        )

                        Slider(
                            value = brushRadiusDp,
                            onValueChange = { brushRadiusDp = it },
                            valueRange = 8f..80f,
                            modifier = Modifier.weight(1f)
                        )

                        // Reset Zoom button
                        if (scale > 1.05f || panOffset != Offset.Zero) {
                            IconButton(
                                onClick = {
                                    scale = 1f
                                    panOffset = Offset.Zero
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.RestartAlt,
                                    contentDescription = "Скинути масштаб"
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Tool selector row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Interaction toggle: Draw vs Pan/Zoom
                        FilterChip(
                            selected = interactionMode == InteractionMode.PAN_ZOOM,
                            onClick = {
                                interactionMode = if (interactionMode == InteractionMode.PAN_ZOOM) {
                                    InteractionMode.DRAW
                                } else {
                                    InteractionMode.PAN_ZOOM
                                }
                            },
                            label = { Text("Зум") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.PanTool,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        )

                        // Brush Mode: Erase
                        FilterChip(
                            selected = interactionMode == InteractionMode.DRAW && brushMode == BrushMode.ERASE,
                            onClick = {
                                interactionMode = InteractionMode.DRAW
                                brushMode = BrushMode.ERASE
                            },
                            label = { Text("Стерти") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.Delete,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        )

                        // Brush Mode: Restore
                        FilterChip(
                            selected = interactionMode == InteractionMode.DRAW && brushMode == BrushMode.RESTORE,
                            onClick = {
                                interactionMode = InteractionMode.DRAW
                                brushMode = BrushMode.RESTORE
                            },
                            label = { Text("Відновити") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.Brush,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        )

                        // Brush Mode: Smart Defringe
                        FilterChip(
                            selected = interactionMode == InteractionMode.DRAW && brushMode == BrushMode.DEFRINGE,
                            onClick = {
                                interactionMode = InteractionMode.DRAW
                                brushMode = BrushMode.DEFRINGE
                            },
                            label = { Text("Дефриндж") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.AutoFixHigh,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        )
                    }
                }
            }
        },
        modifier = modifier
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
                .clipToBounds()
        ) {
            val canvasWidth = constraints.maxWidth.toFloat()
            val canvasHeight = constraints.maxHeight.toFloat()

            // Calculate exact fit bounds for the image
            val imgAspect = imgWidth.toFloat() / imgHeight.toFloat()
            val canvasAspect = canvasWidth / max(1f, canvasHeight)

            val fitWidth: Float
            val fitHeight: Float
            val baseOffsetX: Float
            val baseOffsetY: Float

            if (imgAspect > canvasAspect) {
                fitWidth = canvasWidth
                fitHeight = fitWidth / imgAspect
                baseOffsetX = 0f
                baseOffsetY = (canvasHeight - fitHeight) / 2f
            } else {
                fitHeight = canvasHeight
                fitWidth = fitHeight * imgAspect
                baseOffsetX = (canvasWidth - fitWidth) / 2f
                baseOffsetY = 0f
            }

            // Function to map Canvas coordinate -> Image Pixel coordinate
            fun canvasToImage(canvasPt: Offset): Pair<Int, Int>? {
                // Accounting for scale and pan relative to canvas center
                val cx = canvasWidth / 2f
                val cy = canvasHeight / 2f

                // World coordinate before zoom centering:
                val worldX = (canvasPt.x - cx - panOffset.x) / scale + cx
                val worldY = (canvasPt.y - cy - panOffset.y) / scale + cy

                val localX = worldX - baseOffsetX
                val localY = worldY - baseOffsetY

                if (localX < 0f || localX >= fitWidth || localY < 0f || localY >= fitHeight) {
                    return null
                }

                val px = ((localX / fitWidth) * imgWidth).toInt().coerceIn(0, imgWidth - 1)
                val py = ((localY / fitHeight) * imgHeight).toInt().coerceIn(0, imgHeight - 1)
                return Pair(px, py)
            }

            fun calculateImageRadius(): Int {
                val brushPx = with(density) { brushRadiusDp.dp.toPx() }
                val scaleRatio = imgWidth / fitWidth
                return ((brushPx * scaleRatio) / scale).roundToInt().coerceIn(2, max(imgWidth, imgHeight) / 4)
            }

            // Interactive Drawing / Zoom Container
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(interactionMode) {
                        if (interactionMode == InteractionMode.PAN_ZOOM) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(0.8f, 8f)
                                panOffset += pan
                            }
                        } else {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    currentTouchCanvasOffset = offset
                                    canvasToImage(offset)?.let { (px, py) ->
                                        engine.startStroke()
                                        engine.continueStroke(px, py, calculateImageRadius(), brushMode)
                                    }
                                },
                                onDrag = { change, _ ->
                                    val offset = change.position
                                    currentTouchCanvasOffset = offset
                                    canvasToImage(offset)?.let { (px, py) ->
                                        engine.continueStroke(px, py, calculateImageRadius(), brushMode)
                                    }
                                },
                                onDragEnd = {
                                    currentTouchCanvasOffset = null
                                    engine.endStroke()
                                    historyRevision++
                                },
                                onDragCancel = {
                                    currentTouchCanvasOffset = null
                                    engine.endStroke()
                                    historyRevision++
                                }
                            )
                        }
                    }
            ) {
                // Background & Cutout Layer with GPU Transform
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = panOffset.x
                            translationY = panOffset.y
                        }
                ) {
                    // Fit image box
                    Box(
                        modifier = Modifier
                            .size(
                                with(density) { fitWidth.toDp() },
                                with(density) { fitHeight.toDp() }
                            )
                            .align(Alignment.Center)
                    ) {
                        CheckerboardBackground()

                        androidx.compose.foundation.Image(
                            bitmap = currentDisplayBitmap.asImageBitmap(),
                            contentDescription = "Відредагований об'єкт",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                // Brush circle cursor under finger (drawn on top of everything without lag)
                currentTouchCanvasOffset?.let { touchPos ->
                    val brushPx = with(density) { brushRadiusDp.dp.toPx() }
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawCircle(
                            color = when (brushMode) {
                                BrushMode.ERASE -> Color(0xFFFF5252) // Red
                                BrushMode.RESTORE -> Color(0xFF4CAF50) // Green
                                BrushMode.DEFRINGE -> Color(0xFF40C4FF) // Cyan
                            },
                            radius = brushPx,
                            center = touchPos,
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }
                }
            }
        }
    }
}
