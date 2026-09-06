package com.cleancut.bgremover.ui.screen

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
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

    // Display bitmap updated in-place for 60/120 FPS live feedback without GC thrashing
    val displayBitmap = remember { engine.createCutoutBitmap() }
    val displayImageBitmap = remember(displayBitmap) { displayBitmap.asImageBitmap() }
    val originalImageBitmap = remember(originalBitmap) { originalBitmap.asImageBitmap() }

    // Lightweight draw tick to trigger Canvas draw phase without recomposing the entire tree
    var renderTick by remember { mutableIntStateOf(0) }

    // Editor settings
    var interactionMode by remember { mutableStateOf(InteractionMode.DRAW) }
    var brushMode by remember { mutableStateOf(BrushMode.ERASE) }
    var brushRadiusDp by remember { mutableFloatStateOf(24f) }
    var showOriginal by remember { mutableStateOf(false) }
    var showCancelConfirmDialog by remember { mutableStateOf(false) }

    // Transform state (Pan & Zoom)
    var scale by remember { mutableFloatStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }

    // Active touch indicator under finger
    var currentTouchCanvasOffset by remember { mutableStateOf<Offset?>(null) }

    val density = LocalDensity.current

    BackHandler {
        if (engine.canUndo) {
            showCancelConfirmDialog = true
        } else {
            onCancel()
        }
    }

    if (showCancelConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showCancelConfirmDialog = false },
            title = { Text("Скасувати зміни?") },
            text = { Text("Всі внесені ручні виправлення будуть втрачені.") },
            confirmButton = {
                TextButton(onClick = {
                    showCancelConfirmDialog = false
                    onCancel()
                }) {
                    Text(
                        text = "Вийти",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelConfirmDialog = false }) {
                    Text("Залишитися")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Ручна правка",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (engine.canUndo) {
                                showCancelConfirmDialog = true
                            } else {
                                onCancel()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "Скасувати"
                        )
                    }
                },
                actions = {
                    // Compare with original photo toggle
                    IconButton(
                        onClick = { showOriginal = !showOriginal }
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Visibility,
                            contentDescription = if (showOriginal) "Показати результат" else "Показати оригінал",
                            tint = if (showOriginal) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Undo button
                    IconButton(
                        onClick = {
                            if (engine.undo(displayBitmap)) {
                                renderTick++
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
                            if (engine.redo(displayBitmap)) {
                                renderTick++
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
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    // Brush size slider with live diameter preview
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Розмір: ${brushRadiusDp.roundToInt()} px",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Slider(
                            value = brushRadiusDp,
                            onValueChange = { brushRadiusDp = it },
                            valueRange = 6f..80f,
                            modifier = Modifier.weight(1f)
                        )

                        // Live visual brush circle
                        Box(
                            modifier = Modifier.size(28.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            val previewRadius = (brushRadiusDp / 80f * 13f).coerceIn(2f, 13f).dp
                            Box(
                                modifier = Modifier
                                    .size(previewRadius * 2)
                                    .background(
                                        color = when (brushMode) {
                                            BrushMode.ERASE -> Color(0xFFFF5252)
                                            BrushMode.RESTORE -> Color(0xFF4CAF50)
                                            BrushMode.DEFRINGE -> Color(0xFF00B0FF)
                                        },
                                        shape = CircleShape
                                    )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Responsive 4-tool selector: equal weight, never clips text on any mobile screen
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        EditorToolItem(
                            icon = Icons.Outlined.Delete,
                            label = "Стерти",
                            selected = interactionMode == InteractionMode.DRAW && brushMode == BrushMode.ERASE,
                            onClick = {
                                interactionMode = InteractionMode.DRAW
                                brushMode = BrushMode.ERASE
                            },
                            modifier = Modifier.weight(1f)
                        )

                        EditorToolItem(
                            icon = Icons.Outlined.Brush,
                            label = "Відновити",
                            selected = interactionMode == InteractionMode.DRAW && brushMode == BrushMode.RESTORE,
                            onClick = {
                                interactionMode = InteractionMode.DRAW
                                brushMode = BrushMode.RESTORE
                            },
                            modifier = Modifier.weight(1f)
                        )

                        EditorToolItem(
                            icon = Icons.Outlined.AutoFixHigh,
                            label = "Дефриндж",
                            selected = interactionMode == InteractionMode.DRAW && brushMode == BrushMode.DEFRINGE,
                            onClick = {
                                interactionMode = InteractionMode.DRAW
                                brushMode = BrushMode.DEFRINGE
                            },
                            modifier = Modifier.weight(1f)
                        )

                        EditorToolItem(
                            icon = Icons.Outlined.PanTool,
                            label = "Зум",
                            selected = interactionMode == InteractionMode.PAN_ZOOM,
                            onClick = {
                                interactionMode = if (interactionMode == InteractionMode.PAN_ZOOM) {
                                    InteractionMode.DRAW
                                } else {
                                    InteractionMode.PAN_ZOOM
                                }
                            },
                            modifier = Modifier.weight(1f)
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

            // Calculate exact fit bounds for the image inside the canvas
            val imgAspect = imgWidth.toFloat() / imgHeight.toFloat()
            val canvasAspect = canvasWidth / max(1f, canvasHeight)

            val fitWidth: Float
            val fitHeight: Float

            if (imgAspect > canvasAspect) {
                fitWidth = canvasWidth
                fitHeight = fitWidth / imgAspect
            } else {
                fitHeight = canvasHeight
                fitWidth = fitHeight * imgAspect
            }

            val baseOffsetX = (canvasWidth - fitWidth) / 2f
            val baseOffsetY = (canvasHeight - fitHeight) / 2f

            // Map Screen/Canvas coordinate -> Image Pixel coordinate
            fun canvasToImage(canvasPt: Offset): Pair<Int, Int>? {
                val cx = canvasWidth / 2f
                val cy = canvasHeight / 2f

                val worldX = cx + (canvasPt.x - cx - panOffset.x) / scale
                val worldY = cy + (canvasPt.y - cy - panOffset.y) / scale

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
                return ((brushPx * scaleRatio) / scale).roundToInt().coerceIn(1, max(imgWidth, imgHeight) / 4)
            }

            // Interactive Multi-touch Gesture Container
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(interactionMode, brushMode, brushRadiusDp, fitWidth, fitHeight, canvasWidth, canvasHeight) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var isTransforming = false
                            var isDrawing = false
                            var lastCentroid = down.position
                            var prevDistance = 0f

                            do {
                                val event = awaitPointerEvent()
                                val pressedChanges = event.changes.filter { it.pressed }
                                val count = pressedChanges.size

                                if (count >= 2) {
                                    // Multi-touch: simultaneous pinch-to-zoom & pan in ANY mode
                                    if (isDrawing) {
                                        isDrawing = false
                                        engine.endStroke()
                                        currentTouchCanvasOffset = null
                                        renderTick++
                                    }
                                    isTransforming = true

                                    val p1 = pressedChanges[0].position
                                    val p2 = pressedChanges[1].position
                                    val centroid = (p1 + p2) / 2f
                                    val distance = (p1 - p2).getDistance()

                                    if (prevDistance > 0f) {
                                        val zoom = distance / prevDistance
                                        val pan = centroid - lastCentroid

                                        val oldScale = scale
                                        val newScale = (scale * zoom).coerceIn(1f, 10f)
                                        val cx = canvasWidth / 2f
                                        val cy = canvasHeight / 2f
                                        val cRel = centroid - Offset(cx, cy)

                                        val newPan = panOffset + (panOffset - cRel) * (newScale / oldScale - 1f) + pan
                                        scale = newScale

                                        if (newScale > 1f) {
                                            val maxPanX = ((fitWidth * newScale - canvasWidth).coerceAtLeast(0f) / 2f) + (fitWidth * 0.25f)
                                            val maxPanY = ((fitHeight * newScale - canvasHeight).coerceAtLeast(0f) / 2f) + (fitHeight * 0.25f)
                                            panOffset = Offset(
                                                newPan.x.coerceIn(-maxPanX, maxPanX),
                                                newPan.y.coerceIn(-maxPanY, maxPanY)
                                            )
                                        } else {
                                            panOffset = Offset.Zero
                                        }

                                        pressedChanges.forEach { it.consume() }
                                    }

                                    lastCentroid = centroid
                                    prevDistance = distance
                                } else if (count == 1 && !isTransforming) {
                                    val change = pressedChanges[0]
                                    val pos = change.position

                                    if (interactionMode == InteractionMode.PAN_ZOOM) {
                                        val pan = pos - change.previousPosition
                                        val newPan = panOffset + pan
                                        if (scale > 1f) {
                                            val maxPanX = ((fitWidth * scale - canvasWidth).coerceAtLeast(0f) / 2f) + (fitWidth * 0.25f)
                                            val maxPanY = ((fitHeight * scale - canvasHeight).coerceAtLeast(0f) / 2f) + (fitHeight * 0.25f)
                                            panOffset = Offset(
                                                newPan.x.coerceIn(-maxPanX, maxPanX),
                                                newPan.y.coerceIn(-maxPanY, maxPanY)
                                            )
                                        }
                                        change.consume()
                                    } else {
                                        currentTouchCanvasOffset = pos
                                        if (!isDrawing) {
                                            isDrawing = true
                                            engine.startStroke()
                                        }
                                        canvasToImage(pos)?.let { (px, py) ->
                                            val radius = calculateImageRadius()
                                            val modifiedBox = engine.continueStroke(px, py, radius, brushMode)
                                            if (modifiedBox != null) {
                                                engine.updateBitmapRegion(
                                                    displayBitmap,
                                                    modifiedBox.minX, modifiedBox.minY,
                                                    modifiedBox.maxX, modifiedBox.maxY
                                                )
                                                renderTick++
                                            }
                                        }
                                        change.consume()
                                    }
                                }
                            } while (event.changes.any { it.pressed })

                            if (isDrawing) {
                                isDrawing = false
                                currentTouchCanvasOffset = null
                                engine.endStroke()
                                renderTick++
                            }
                            currentTouchCanvasOffset = null
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
                    Box(
                        modifier = Modifier
                            .size(
                                with(density) { fitWidth.toDp() },
                                with(density) { fitHeight.toDp() }
                            )
                            .align(Alignment.Center)
                    ) {
                        CheckerboardBackground()

                        if (showOriginal) {
                            androidx.compose.foundation.Image(
                                bitmap = originalImageBitmap,
                                contentDescription = "Оригінальне зображення",
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Canvas(
                                modifier = Modifier.fillMaxSize()
                            ) {
                                // Read renderTick in draw phase to invalidate without recomposition
                                @Suppress("UNUSED_VARIABLE")
                                val tick = renderTick
                                drawImage(
                                    image = displayImageBitmap,
                                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt())
                                )
                            }
                        }
                    }
                }

                // Precision Brush Cursor with dual contrast ring
                currentTouchCanvasOffset?.let { touchPos ->
                    if (interactionMode == InteractionMode.DRAW && !showOriginal) {
                        val brushPx = with(density) { brushRadiusDp.dp.toPx() }
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            // Dark outer ring for clear visibility over white/light areas
                            drawCircle(
                                color = Color(0x66000000),
                                radius = brushPx + 1.dp.toPx(),
                                center = touchPos,
                                style = Stroke(width = 3.dp.toPx())
                            )
                            // Colored inner ring for mode distinction
                            drawCircle(
                                color = when (brushMode) {
                                    BrushMode.ERASE -> Color(0xFFFF5252) // Red
                                    BrushMode.RESTORE -> Color(0xFF4CAF50) // Green
                                    BrushMode.DEFRINGE -> Color(0xFF00B0FF) // Cyan
                                },
                                radius = brushPx,
                                center = touchPos,
                                style = Stroke(width = 2.dp.toPx())
                            )
                            // Center anchor point
                            drawCircle(
                                color = Color.White,
                                radius = 2.dp.toPx(),
                                center = touchPos
                            )
                        }
                    }
                }

                // Floating Reset Zoom button when zoomed in
                AnimatedVisibility(
                    visible = scale > 1.05f || panOffset != Offset.Zero,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp)
                ) {
                    FilledTonalIconButton(
                        onClick = {
                            scale = 1f
                            panOffset = Offset.Zero
                        },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.RestartAlt,
                            contentDescription = "Скинути масштаб"
                        )
                    }
                }

                // Floating indicator when viewing original photo
                AnimatedVisibility(
                    visible = showOriginal,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
                        tonalElevation = 4.dp
                    ) {
                        Text(
                            text = "Оригінал",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorToolItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        animationSpec = tween(durationMillis = 160),
        label = "toolBg"
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(durationMillis = 160),
        label = "toolContent"
    )

    Surface(
        selected = selected,
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = backgroundColor,
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)) else null,
        modifier = modifier.height(54.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

