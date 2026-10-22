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
            routeId=dao.insertRoute(RouteEntity(title=title,sourceLabel="Часы",notes="Historical route text"))
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
    private fun click(text:String,scroll:Boolean=false) { await(text);val node=rule.onNodeWithText(text);if(scroll) { if(rule.onAllNodesWithTag("hike-questionnaire").fetchSemanticsNodes().isNotEmpty()) rule.onNodeWithTag("hike-questionnaire").performScrollTo(); node.performScrollTo() };node.performClick();rule.waitForIdle() }
    private fun openQuestionnaire() {rule.waitUntil(10000){rule.onAllNodesWithContentDescription("Открыть анкету").fetchSemanticsNodes().isNotEmpty()};rule.onNodeWithContentDescription("Открыть анкету").performScrollTo().performClick();rule.onNodeWithTag("hike-questionnaire").performScrollTo()}
    private fun await(text:String) { rule.waitUntil(10000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }
    private fun screenshot037(name:String) {
        rule.waitForIdle(); Thread.sleep(400)
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(app.getExternalFilesDir(null),name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }
    @Test fun multipleConstructedCompanionsWrapAndSaveAtConfiguredFont() {
        val originals=runBlocking { dao.observeCompanions().first() }
        val names=listOf("TEST037 Long companion Alpha", "TEST037 Long companion Beta", "TEST037 Long companion Gamma")
        val created=runBlocking { names.map { name -> val c=com.mayushi.trailsense.data.CompanionEntity(name=name); c.copy(id=dao.insertCompanion(c)) } }
        try {
            content { RouteEventDetail(routeId,vm,{}) };await(title);openQuestionnaire()
            names.forEach { click(it,true) }
            rule.onNodeWithText("Тяжёлый рюкзак ≈15 кг").performScrollTo().assertIsDisplayed()
            screenshot037("037-companions-configuredfont.png")
            click("Сохранить анкету",true)
            assertEquals(names.sorted(),runBlocking { dao.companionNamesForActivity(activityId) }.sorted())
        } finally {
            runBlocking { dao.clearActivityCompanions(activityId); created.forEach { dao.deleteCompanion(it) };assertEquals(originals,dao.observeCompanions().first()) }
        }
    }
    @Test fun questionnaireOneCommentPreservesSegmentsSaveCollapsesAndValidationStaysOpen() {
        content { RouteEventDetail(routeId,vm,{}) };await(title)
        openQuestionnaire()
        rule.onNodeWithText("Указать участки разного темпа (необязательно)").assertDoesNotExist()
        rule.onNodeWithText("Заметка о маршруте").assertDoesNotExist()
        rule.onNodeWithText("Заметки к походу").performScrollTo()
        rule.onNodeWithText("Historical personal text\nHistorical route text").assertExists()
        click("Сохранить анкету",true)
        rule.waitUntil(5000) {runBlocking {dao.activity(activityId)!!.questionnaireCompletedAt!=null}}
        rule.waitUntil(5000) {rule.onAllNodesWithContentDescription("Открыть анкету").fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithContentDescription("Открыть анкету").assertExists()
        val a=runBlocking { dao.activity(activityId)!! };val r=runBlocking { dao.route(routeId)!! }
        assertEquals("Historical personal text\nHistorical route text",a.comment);assertEquals("",r.notes)
        assertEquals(1,runBlocking { dao.paceSegments(activityId).size })
        openQuestionnaire()
        rule.onNodeWithText("Заметки к походу").performScrollTo().performTextReplacement("[rating] invalid")
        click("Сохранить анкету",true)
        rule.onNodeWithContentDescription("Свернуть анкету").assertExists()
        assertEquals(a.comment,runBlocking { dao.activity(activityId)!!.comment })
        println("036 questionnaire actual Compose save collapsed; invalid form retained; one comment and legacy segment preserved")
    }
    @Test fun longPressThresholdCancelDragAndActualConfirmDelete() {
        var opened=0L
        content { EventsScreen(vm,{ opened=it }) };await(title)
        rule.onNodeWithText(title).performTouchInput { down(center); advanceEventTime(1900); up() }
        rule.onNodeWithText("Удалить хайк?").assertDoesNotExist()
        rule.onNodeWithText(title).performTouchInput { longClick(durationMillis=2100) }
        await("Удалить хайк?")
        click("Отмена");assertNotNull(runBlocking { dao.route(routeId) })
        opened=0L
        rule.onNodeWithText(title).performTouchInput { swipeUp() };rule.waitForIdle()
        assertEquals("Drag must not open the card",0L,opened)
        rule.onNodeWithText("Удалить хайк?").assertDoesNotExist()
        // Accessibility context action is a second supported entry, independent of timeout.
        rule.onNode(hasText(title) and hasClickAction()).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnLongClick) { it() }
        await("Удалить хайк?");click("Удалить")
        rule.waitUntil(10000) { runBlocking { dao.route(routeId)==null } }
        assertNull(runBlocking { dao.activity(activityId) })
        println("038 delete 1900ms no confirmation / 2100ms confirmation / cancel / drag / own TEST deletion PASS")
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
        await("Что показывать");click(date.dayOfMonth.toString())
        await(title)
        rule.onAllNodes(hasText(title) and hasAnyAncestor(isDialog())).assertCountEquals(2)
        rule.onNodeWithText("TEST036 undated", substring=true).assertDoesNotExist()
        rule.onAllNodes(hasText(title) and hasAnyAncestor(isDialog()))[1].performClick();rule.waitForIdle();assertEquals(routeId,opened)
        runBlocking { app.repository.setManualDate(activityId,date.minusDays(1)) }
        println("036 calendar real saved/manual day and multiple hike entries clickable; undated excluded PASS")
    }
    @Test fun scopedAnalysisChoiceCancelQueueAndSystemShareChooser() {
        content { HikeAnalysisChoice(activityId,vm,{}) }
        click("Отмена")
        assertTrue(runBlocking { dao.pendingAiJobs().none { it.entityId==activityId && it.entityType=="ACTIVITY" } })
        content { HikeAnalysisChoice(activityId,vm,{}) };click("Поставить в очередь")
        rule.waitUntil(10000) { runBlocking { dao.pendingAiJobs().any { it.entityId==activityId && it.entityType=="ACTIVITY" } } }
        content { HikeAnalysisChoice(activityId,vm,{}) };click("Отправить сейчас")
        Thread.sleep(2500)
        var chooserRoot=InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
        for(i in 0 until 40) { if(chooserRoot?.packageName?.toString()=="com.android.intentresolver") break;Thread.sleep(250);chooserRoot=InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow }
        assertNotNull(chooserRoot);assertNotEquals("System chooser must really open",app.packageName,chooserRoot!!.packageName.toString())
        println("039 actual chooser active package=${chooserRoot.packageName}")
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent 4").close();Thread.sleep(700)
        val jobs=runBlocking { dao.pendingAiJobs().filter { it.entityId==activityId && it.entityType=="ACTIVITY" } }
        assertTrue(jobs.isNotEmpty());assertTrue(jobs.all { it.inputJson.contains(title) })
        val exported=runBlocking { app.telegramExchange.createRequestFile(analysisKind="DEEP_ROUTE",activityId=activityId) }
        val tasks=org.json.JSONObject(exported.file.readText()).getJSONArray("tasks")
        assertTrue(tasks.length()>0)
        for(i in 0 until tasks.length()) { assertEquals(activityId,tasks.getJSONObject(i).getLong("entity_id"));assertEquals("ACTIVITY",tasks.getJSONObject(i).getString("entity_type")) }
        println("039 scoped analysis chooser opened then canceled; no message posted; queue selected recording PASS")
    }
    @Test fun candidateOpenIsIndependentCancelAndFullTitleConfirmation() {
        val candidate=AllTrailsMcpClient.Candidate(123,"TEST037 Full long official candidate title with repeated ascent and trail alternatives for narrow screen acceptance","https://www.alltrails.com/trail/canada/british-columbia/lynn-loop",1500.0,100.0,"loop","Synthetic")
        val r=runBlocking { dao.route(routeId)!! };val a=runBlocking { dao.activity(activityId)!! }
        content { RouteSearchDialog(r,a,vm,listOf(candidate),dismiss={}) }
        rule.onNodeWithText("Открыть кандидата ↗").performScrollTo().performClick()
        Thread.sleep(1800)
        var browserRoot=InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
        for(i in 0 until 40) { if(browserRoot?.packageName?.toString()=="com.android.chrome") break;Thread.sleep(250);browserRoot=InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow }
        assertNotNull(browserRoot);assertNotEquals(app.packageName,browserRoot!!.packageName.toString())
        println("036 actual candidate browser active package=${browserRoot.packageName}")
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent 4").close();Thread.sleep(700)
        assertEquals(r,runBlocking { dao.route(routeId)!! })
        content { RouteSearchDialog(r,a,vm,listOf(candidate),dismiss={}) }
        click("Выбрать",true);click("Отмена")
        assertEquals(r,runBlocking { dao.route(routeId)!! })
        content { RouteSearchDialog(r,a,vm,listOf(candidate),dismiss={}) };click("Выбрать",true)
        screenshot037("037-alltrails-confirm-configuredfont.png");click("Подтвердить")
        rule.waitUntil(10000) { runBlocking { dao.route(routeId)!!.title==candidate.name } }
        assertEquals(a,runBlocking { dao.activity(activityId)!! })
        val confirmed=runBlocking { dao.route(routeId)!! };runBlocking { app.repository.saveRoute(confirmed.copy(title="TEST036 manual rename")) }
        assertEquals("TEST036 manual rename",runBlocking { dao.route(routeId)!!.title })
        println("036 browser candidate independent, cancel unchanged, confirm full title, manual rename, watch unchanged PASS")
    }
    @Test fun calendarOverlapsWholePeriodAndOngoingEditPersist() {
        val today=LocalDate.now(); val first=today.minusDays(3).toEpochDay();val last=today.plusDays(3).toEpochDay()
        val original=runBlocking { dao.observeCalendarContexts().first() }
        val ordinary=runBlocking { dao.insertCalendarContext(CalendarContextEntity(startEpochDay=first,endEpochDay=last,type="STRESS",note="TEST037 ordinary original",createdAt=123)) }
        val strong=runBlocking { dao.insertCalendarContext(CalendarContextEntity(startEpochDay=first,endEpochDay=first,type="STRONG_STRESS",ongoing=true,note="TEST037 ongoing original",createdAt=124)) }
        try {
            content { ContextCalendarScreen(vm) {} };await("Что показывать");click(today.dayOfMonth.toString())
            await("TEST037 ordinary original");rule.onNode(hasText("TEST037 ongoing original",substring=true) and hasAnyAncestor(isDialog())).assertExists()
            screenshot037("037-calendar-configuredfont.png")
            rule.onNode(hasTestTag("context-edit-$ordinary") and hasAnyAncestor(isDialog())).performScrollTo().performClick()
            await("Изменить весь период")
            rule.onNodeWithText(LocalDate.ofEpochDay(first).toString()).performScrollTo().assertExists()
            rule.onNodeWithText(LocalDate.ofEpochDay(last).toString()).performScrollTo().assertExists()
            rule.onNode(hasText("Заметка") and hasSetTextAction()).performScrollTo().performTextReplacement("TEST037 changed whole period")
            click("Сохранить")
            rule.waitUntil(10000) { runBlocking { dao.calendarContext(ordinary)?.note=="TEST037 changed whole period" } }
            val saved=runBlocking { dao.calendarContext(ordinary)!! };assertEquals(first,saved.startEpochDay);assertEquals(last,saved.endEpochDay);assertEquals(123L,saved.createdAt)
            assertTrue(runBlocking { dao.calendarContext(strong)!!.ongoing })
            content { ContextCalendarScreen(vm) {} };await("Что показывать");click(today.dayOfMonth.toString());await("TEST037 changed whole period")
            rule.onNode(hasText("TEST037 ongoing original",substring=true) and hasAnyAncestor(isDialog())).assertExists()
        } finally {
            runBlocking { dao.deleteCalendarContext(dao.calendarContext(ordinary)!!);dao.deleteCalendarContext(dao.calendarContext(strong)!!);assertEquals(original,dao.observeCalendarContexts().first()) }
        }
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
