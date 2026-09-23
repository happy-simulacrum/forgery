package com.forgery.app.core.ui

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Stateless text field for MVI screens.
 *
 * The ViewModel owns the [TextFieldValue] (single source of truth, in memory);
 * this composable renders it verbatim and forwards IME edits. No local text
 * state, no store echo, no adoption — cursor/selection round-trips through
 * the VM untouched. Domain commit (parsing) and persistence happen in the VM
 * on Done / focus loss / navigation / Generate, never per keystroke.
 */
@Composable
fun DraftTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    singleLine: Boolean = false,
    minLines: Int = 1,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = label?.let { { Text(it) } },
        singleLine = singleLine,
        minLines = minLines,
        keyboardOptions = keyboardOptions,
        modifier = modifier,
    )
}

/**
 * Merges an external text update (LoRA/Style insert, mode switch, handoff)
 * into VM-held [TextFieldValue], preserving the cursor/selection
 * (clamped to the new length). Pure — unit-tested. Used by ViewModels,
 * not by composables.
 */
fun adoptExternal(current: TextFieldValue, external: String): TextFieldValue {
    if (current.text == external) return current
    val sel = current.selection
    val clamped = TextRange(
        sel.start.coerceIn(0, external.length),
        sel.end.coerceIn(0, external.length),
    )
    return current.copy(text = external, selection = clamped)
}

/**
 * Drops non-digit characters (and overflow past [maxDigits]), keeping the
 * cursor anchored to the same logical position. Pure — unit-tested.
 */
fun filterDigits(value: TextFieldValue, maxDigits: Int = 5): TextFieldValue {
    val kept = StringBuilder()
    var selStart = value.selection.start
    var selEnd = value.selection.end
    value.text.forEachIndexed { index, c ->
        if (c.isDigit() && kept.length < maxDigits) {
            kept.append(c)
        } else {
            if (index < value.selection.start) selStart--
            if (index < value.selection.end) selEnd--
        }
    }
    val text = kept.toString()
    return TextFieldValue(
        text = text,
        selection = TextRange(
            selStart.coerceIn(0, text.length),
            selEnd.coerceIn(0, text.length),
        ),
        composition = null,
    )
}

/** Raw numeric variant: VM owns the [TextFieldValue], digits only. */
@Composable
fun DraftIntField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    maxDigits: Int = 5,
) {
    DraftTextField(
        value = value,
        onValueChange = { onValueChange(filterDigits(it, maxDigits)) },
        label = label,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
        ),
        modifier = modifier,
    )
}
