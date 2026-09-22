package com.forgery.app.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** Bounds applied to manual width/height input on commit. No snap-to-64. */
const val MIN_GENERATION_SIZE = 64
const val MAX_GENERATION_SIZE = 2048
const val MAX_SIZE_DIGITS = 4

/** Max digits accepted in the seed field (Long fits 19 digits). */
const val MAX_SEED_DIGITS = 19

/**
 * Parses a size keystroke: digits only, empty commits nothing.
 * Values below [MIN_GENERATION_SIZE] are treated as in-progress typing
 * (held locally, not committed) so multi-digit input isn't clamped mid-type;
 * anything at/above the minimum is clamped to [MAX_GENERATION_SIZE].
 * Pure — no rounding, 1254 stays 1254.
 */
internal fun parseSizeInput(raw: String): Int? {
    val digits = raw.filter(Char::isDigit).take(MAX_SIZE_DIGITS)
    if (digits.isEmpty()) return null
    val parsed = digits.toIntOrNull() ?: return null
    if (parsed < MIN_GENERATION_SIZE) return null
    return parsed.coerceIn(MIN_GENERATION_SIZE, MAX_GENERATION_SIZE)
}

/**
 * Parses a seed keystroke: digits only, empty means the default (-1, random).
 * Returns null only on Long overflow — then nothing is committed.
 */
internal fun parseSeedInput(raw: String): Long? {
    val digits = raw.filter(Char::isDigit).take(MAX_SEED_DIGITS)
    if (digits.isEmpty()) return -1L
    return digits.toLongOrNull()
}

/**
 * Manual width/height inputs with a swap button between them.
 *
 * Mirrors resolver `xl_width` / `xl_height` inputs: numeric keyboard,
 * digits only, empty doesn't commit, clamp 64..2048 on commit,
 * no snap-to-64.
 */
@Composable
fun SizeInputRow(
    width: Int,
    height: Int,
    onSizeChange: (width: Int, height: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text("Size: ${width}×${height}", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(4.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DraftTextField(
                value = width.toString(),
                onValueChange = { raw ->
                    parseSizeInput(raw)?.let { onSizeChange(it, height) }
                },
                label = "Width",
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = { onSizeChange(height, width) }) { Text("⇄") }
            DraftTextField(
                value = height.toString(),
                onValueChange = { raw ->
                    parseSizeInput(raw)?.let { onSizeChange(width, it) }
                },
                label = "Height",
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * Seed input showing the stored default; the dice button resets to random.
 */
@Composable
fun SeedInputRow(
    seed: Long,
    onSeedChange: (Long) -> Unit,
    onRandomize: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        DraftTextField(
            value = seed.toString(),
            onValueChange = { raw ->
                parseSeedInput(raw)?.let(onSeedChange)
            },
            label = "Seed",
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRandomize) {
            Icon(Icons.Filled.Casino, contentDescription = "Random seed")
        }
    }
}
