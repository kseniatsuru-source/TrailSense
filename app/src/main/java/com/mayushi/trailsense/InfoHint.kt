package com.mayushi.trailsense

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp

/** One accessible, full-touch-target explanation per section. Never hides errors or consent. */
@Composable
fun InfoHint(title: String, text: String, compactInline: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = Modifier.size(48.dp)) {
        // Keep the full independent tap target, but place the small glyph 4dp after the criterion.
        androidx.compose.foundation.layout.Box(
            Modifier.size(48.dp),
            contentAlignment = if (compactInline) androidx.compose.ui.Alignment.CenterStart else androidx.compose.ui.Alignment.Center,
        ) {
            Icon(Icons.Default.Info, contentDescription = "Информация: $title",
                modifier = if (compactInline) Modifier.padding(start = 4.dp).size(16.dp) else Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(title) },
        text = { Text(text) }, confirmButton = { TextButton(onClick = { open = false }) { Text("Понятно") } })
}
