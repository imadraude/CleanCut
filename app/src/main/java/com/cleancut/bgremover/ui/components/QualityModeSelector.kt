package com.cleancut.bgremover.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.Hd
import androidx.compose.material.icons.outlined.PhotoFilter
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
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
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp)
        ) {
            // 1. FAST MODE
            val isFast = currentMode == SegmentationMode.FAST
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isFast) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(enabled = !isSwitching) { onModeSelected(SegmentationMode.FAST) },
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isFast && isSwitching) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.Speed,
                            contentDescription = null,
                            tint = if (isFast) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Швидкий",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isFast) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isFast) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 2. STUDIO MODE (RMBG-1.4)
            val isStudio = currentMode == SegmentationMode.STUDIO
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isStudio) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(enabled = !isSwitching) { onModeSelected(SegmentationMode.STUDIO) },
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isStudio && isSwitching) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.PhotoFilter,
                            contentDescription = null,
                            tint = if (isStudio) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Студія",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isStudio) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isStudio) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 3. ULTRA MODE (BiRefNet)
            val isUltra = currentMode == SegmentationMode.ULTRA
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isUltra) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(enabled = !isSwitching) { onModeSelected(SegmentationMode.ULTRA) },
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isUltra && isSwitching) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.Hd,
                            contentDescription = null,
                            tint = if (isUltra) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "BiRefNet",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isUltra) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isUltra) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
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
    val modelTitle = if (targetMode == SegmentationMode.ULTRA) "Ультра BiRefNet-Lite" else "Студійний RMBG-1.4"
    val modelSize = if (targetMode == SegmentationMode.ULTRA) "~213 МБ" else "~42 МБ"
    val modelDesc = if (targetMode == SegmentationMode.ULTRA) {
        "BiRefNet забезпечує еталонну точність для найдрібніших деталей, скла та тонких структур."
    } else {
        "RMBG-1.4 забезпечує студійне виділення волосся та складних меж об'єкта."
    }

    AlertDialog(
        onDismissRequest = { if (!isDownloading) onDismiss() },
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

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Модель зберігається в пам'яті пристрою та працює повністю автономно без доступу до мережі.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (isDownloading) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Завантаження: $progress%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirmDownload,
                enabled = !isDownloading,
                shape = RoundedCornerShape(10.dp),
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
                    shape = RoundedCornerShape(10.dp),
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
