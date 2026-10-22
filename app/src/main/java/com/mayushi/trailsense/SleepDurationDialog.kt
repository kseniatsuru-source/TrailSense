package com.mayushi.trailsense

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mayushi.trailsense.domain.SleepDurationInput

/** Approved compact two-field editor. Cancel/Back never changes the questionnaire draft. */
@Composable
fun SleepDurationDialog(value: Double?, dismiss: () -> Unit, done: (Double?) -> Unit) {
    val initial = remember { SleepDurationInput.fields(value) }
    var hours by remember { mutableStateOf(TextFieldValue(initial.first, TextRange(0, initial.first.length))) }
    var minutes by remember { mutableStateOf(TextFieldValue(initial.second, TextRange(0, initial.second.length))) }
    val hourFocus = remember { FocusRequester() }
    val minuteFocus = remember { FocusRequester() }
    var hoursFocused by remember { mutableStateOf(false) }
    var hourReady by remember { mutableStateOf(false) }
    var minutesFocused by remember { mutableStateOf(false) }
    var advanceRequested by remember { mutableStateOf(false) }
    LaunchedEffect(advanceRequested) {
        if (advanceRequested) {
            // Commit the edit before ending its input session by moving focus.
            withFrameNanos { }
            minuteFocus.requestFocus()
            advanceRequested = false
        }
    }
    val valid = SleepDurationInput.valid(hours.text, minutes.text)
    val mint = Color(0xFFA5D6B8)
    val keyboard = LocalSoftwareKeyboardController.current
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = mint, unfocusedBorderColor = Color(0xFF697C70),
        focusedContainerColor = Color(0xFF242C27), unfocusedContainerColor = Color(0xFF242C27),
        focusedTextColor = Color(0xFFE1EAE3), unfocusedTextColor = Color(0xFFE1EAE3), cursorColor = mint)
    fun next() {
        if (minutes.text.isEmpty()) minutes = TextFieldValue("00", TextRange(0, 2))
        if (hours.text.isNotEmpty()) hours = TextFieldValue(hours.text.padStart(2, '0'))
        minuteFocus.requestFocus()
    }
    ImeAlertDialog(onDismissRequest = dismiss, containerColor = Color(0xFF303833),
        title = { Text("Длительность сна") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Часы", color = if (hoursFocused) mint else Color(0xFFBFCAC3))
                        OutlinedTextField(hours, { nextValue ->
                            if (SleepDurationInput.accepts(nextValue.text)) {
                                hours = nextValue
                                if (SleepDurationInput.advanceFromHours(nextValue.text)) advanceRequested = true
                            }
                        }, Modifier.fillMaxWidth().focusRequester(hourFocus).onGloballyPositioned { hourReady = true }.onFocusChanged { state ->
                            hoursFocused = state.isFocused
                            if (state.isFocused) hours = hours.copy(selection = TextRange(0, hours.text.length))
                        }, textStyle = LocalTextStyle.current.copy(fontSize = 32.sp, textAlign = TextAlign.Center),
                            colors = fieldColors,
                            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                            keyboardActions = KeyboardActions(onNext = { next() }))
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Минуты", color = if (minutesFocused) mint else Color(0xFFBFCAC3))
                        OutlinedTextField(minutes, { nextValue ->
                            if (SleepDurationInput.accepts(nextValue.text)) minutes = nextValue
                        }, Modifier.fillMaxWidth().focusRequester(minuteFocus).onFocusChanged { state ->
                            minutesFocused = state.isFocused
                            if (state.isFocused) minutes = minutes.copy(selection = TextRange(0, minutes.text.length))
                        }, textStyle = LocalTextStyle.current.copy(fontSize = 32.sp, textAlign = TextAlign.Center),
                            colors = fieldColors,
                            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }))
                    }
                }
                Text(if (valid) "Вводи только цифры" else "Часы 0–24, минуты 0–59; не больше 24 часов",
                    color = if (valid) Color(0xFFBFCAC3) else MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = dismiss) { Text("Отмена", color = mint) }
                TextButton(onClick = { done(SleepDurationInput.value(hours.text, minutes.text)) }, enabled = valid) { Text("Готово", color = if (valid) mint else Color(0xFF697C70)) }
            }
        })
    // Dialog content attaches after its parent effect; never request an unattached target.
    LaunchedEffect(hourReady) { if (hourReady) hourFocus.requestFocus() }
}
