package com.mayushi.trailsense

import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import android.view.WindowManager
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.DialogProperties

/** Resize the dialog's entire surface, not just its text, so commit/cancel stay above the IME. */
@Composable
internal fun ImeAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    containerColor: Color = AlertDialogDefaults.containerColor,
) {
    AlertDialog(onDismissRequest=onDismissRequest,confirmButton=confirmButton,
        modifier=modifier.statusBarsPadding().navigationBarsPadding().imePadding(),dismissButton=dismissButton,
        icon=icon,title=title,text=text?.let { body -> {
            val window=(LocalView.current.parent as? DialogWindowProvider)?.window
            SideEffect { window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) }
            body()
        } },containerColor=containerColor,
        properties=DialogProperties(decorFitsSystemWindows=false))
}
