package com.mayushi.trailsense

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Questionnaire-only compact controls; historical/general RatingField stays unchanged. */
@Composable
internal fun ApplicableRating(label: String, value: Int?, states: MutableMap<String, String>, set: (Int?) -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary.copy(alpha = .06f), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // fill=false keeps short criteria and their help together; long criteria wrap, never ellipsize.
            Text(label, Modifier.weight(1f, fill = false))
            RatingExplanation(label)?.let { InfoHint(label, it, compactInline = true) }
        }
        Row {
            (1..5).forEach { score ->
                IconButton(
                    onClick = {
                        val rating = questionnaireRatingAfterTap(value, score)
                        states[label] = if (rating == null) { if(label in listOf("Спуск","Техничность","Экспозиция")) "UNANSWERED" else "NA" } else "ANSWERED"
                        set(rating)
                    },
                    modifier = Modifier.size(48.dp).semantics { selected = value == score },
                ) {
                    Icon(
                        if ((value ?: 0) >= score) Icons.Default.Circle else Icons.Default.RadioButtonUnchecked,
                        contentDescription = "$label: $score",
                        modifier = Modifier.size(16.dp),
                        tint = if ((value ?: 0) >= score) Coral else Color.Gray,
                    )
                }
            }
        }
    }
}

// Re-tapping the selected score explicitly clears it, retaining the existing nullable/NA audit contract.
internal fun questionnaireRatingAfterTap(value: Int?, score: Int): Int? = if (value == score) null else score
