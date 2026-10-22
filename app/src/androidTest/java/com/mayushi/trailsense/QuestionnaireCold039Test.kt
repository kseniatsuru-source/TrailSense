package com.mayushi.trailsense

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mayushi.trailsense.data.*
import com.mayushi.trailsense.domain.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

/** Explicit two-process protocol; do not batch stages or recapture a pending baseline. */
@RunWith(AndroidJUnit4::class)
class QuestionnaireCold039Test {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    private val app get()=rule.activity.application as TrailSenseApplication
    private val dao get()=app.database.trailDao()
    private val state get()=File(app.getExternalFilesDir(null),"questionnaire-cold039-owned.json")
    private fun open(routeId:Long) {
        val vm=AppViewModel(app)
        rule.activity.runOnUiThread{rule.activity.setContent{TrailSenseTheme{TrailSenseApp(null,vm)}}}
        rule.waitUntil(15000){rule.onAllNodesWithText("TEST039 cold saved questionnaire").fetchSemanticsNodes().isNotEmpty()}
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("TEST039 cold saved questionnaire"))
        rule.onNodeWithText("TEST039 cold saved questionnaire").performClick()
        rule.waitUntil(15000){rule.onAllNodesWithContentDescription("Открыть анкету").fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithContentDescription("Открыть анкету").performScrollTo().performClick()
    }
    @Test fun prepareSavedNullDraftCollapseAcrossProcess() {
        assertFalse("Never overwrite pending ownership",state.exists())
        val route=runBlocking{dao.insertRoute(RouteEntity(title="TEST039 cold saved questionnaire"))}
        val points=listOf(TrackPoint(49.0,-123.0,timeEpochMs=1700000000000),TrackPoint(49.001,-123.0,timeEpochMs=1700000100000))
        val id=runBlocking{dao.insertActivity(ActivityEntity(routeId=route,title="watch unchanged",sourceFileName="constructed",sourceFormat="TCX",trackEncoded=TrackCodec.encode(points),startedAt=1700000000000,distanceM=100.0,elevationGainM=0.0,elevationLossM=0.0,descentEffort=3,sleepHours=8.0,comment="Exact original prose [legacy] → заметки"))}
        state.writeText(JSONObject().put("routeId",route).put("activityId",id).put("preparePid",android.os.Process.myPid()).toString())
        open(route)
        rule.onNodeWithTag("hike-questionnaire").performScrollTo()
        rule.onNodeWithContentDescription("Спуск: 3").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Свернуть анкету").performClick()
        rule.onNodeWithContentDescription("Открыть анкету").performClick()
        rule.onNodeWithTag("hike-questionnaire").performScrollTo()
        rule.onNodeWithContentDescription("Спуск: 3").performScrollTo().assertExists()
        rule.onNodeWithText("Да").performScrollTo().performClick().assertIsSelected()
        rule.onNodeWithText("Да").performClick().assertIsNotSelected()
        rule.onNodeWithText("Сохранить анкету").performScrollTo().performClick()
        rule.waitUntil(15000){runBlocking{dao.activity(id)?.questionnaireCompletedAt!=null}}
        val saved=runBlocking{dao.activity(id)!!};assertNull(saved.descentEffort);assertEquals("Exact original prose [legacy] → заметки",saved.comment)
        rule.waitUntil(10000){rule.onAllNodesWithContentDescription("Открыть анкету").fetchSemanticsNodes().isNotEmpty()}
        assertEquals("NA",RatingApplicability.states(saved.questionnaireHistory)["Спуск"])
        println("039 saved null/draft collapse staged in real full app pid=${android.os.Process.myPid()}")
    }
    @Test fun coldReadbackSavedQuestionnaireAndOwnedCleanup() {
        val s=JSONObject(state.readText());val route=s.getLong("routeId");val id=s.getLong("activityId")
        try {
            assertNotEquals("Must be different OS process",s.getInt("preparePid"),android.os.Process.myPid())
            val saved=runBlocking{dao.activity(id)!!};assertNull(saved.descentEffort);assertNotNull(saved.questionnaireCompletedAt)
            assertEquals("Exact original prose [legacy] → заметки",saved.comment);assertEquals("NA",RatingApplicability.states(saved.questionnaireHistory)["Спуск"])
            open(route)
            rule.onNodeWithTag("hike-questionnaire").performScrollTo()
            rule.onNodeWithContentDescription("Спуск: 3").performScrollTo().assertIsDisplayed()
            rule.onNodeWithContentDescription("Свернуть анкету").assertIsDisplayed()
            println("039 real OS cold saved questionnaire/null/notes reload pid=${android.os.Process.myPid()} PASS")
        } finally {
            runBlocking{app.repository.deleteRouteEvent(route);assertNull(dao.activity(id));assertNull(dao.route(route))}
            state.delete()
        }
    }
}
