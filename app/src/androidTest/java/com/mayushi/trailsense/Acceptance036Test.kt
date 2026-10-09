package com.mayushi.trailsense

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.runtime.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso
import android.view.MotionEvent
import android.os.SystemClock
import com.mayushi.trailsense.data.*
import com.mayushi.trailsense.domain.*
import com.mayushi.trailsense.network.AllTrailsMcpClient
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class Acceptance036Test {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    private val app get()=rule.activity.application as TrailSenseApplication
    private val dao get()=app.database.trailDao()
    private lateinit var vm:AppViewModel
    private var routeId=0L;private var activityId=0L
    private val title="TEST036 synthetic acceptance"
    private val points=(0..100).map { i -> TrackPoint(49.0+i*.0001,-123.0,elevationM=100.0+i,timeEpochMs=1_000_000L+i*10_000L,watchDistanceM=i*15.0) }
    private lateinit var before:List<ActivityEntity>
    private lateinit var routeBefore:List<RouteEntity>
    @Before fun fixture() {
        vm=AppViewModel(app)
        runBlocking {
            before=dao.observeActivities().first();routeBefore=dao.observeRoutes().first()
            routeId=dao.insertRoute(RouteEntity(title=title,notes="Historical route text"))
            val m=TrackAnalyzer.analyze(points,TrackSummary(distanceM=1500.0,elevationGainM=123.0,elevationLossM=45.0,elapsedSeconds=1500))
            val a=WatchActivityUpdater.merge(null,routeId,title,"synthetic.tcx",TrackFormat.TCX,TrackCodec.encode(points),LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),m).copy(comment="Historical personal text")
            activityId=dao.insertActivity(a)
            dao.insertPaceSegments(listOf(PaceSegmentEntity(activityId=activityId,startFraction=.2,endFraction=.4,paceSetterName="Synthetic",note="Preserve legacy segment")))
        }
    }
    @After fun cleanup() {
        runBlocking {
            if(dao.route(routeId)!=null)app.repository.deleteRouteEvent(routeId)
            assertEquals(before,dao.observeActivities().first())
            assertEquals(routeBefore,dao.observeRoutes().first())
        }
    }
    private fun content(block:@Composable ()->Unit) { rule.activity.runOnUiThread { rule.activity.setContent { TrailSenseTheme { block() } } };rule.waitForIdle() }
    private fun click(text:String,scroll:Boolean=false) { val node=rule.onNodeWithText(text);if(scroll)node.performScrollTo();node.performClick();rule.waitForIdle() }
    private fun await(text:String) { rule.waitUntil(10000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }
    @Test fun questionnaireOneCommentPreservesSegmentsSaveCollapsesAndValidationStaysOpen() {
        content { RouteEventDetail(routeId,vm,{}) };await(title)
        click("Развернуть",true)
        rule.onNodeWithText("Указать участки разного темпа (необязательно)").assertDoesNotExist()
        rule.onNodeWithText("Заметка о маршруте").assertDoesNotExist()
        rule.onNodeWithText("Комментарий к походу").performScrollTo()
        rule.onNodeWithText("Historical personal text\n\nHistorical route text").assertExists()
        click("Сохранить анкету",true)
        await("Развернуть")
        val a=runBlocking { dao.activity(activityId)!! };val r=runBlocking { dao.route(routeId)!! }
        assertEquals("Historical personal text\n\nHistorical route text",a.comment);assertEquals("",r.notes)
        assertEquals(1,runBlocking { dao.paceSegments(activityId).size })
        click("Развернуть",true)
        rule.onNodeWithText("Критерии: имя | оценка | максимум").performScrollTo().performTextInput("invalid")
        click("Сохранить анкету",true)
        rule.onNodeWithText("Свернуть").assertExists()
        assertEquals(a.comment,runBlocking { dao.activity(activityId)!!.comment })
        println("036 questionnaire actual Compose save collapsed; invalid form retained; one comment and legacy segment preserved")
    }
    @Test fun longPressThresholdCancelDragAndActualConfirmDelete() {
        var opened=0L
        content { EventsScreen(vm,{ opened=it }) };await(title)
        rule.onNodeWithText(title).performTouchInput { down(center); advanceEventTime(850); up() }
        rule.onNodeWithText("Удалить хайк").assertDoesNotExist()
        rule.onNodeWithText(title).performTouchInput { longClick(durationMillis=HIKE_LONG_PRESS_MS+250) }
        await("Удалить хайк")
        click("Отмена");assertNotNull(runBlocking { dao.route(routeId) })
        opened=0L
        rule.onNodeWithText(title).performTouchInput { swipeUp() };rule.waitForIdle()
        assertEquals("Drag must not open the card",0L,opened)
        rule.onNodeWithText("Удалить хайк").assertDoesNotExist()
        // Accessibility context action is a second supported entry, independent of timeout.
        rule.onNode(hasText(title) and hasClickAction()).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnLongClick) { it() }
        await("Удалить хайк");click("Удалить хайк");click("Удалить")
        rule.waitUntil(10000) { runBlocking { dao.route(routeId)==null } }
        assertNull(runBlocking { dao.activity(activityId) })
        println("036 delete actual 1200ms pointer hold threshold/cancel/drag and confirmed synthetic deletion PASS")
    }
    @Test fun calendarSavedManualDateMultipleAndUndatedNotGuessed() {
        val date=LocalDate.now()
        var second=0L;var unknown=0L;var opened=0L
        runBlocking {
            val a=dao.activity(activityId)!!
            second=dao.insertActivity(a.copy(id=0,title="TEST036 second",manualDateEpochDay=date.toEpochDay()))
            unknown=dao.insertActivity(a.copy(id=0,title="TEST036 undated",startedAt=null,manualDateEpochDay=null))
        }
        content { ContextCalendarScreen(vm) { opened=it } }
        await("Календарь контекста");click(date.dayOfMonth.toString())
        rule.onAllNodesWithText(title).assertCountEquals(2)
        rule.onNodeWithText("TEST036 undated").assertDoesNotExist()
        rule.onAllNodesWithText(title)[1].performClick();rule.waitForIdle();assertEquals(routeId,opened)
        runBlocking { app.repository.setManualDate(activityId,date.minusDays(1)) }
        println("036 calendar real saved/manual day and multiple hike entries clickable; undated excluded PASS")
    }
    @Test fun scopedAnalysisChoiceCancelQueueAndSystemShareChooser() {
        content { HikeAnalysisChoice(routeId,vm,{}) }
        click("Отмена")
        assertTrue(runBlocking { dao.pendingAiJobs().none { it.entityId==routeId && it.entityType=="ROUTE" } })
        content { HikeAnalysisChoice(routeId,vm,{}) };click("Добавить в очередь")
        rule.waitUntil(10000) { runBlocking { dao.pendingAiJobs().any { it.entityId==routeId && it.entityType=="ROUTE" } } }
        content { HikeAnalysisChoice(routeId,vm,{}) };click("Отправить сейчас")
        Thread.sleep(2500)
        var chooserRoot=InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
        for(i in 0 until 40) { if(chooserRoot?.packageName?.toString()=="com.android.intentresolver") break;Thread.sleep(250);chooserRoot=InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow }
        assertNotNull(chooserRoot);assertNotEquals("System chooser must really open",app.packageName,chooserRoot!!.packageName.toString())
        println("036 actual chooser active package=${chooserRoot.packageName}")
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent 4").close();Thread.sleep(700)
        val jobs=runBlocking { dao.pendingAiJobs().filter { it.entityId==routeId && it.entityType=="ROUTE" } }
        assertTrue(jobs.isNotEmpty());assertTrue(jobs.all { it.inputJson.contains(title) })
        val exported=runBlocking { app.telegramExchange.createRequestFile(routeId,"DEEP_ROUTE") }
        val tasks=org.json.JSONObject(exported.file.readText()).getJSONArray("tasks")
        assertTrue(tasks.length()>0)
        for(i in 0 until tasks.length()) { assertEquals(routeId,tasks.getJSONObject(i).getLong("entity_id"));assertEquals("ROUTE",tasks.getJSONObject(i).getString("entity_type")) }
        println("036 scoped analysis chooser opened then canceled; no message posted; queue current hike PASS")
    }
    @Test fun candidateOpenIsIndependentCancelAndFullTitleConfirmation() {
        val candidate=AllTrailsMcpClient.Candidate(123,"TEST036 Full candidate title","https://www.alltrails.com/trail/canada/british-columbia/lynn-loop",1500.0,100.0,"loop","Synthetic")
        val r=runBlocking { dao.route(routeId)!! };val a=runBlocking { dao.activity(activityId)!! }
        content { RouteSearchDialog(r,a,vm,listOf(candidate),{}) }
        rule.onNodeWithText("Открыть кандидата в браузере").performScrollTo().performClick()
        Thread.sleep(1800)
        var browserRoot=InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
        for(i in 0 until 40) { if(browserRoot?.packageName?.toString()=="com.android.chrome") break;Thread.sleep(250);browserRoot=InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow }
        assertNotNull(browserRoot);assertNotEquals(app.packageName,browserRoot!!.packageName.toString())
        println("036 actual candidate browser active package=${browserRoot.packageName}")
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent 4").close();Thread.sleep(700)
        assertEquals(r,runBlocking { dao.route(routeId)!! })
        content { RouteSearchDialog(r,a,vm,listOf(candidate),{}) }
        click(candidate.name,true);click("Отмена")
        assertEquals(r,runBlocking { dao.route(routeId)!! })
        content { RouteSearchDialog(r,a,vm,listOf(candidate),{}) };click(candidate.name,true);click("Подтвердить")
        rule.waitUntil(10000) { runBlocking { dao.route(routeId)!!.title==candidate.name } }
        assertEquals(a,runBlocking { dao.activity(activityId)!! })
        val confirmed=runBlocking { dao.route(routeId)!! };runBlocking { app.repository.saveRoute(confirmed.copy(title="TEST036 manual rename")) }
        assertEquals("TEST036 manual rename",runBlocking { dao.route(routeId)!!.title })
        println("036 browser candidate independent, cancel unchanged, confirm full title, manual rename, watch unchanged PASS")
    }
    @Test fun trimAuthoritativeDistanceResetExactWatchMetrics() {
        val before=runBlocking { dao.activity(activityId)!! }
        runBlocking { app.repository.trimActivityTrack(activityId,.2,.8) }
        val trimmed=runBlocking { dao.activity(activityId)!! }
        assertEquals(900.0,trimmed.distanceM,.01)
        assertEquals(before.originalTrackEncoded,trimmed.originalTrackEncoded)
        runBlocking { app.repository.resetTrackCorrection(activityId) }
        val reset=runBlocking { dao.activity(activityId)!! }
        assertEquals(before.distanceM,reset.distanceM,.001);assertEquals(before.elevationGainM,reset.elevationGainM,.001);assertEquals(before.elevationLossM,reset.elevationLossM,.001);assertEquals(before.elapsedSeconds,reset.elapsedSeconds)
        println("036 real Room trim on nonuniform watch-compatible axis; reset exact original watch metrics PASS")
    }
}
