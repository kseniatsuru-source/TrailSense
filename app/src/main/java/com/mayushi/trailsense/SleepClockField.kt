package com.mayushi.trailsense

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import com.mayushi.trailsense.domain.SleepClock

@Composable
fun SleepClockField(label: String, value: Double?, valid: (Boolean) -> Unit = {}, set: (Double?) -> Unit) {
    var text by remember(value) { mutableStateOf(SleepClock.format(value)) }
    val error = text.isNotBlank() && SleepClock.parse(text) == null
    OutlinedTextField(text, {
        text = it
        val parsed = SleepClock.parse(it)
        valid(it.isBlank() || parsed != null)
        if (it.isBlank() || parsed != null) set(parsed)
    }, Modifier.fillMaxWidth(), label = { Text("$label · ЧЧ:мм") }, singleLine = true,
        isError = error, supportingText = { if(error) Text("Укажи ЧЧ:мм, например 9:04; минуты 00–59") })
}
