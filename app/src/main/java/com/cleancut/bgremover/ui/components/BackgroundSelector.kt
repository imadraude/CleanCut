package com.cleancut.bgremover.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cleancut.bgremover.data.util.BackgroundOption
import com.cleancut.bgremover.ui.theme.PresetBlack
import com.cleancut.bgremover.ui.theme.PresetCleanSky
import com.cleancut.bgremover.ui.theme.PresetMint
import com.cleancut.bgremover.ui.theme.PresetStudioGray
import com.cleancut.bgremover.ui.theme.PresetWarmBeige
import com.cleancut.bgremover.ui.theme.PresetWhite

@Composable
fun BackgroundSelector(
    selectedOption: BackgroundOption,
    onOptionSelected: (BackgroundOption) -> Unit,
    onPickCustomImage: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorPresets = listOf(
        PresetWhite,
        PresetBlack,
        PresetStudioGray,
        PresetCleanSky,
        PresetWarmBeige,
        PresetMint
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
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
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. Transparent option (48dp x 48dp touch target)
            val isTransparent = selectedOption is BackgroundOption.Transparent
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(
                        width = if (isTransparent) 2.5.dp else 1.dp,
                        color = if (isTransparent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        shape = CircleShape
                    )
                    .clickable { onOptionSelected(BackgroundOption.Transparent) }
                    .semantics { contentDescription = "Прозорий фон" },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Block,
                    contentDescription = null,
                    tint = if (isTransparent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
            }

            // 2. Solid Color Presets (48dp x 48dp touch target each)
            colorPresets.forEach { color ->
                val isSelected = selectedOption is BackgroundOption.SolidColor && selectedOption.colorArgb == color.toArgb()
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(color)
                        .border(
                            width = if (isSelected) 2.5.dp else 1.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else Color(0xFFCCCCCC),
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
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(
                        width = if (isCustomImage) 2.5.dp else 1.dp,
                        color = if (isCustomImage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
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
