package com.cleancut.bgremover.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cleancut.bgremover.domain.model.BackgroundOption
import com.cleancut.bgremover.ui.theme.PresetBlack
import com.cleancut.bgremover.ui.theme.PresetCleanSky
import com.cleancut.bgremover.ui.theme.PresetLavender
import com.cleancut.bgremover.ui.theme.PresetMint
import com.cleancut.bgremover.ui.theme.PresetStudioGray
import com.cleancut.bgremover.ui.theme.PresetWarmBeige
import com.cleancut.bgremover.ui.theme.PresetWhite

private val ColorPresets = listOf(
    PresetWhite,
    PresetBlack,
    PresetStudioGray,
    PresetCleanSky,
    PresetWarmBeige,
    PresetMint,
    PresetLavender
)

@Composable
fun BackgroundSelector(
    selectedOption: BackgroundOption,
    onOptionSelected: (BackgroundOption) -> Unit,
    onPickCustomImage: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Text(
            text = "Вибір фону",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. Transparent option (visual checkerboard preview)
            val isTransparent = selectedOption is BackgroundOption.Transparent
            val transScale by animateFloatAsState(
                targetValue = if (isTransparent) 1.08f else 1f,
                animationSpec = tween(150),
                label = "transScale"
            )

            Box(
                modifier = Modifier
                    .size(48.dp)
                    .graphicsLayer {
                        scaleX = transScale
                        scaleY = transScale
                    }
                    .clip(CircleShape)
                    .border(
                        width = if (isTransparent) 2.5.dp else 1.dp,
                        color = if (isTransparent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        shape = CircleShape
                    )
                    .clickable { onOptionSelected(BackgroundOption.Transparent) }
                    .semantics { contentDescription = "Прозорий фон" },
                contentAlignment = Alignment.Center
            ) {
                CheckerboardBackground(
                    modifier = Modifier.clip(CircleShape),
                    squareSizePx = 16f
                )
                if (isTransparent) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // 2. Solid Color Presets
            ColorPresets.forEach { color ->
                val isSelected = selectedOption is BackgroundOption.SolidColor && selectedOption.colorArgb == color.toArgb()
                val colorScale by animateFloatAsState(
                    targetValue = if (isSelected) 1.08f else 1f,
                    animationSpec = tween(150),
                    label = "colorScale"
                )

                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .graphicsLayer {
                            scaleX = colorScale
                            scaleY = colorScale
                        }
                        .clip(CircleShape)
                        .background(color)
                        .border(
                            width = if (isSelected) 2.5.dp else 1.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            shape = CircleShape
                        )
                        .clickable { onOptionSelected(BackgroundOption.SolidColor(color.toArgb())) }
                        .semantics { contentDescription = "Колір фону" },
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Outlined.Check,
                            contentDescription = null,
                            tint = if (color == PresetBlack) Color.White else Color.Black,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // 3. Custom Image Picker
            val isCustomImage = selectedOption is BackgroundOption.Image
            val customScale by animateFloatAsState(
                targetValue = if (isCustomImage) 1.08f else 1f,
                animationSpec = tween(150),
                label = "customScale"
            )

            Box(
                modifier = Modifier
                    .size(48.dp)
                    .graphicsLayer {
                        scaleX = customScale
                        scaleY = customScale
                    }
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(
                        width = if (isCustomImage) 2.5.dp else 1.dp,
                        color = if (isCustomImage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        shape = CircleShape
                    )
                    .clickable { onPickCustomImage() }
                    .semantics { contentDescription = "Вибрати власне зображення для фону" },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.AddPhotoAlternate,
                    contentDescription = null,
                    tint = if (isCustomImage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}
