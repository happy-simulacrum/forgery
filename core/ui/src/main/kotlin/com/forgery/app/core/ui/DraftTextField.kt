package com.forgery.app.core.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * Text field for values backed by an asynchronous store (DataStore, Room).
 *
 * Keystrokes update local state synchronously so the IME cursor never
 * drifts; the external [value] is only adopted when it changes underneath
 * the local text (mode switch, LoRA/Style insert, server refresh). Without
 * this, every keystroke round-trips through the store and the echoed value
 * races the IME, scrambling input ("hello" -> "athlloe").
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
    var local by rememberSaveable { mutableStateOf(value) }
    if (value != lastExternal) {
        lastExternal = value
        local = value
    }
    OutlinedTextField(
        value = local,
        onValueChange = {
            local = it
            onValueChange(it)
        },
        label = label?.let { { Text(it) } },
        singleLine = singleLine,
        minLines = minLines,
        keyboardOptions = keyboardOptions,
        modifier = modifier,
    )
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
