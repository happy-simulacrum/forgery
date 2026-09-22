package com.forgery.app.core.ui

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Text field for values backed by an asynchronous store (DataStore, Room).
 *
 * Keystrokes update local state synchronously so the IME cursor never
 * drifts; the external [value] is only adopted when it differs from the
 * local text (mode switch, LoRA/Style insert, server refresh, out-of-order
 * store echo during fast input). Adoption preserves the cursor, only
 * clamping it to the new length — without this, every programmatic text
 * swap resets the selection and fast delete races the IME ("hello" ->
 * "athlloe", cursor jumps on hold-delete).
 */
@Composable
fun DraftTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    singleLine: Boolean = false,
    minLines: Int = 1,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    var lastExternal by rememberSaveable { mutableStateOf(value) }
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(value))
    }
    if (value != lastExternal) {
        lastExternal = value
        field = adoptExternal(field, value)
    }
    OutlinedTextField(
        value = field,
        onValueChange = {
            field = it
            onValueChange(it.text)
        },
        label = label?.let { { Text(it) } },
        singleLine = singleLine,
        minLines = minLines,
        keyboardOptions = keyboardOptions,
        modifier = modifier,
    )
}

/**
 * Merges an external text update into the current [TextFieldValue],
 * preserving the cursor/selection (clamped to the new length).
 * Pure — unit-tested.
 */
internal fun adoptExternal(current: TextFieldValue, external: String): TextFieldValue {
    if (current.text == external) return current
    val sel = current.selection
    val clamped = TextRange(
        sel.start.coerceIn(0, external.length),
        sel.end.coerceIn(0, external.length),
    )
    return current.copy(text = external, selection = clamped)
}

/** Numeric variant: forwards only parseable input, keeps placeholder text local. */
@Composable
fun DraftIntField(
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    maxDigits: Int = 5,
) {
    DraftTextField(
        value = value.toString(),
        onValueChange = { raw ->
            raw.filter(Char::isDigit).take(maxDigits).toIntOrNull()?.let(onValueChange)
        },
        label = label,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
        modifier = modifier,
    )
}
