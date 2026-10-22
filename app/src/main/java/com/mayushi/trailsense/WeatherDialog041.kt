package com.mayushi.trailsense

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mayushi.trailsense.domain.WeatherContext

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun WeatherDialog041(current: WeatherContext, dismiss: () -> Unit, save: (WeatherContext) -> Unit) {
    var felt by remember { mutableStateOf(current.felt) }
    var conditions by remember { mutableStateOf(current.conditions) }
    var temperature by remember { mutableStateOf(current.temperatureC?.let { if(it == it.toInt().toDouble()) it.toInt().toString() else it.toString() } ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    ImeAlertDialog(onDismissRequest = dismiss, title = { Text("Погода") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Ощущалась как")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { WeatherContext.FELT.forEach { value ->
                FilterChip(value in felt, { felt = if(value in felt) felt - value else felt + value }, { Text(value) })
            } }
            Text("Условия")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { WeatherContext.CONDITIONS.forEach { value ->
                FilterChip(value in conditions, { conditions = if(value in conditions) conditions - value else conditions + value }, { Text(value) })
            } }
            OutlinedTextField(temperature, { temperature = it; error = null }, Modifier.fillMaxWidth(), label = { Text("Температура, °C") },
                // Compose Decimal requests an unsigned Android number editor. Text keeps '-' available on IMEs.
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), singleLine = true,
                trailingIcon = { if(temperature.isNotEmpty()) IconButton(onClick = { temperature = ""; error = null }) { Icon(Icons.Default.Clear, "Очистить температуру") } })
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = {
        try { save(WeatherContext(felt, conditions, WeatherContext.temperature(temperature))) }
        catch(e: IllegalArgumentException) { error = e.message }
    }) { Text("Готово") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Отмена") } })
}
