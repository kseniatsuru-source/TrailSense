package com.mayushi.trailsense

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Synthetic regression for focus attachment in a newly composed native dialog window. */
@RunWith(AndroidJUnit4::class)
class SleepDurationDialog041Test {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    @Test fun initialHourFocusWaitsForDialogAttachmentAndTwoFieldsAdvance() {
        var result:Double?=null
        rule.activity.runOnUiThread {rule.activity.setContent {TrailSenseTheme {SleepDurationDialog(8.0,{}, {result=it})}}}
        val fields=rule.onAllNodes(hasSetTextAction())
        rule.waitUntil(10000) {fields.fetchSemanticsNodes().size==2}
        fields[0].assertIsFocused().performTextReplacement("09")
        fields[1].assertIsFocused().performTextReplacement("35")
        rule.onNodeWithText("Готово").assertIsDisplayed().performClick()
        assertEquals(9.0+35.0/60,result!!,0.000001)
    }
    @Test fun blankMidnightInvalidMinutesImeDoneCancelAndBackPreserveDraft() {
        var result:Double?=7.0;var cancelled=0
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {rule.activity.setContent {TrailSenseTheme {SleepDurationDialog(null,{cancelled++},{result=it})}}}
        val fields=rule.onAllNodes(hasSetTextAction())
        rule.waitUntil(10000) {fields.fetchSemanticsNodes().size==2}
        fields[0].performTextReplacement("");fields[1].performTextReplacement("")
        rule.onNodeWithText("Готово").performClick();assertNull(result)
        fields[0].performTextReplacement("24");fields[1].performTextReplacement("00")
        rule.onNodeWithText("Готово").assertIsEnabled().performClick();assertEquals(24.0,result!!,0.0)
        fields[1].performTextReplacement("60");rule.onNodeWithText("Готово").assertIsNotEnabled()
        fields[1].performTextReplacement("01");rule.onNodeWithText("Готово").assertIsNotEnabled()
        fields[0].performTextReplacement("08");fields[1].performTextReplacement("05");fields[1].performImeAction()
        rule.onNodeWithText("Готово").assertIsDisplayed()
        rule.onNodeWithText("Отмена").performClick();assertEquals(1,cancelled);assertEquals(24.0,result!!,0.0)
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        androidx.test.espresso.Espresso.pressBack();rule.waitUntil(10000){cancelled==2};assertEquals(24.0,result!!,0.0)
    }
}
