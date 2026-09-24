package com.forgery.app.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Aspect-ratio preset: button label plus the exact pixels it applies.
 *
 * Values mirror resolver-sd `setRes(mode, w, h)` calls (www/index.html):
 * no snap-to-64 — e.g. 1254 stays 1254.
 */
data class AspectRatioPreset(val label: String, val width: Int, val height: Int)

/**
 * Preset grids: first = 1MP row, second = 2MP row.
 *
 * Values mirror resolver-sd `setRes` calls (www/index.html).
 */
fun aspectPresets(): Pair<List<AspectRatioPreset>, List<AspectRatioPreset>> =
    Pair(
        listOf(
            AspectRatioPreset("1:1", 1024, 1024),
            AspectRatioPreset("3:2", 1254, 836),
            AspectRatioPreset("4:3", 1182, 887),
            AspectRatioPreset("16:9", 1365, 768),
            AspectRatioPreset("21:9", 1564, 670),
        ),
        listOf(
            AspectRatioPreset("1:1", 1448, 1448),
            AspectRatioPreset("3:2", 1773, 1182),
            AspectRatioPreset("4:3", 1672, 1254),
            AspectRatioPreset("16:9", 1936, 1089),
        ),
    )

/**
 * Aspect-ratio selector mirroring resolver `#resGrid` / `.res-switch` / `.mp-label`.
 *
 * Two labeled rows (1MP / 2MP) of preset buttons plus a "⇄" flip cell
 * at the end of the 2MP row (resolver `flipRes`: swap width/height).
 * The preset matching [currentWidth] x [currentHeight] is highlighted.
 */
@Composable
fun AspectRatioGrid(
    currentWidth: Int,
    currentHeight: Int,
    onSelect: (width: Int, height: Int) -> Unit,
    onFlip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (oneMp, twoMp) = aspectPresets()
    Column(modifier = modifier) {
        Text("Aspect Ratio", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(4.dp))
        PresetRow(
            label = "1MP",
            presets = oneMp,
            currentWidth = currentWidth,
            currentHeight = currentHeight,
            onSelect = onSelect,
            showFlip = false,
            onFlip = onFlip,
        )
        Spacer(Modifier.height(4.dp))
        PresetRow(
            label = "2MP",
            presets = twoMp,
            currentWidth = currentWidth,
            currentHeight = currentHeight,
            onSelect = onSelect,
            showFlip = true,
            onFlip = onFlip,
        )
    }
}

@Composable
private fun PresetRow(
    label: String,
    presets: List<AspectRatioPreset>,
    currentWidth: Int,
    currentHeight: Int,
    onSelect: (width: Int, height: Int) -> Unit,
    showFlip: Boolean,
    onFlip: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        presets.forEach { preset ->
            val selected = preset.width == currentWidth && preset.height == currentHeight
            val cell = Modifier.weight(1.2f)
            val padding = PaddingValues(horizontal = 2.dp)
            if (selected) {
                Button(
                    onClick = { onSelect(preset.width, preset.height) },
                    contentPadding = padding,
                    modifier = cell,
                ) { PresetLabel(preset.label) }
            } else {
                OutlinedButton(
                    onClick = { onSelect(preset.width, preset.height) },
                    contentPadding = padding,
                    modifier = cell,
                ) { PresetLabel(preset.label) }
            }
        }
        if (showFlip) {
            OutlinedButton(
                onClick = onFlip,
                contentPadding = PaddingValues(horizontal = 2.dp),
                modifier = Modifier.weight(1.2f),
            ) { PresetLabel("⇄") }
        }
    }
}

@Composable
private fun PresetLabel(text: String) {
    Text(text, fontSize = 11.sp, maxLines = 1)
}
