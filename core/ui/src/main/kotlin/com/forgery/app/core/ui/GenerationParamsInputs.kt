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
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

/** Bounds applied to manual width/height input on commit. No snap-to-64. */
const val MIN_GENERATION_SIZE = 64
const val MAX_GENERATION_SIZE = 2048
const val MAX_SIZE_DIGITS = 4

/** Max digits accepted in the seed field (Long fits 19 digits). */
const val MAX_SEED_DIGITS = 19

/**
 * Commits a raw size string to a domain value. Pure — runs in the ViewModel on
 * Done / focus loss / Generate. Empty or below [MIN_GENERATION_SIZE] (still
 * typing, e.g. "1") commits nothing (null) so the raw text survives;
 * anything at/above the minimum is clamped to [MAX_GENERATION_SIZE].
 * No snap-to-64: 1254 stays 1254.
 */
fun parseSizeInput(raw: String): Int? {    val digits = raw.filter(Char::isDigit).take(MAX_SIZE_DIGITS)
    if (digits.isEmpty()) return null
    val parsed = digits.toIntOrNull() ?: return null
    if (parsed < MIN_GENERATION_SIZE) return null
    return parsed.coerceIn(MIN_GENERATION_SIZE, MAX_GENERATION_SIZE)
}

/**
 * Commits a raw seed string. Pure — runs in the ViewModel on commit.
 * Digits only, empty means the default (-1, random).
 * Returns null only on Long overflow — then nothing is committed.
 */
fun parseSeedInput(raw: String): Long? {
    val digits = raw.filter(Char::isDigit).take(MAX_SEED_DIGITS)
    if (digits.isEmpty()) return -1L
    return digits.toLongOrNull()
}

/**
 * Manual width/height inputs with a swap button between them.
 *
 * Raw [TextFieldValue] passthrough: the VM owns the text, parsing/commit
 * ([parseSizeInput]) happens in the VM, never per keystroke.
 * Mirrors resolver `xl_width` / `xl_height` inputs: numeric keyboard.
 */
@Composable
fun SizeInputRow(
    width: TextFieldValue,
    height: TextFieldValue,
    onWidthChange: (TextFieldValue) -> Unit,
    onHeightChange: (TextFieldValue) -> Unit,
    onSwap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text("Size: ${width.text}×${height.text}", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(4.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DraftTextField(
                value = width,
                onValueChange = onWidthChange,
                label = "Width",
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = onSwap) { Text("⇄") }
            DraftTextField(
                value = height,
                onValueChange = onHeightChange,
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
    seed: TextFieldValue,
    onSeedChange: (TextFieldValue) -> Unit,
    onRandomize: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        DraftTextField(
            value = seed,
            onValueChange = onSeedChange,
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
