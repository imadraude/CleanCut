package com.cleancut.bgremover.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Hd
import androidx.compose.material.icons.outlined.PhotoFilter
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cleancut.bgremover.domain.model.SegmentationMode

@Composable
fun QualityModeSelector(
    currentMode: SegmentationMode,
    onModeSelected: (SegmentationMode) -> Unit,
    modifier: Modifier = Modifier,
    isSwitching: Boolean = false
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            ModeTabItem(
                title = "Швидкий",
                icon = Icons.Outlined.Speed,
                isSelected = currentMode == SegmentationMode.FAST,
                isSwitching = isSwitching && currentMode == SegmentationMode.FAST,
                onSelect = { onModeSelected(SegmentationMode.FAST) },
                modifier = Modifier.weight(1f)
            )

            ModeTabItem(
                title = "Студія",
                icon = Icons.Outlined.PhotoFilter,
                isSelected = currentMode == SegmentationMode.STUDIO,
                isSwitching = isSwitching && currentMode == SegmentationMode.STUDIO,
                onSelect = { onModeSelected(SegmentationMode.STUDIO) },
                modifier = Modifier.weight(1f)
            )

            ModeTabItem(
                title = "BiRefNet",
                icon = Icons.Outlined.Hd,
                isSelected = currentMode == SegmentationMode.ULTRA,
                isSwitching = isSwitching && currentMode == SegmentationMode.ULTRA,
                onSelect = { onModeSelected(SegmentationMode.ULTRA) },
                modifier = Modifier.weight(1f)
            )

            ModeTabItem(
                title = "Аніме",
                icon = Icons.Outlined.AutoAwesome,
                isSelected = currentMode == SegmentationMode.ANIME,
                isSwitching = isSwitching && currentMode == SegmentationMode.ANIME,
                onSelect = { onModeSelected(SegmentationMode.ANIME) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ModeTabItem(
    title: String,
    icon: ImageVector,
    isSelected: Boolean,
    isSwitching: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val animatedBgColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent,
        animationSpec = tween(durationMillis = 180),
        label = "modeBgColor"
    )
    val animatedContentColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(durationMillis = 180),
        label = "modeContentColor"
    )

    Box(
        modifier = modifier
            .height(42.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(animatedBgColor)
            .then(
                if (isSelected) {
                    Modifier.border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                        shape = RoundedCornerShape(11.dp)
                    )
                } else Modifier
            )
            .clickable(enabled = !isSwitching) { onSelect() },
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 2.dp)
        ) {
            if (isSwitching) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp)
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = animatedContentColor,
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun DownloadModelDialog(
    targetMode: SegmentationMode,
    isDownloading: Boolean,
    progress: Int,
    onConfirmDownload: () -> Unit,
    onDismiss: () -> Unit
) {
    val modelTitle = when (targetMode) {
        SegmentationMode.ULTRA -> "Ультра BiRefNet-Lite"
        SegmentationMode.STUDIO -> "Студійний RMBG-1.4"
        SegmentationMode.ANIME -> "Аніме IS-Net"
        SegmentationMode.FAST -> "Швидкий ML Kit"
    }
    val modelSize = when (targetMode) {
        SegmentationMode.ULTRA -> "~224 МБ"
        SegmentationMode.STUDIO -> "~42 МБ"
        SegmentationMode.ANIME -> "~168 МБ"
        SegmentationMode.FAST -> "0 МБ"
    }
    val modelDesc = when (targetMode) {
        SegmentationMode.ULTRA -> "BiRefNet забезпечує еталонну точність для найдрібніших деталей, скла та тонких структур."
        SegmentationMode.STUDIO -> "RMBG-1.4 забезпечує студійне виділення волосся та складних меж об'єкта."
        SegmentationMode.ANIME -> "IS-Net Anime оптимізовано спеціально для 2D-ілюстрацій, манґи та аніме з ідеальними лініями без паразитарних білих країв."
        SegmentationMode.FAST -> "Швидка базова сегментація."
    }

    AlertDialog(
        onDismissRequest = { if (!isDownloading) onDismiss() },
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(
                text = "Завантаження моделі: $modelTitle",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column {
                Text(
                    text = "$modelDesc Розмір файлу: $modelSize.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Модель зберігається в пам'яті пристрою та працює повністю автономно без доступу до мережі.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )

                if (isDownloading) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Завантаження...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "$progress%",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirmDownload,
                enabled = !isDownloading,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.height(48.dp)
            ) {
                Text(
                    text = if (isDownloading) "Завантаження..." else "Завантажити ($modelSize)",
                    style = MaterialTheme.typography.titleMedium
                )
            }
        },
        dismissButton = {
            if (!isDownloading) {
                OutlinedButton(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.height(48.dp)
                ) {
                    Text(
                        text = "Скасувати",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
    )
}
