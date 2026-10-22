package com.mayushi.trailsense

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp

/** Preserve full Russian tab names at narrow/large-font widths, not silently clipped glyphs. */
@Composable internal fun BottomTabLabel(title:String) {
    val measurer=rememberTextMeasurer()
    val density=LocalDensity.current
    val style=MaterialTheme.typography.labelSmall
    BoxWithConstraints {
        val width=with(density){maxWidth.toPx()}
        val size=listOf(11,10,9,8).firstOrNull {candidate->
            measurer.measure(title,style.copy(fontSize=candidate.sp),softWrap=false,maxLines=1).size.width<=width
        } ?: 8
        Text(title,maxLines=1,softWrap=false,style=style.copy(fontSize=size.sp),modifier=Modifier.testTag("bottom-tab-$title"))
    }
}
