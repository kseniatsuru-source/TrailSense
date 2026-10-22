package com.mayushi.trailsense

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.ViewAction
import androidx.test.espresso.UiController
import android.view.View
import android.widget.DatePicker
import org.hamcrest.Matcher
import com.mayushi.trailsense.data.*
import com.mayushi.trailsense.domain.*
import com.mayushi.trailsense.exchange.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.json.JSONArray
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.LocalDate
import java.io.File

/** Private local acceptance: exclusively constructed records, no inherited fixture paths. */
@RunWith(AndroidJUnit4::class)
class Review039Test {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    private val app get()=rule.activity.application as TrailSenseApplication
    private val dao get()=app.database.trailDao()
    private lateinit var vm:AppViewModel
    private lateinit var before:List<ActivityEntity>
    private lateinit var routesBefore:List<RouteEntity>
    private val owned=mutableListOf<Long>()
    private var routeId=0L
    private var oldId=0L
    private var newId=0L
    @Before fun setup()=runBlocking {
        vm=AppViewModel(app);before=dao.observeActivities().first();routesBefore=dao.observeRoutes().first()
        routeId=dao.insertRoute(RouteEntity(title="TEST039 independent review"));owned+=routeId
        fun a(date:String,elapsed:Long,effort:Int):ActivityEntity {
            val start=LocalDate.parse(date).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            val p=(0..200).map{i->TrackPoint(49.0+i*.00003,-123.0,elevationM=200.0+i*3,timeEpochMs=start+i*5000L)}
            return ActivityEntity(routeId=routeId,title="watch source title",sourceFileName="TEST039 constructed.tcx",sourceFormat="TCX",trackEncoded=TrackCodec.encode(p),startedAt=start,distanceM=elapsed.toDouble(),elevationGainM=600.0,elevationLossM=0.0,elapsedSeconds=elapsed,overallEffort=effort,sleepHours=effort+4.0,comment="context-$effort",descentEffort=3,updatedAt=start)
        }
        oldId=dao.insertActivity(a("2026-03-31",2000,1));newId=dao.insertActivity(a("2026-06-30",9000,5))
    }
    @After fun cleanup()=runBlocking {
        owned.forEach{app.repository.deleteRouteEvent(it)}
        assertEquals(before,dao.observeActivities().first());assertEquals(routesBefore,dao.observeRoutes().first())
    }
    private fun content(block:@Composable ()->Unit) {rule.activity.runOnUiThread{rule.activity.setContent{TrailSenseTheme{block()}}};rule.waitForIdle()}
    private fun screenshot(name:String) {
        val out=File(app.getExternalFilesDir(null),"review039").apply{mkdirs()}
        val b=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(out,name).outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()
    }
    @Test fun historicalSelectedQueueShareCacheAndResponseExactIdentity() {
        val old=runBlocking{dao.activity(oldId)!!};val latest=runBlocking{dao.activity(newId)!!};val route=runBlocking{dao.route(routeId)!!}
        val originalJobs=runBlocking{dao.pendingAiJobs().map{it.id}.toSet()}
        content {RouteEventDetail(routeId,vm,{},selectedRecordingId=oldId)}
        rule.waitUntil(15000){rule.onAllNodesWithTag("recording-detail-$oldId").fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithText("Отправить на анализ").performScrollTo().performClick()
        rule.onNodeWithText("Отмена").performClick()
        assertEquals(originalJobs,runBlocking{dao.pendingAiJobs().map{it.id}.toSet()})
        rule.onNodeWithText("Отправить на анализ").performClick();rule.onNodeWithText("Поставить в очередь").performClick()
        rule.waitUntil(15000){runBlocking{dao.pendingAiJobs().any{it.entityType=="ACTIVITY" && it.entityId==oldId}}}
        runBlocking {vm.requestRecordingAnalysis(oldId).join();vm.requestRecordingAnalysis(newId).join()}
        val jobs=runBlocking{dao.pendingAiJobs()}.filter{it.id !in originalJobs}
        assertEquals(2,jobs.size)
        val job=jobs.single{it.entityId==oldId};assertEquals("ACTIVITY",job.entityType)
        val input=JSONObject(job.inputJson).getJSONObject("activity")
        assertEquals(oldId,input.getLong("id"));assertEquals(2000,input.getInt("elapsed_s"));assertEquals(1,input.getInt("effort"));assertEquals(5.0,input.getDouble("sleep_hours"),0.0);assertEquals(old.startedAt,input.getLong("started_at_ms"))
        var chooser:Intent?=null
        val capture=object:ContextWrapper(rule.activity) {override fun startActivity(intent:Intent){chooser=intent}}
        runBlocking{vm.sendCurrentHike(capture,oldId).join()}
        assertEquals(Intent.ACTION_CHOOSER,chooser?.action)
        val share=chooser!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        val uri=share.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)!!
        val request=JSONObject(app.contentResolver.openInputStream(uri)!!.bufferedReader().use{it.readText()})
        val tasks=request.getJSONArray("tasks");assertEquals(1,tasks.length());assertEquals(job.id,tasks.getJSONObject(0).getLong("job_id"))
        val result=JSONObject().put("job_id",job.id).put("input_hash",job.inputHash).put("status","ok").put("response",JSONObject().put("summary","TEST039 historical response").put("route_facts",JSONObject.NULL).put("activity_facts",JSONObject.NULL))
        val response=JSONObject().put("protocol",TelegramExchangeProtocol.PROTOCOL).put("schema_version",1).put("direction","analysis_response").put("request_id",request.getString("request_id")).put("results",JSONArray().put(result))
        val file=File(app.cacheDir,"TEST039-response.json").apply{writeText(response.toString())}
        try {runBlocking{assertEquals(1,app.telegramExchange.importResponse(android.net.Uri.fromFile(file)).imported)}} finally{file.delete()}
        assertEquals(old,runBlocking{dao.activity(oldId)});assertEquals(latest,runBlocking{dao.activity(newId)});assertEquals(route,runBlocking{dao.route(routeId)})
        rule.waitUntil(15000){rule.onAllNodesWithText("TEST039 historical response").fetchSemanticsNodes().isNotEmpty()}
        content {RouteEventDetail(routeId,vm,{},selectedRecordingId=newId)}
        rule.waitUntil(15000){rule.onAllNodesWithTag("recording-detail-$newId").fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithText("TEST039 historical response").assertDoesNotExist()
        assertNull(runBlocking{dao.observeLatestAi("ACTIVITY",newId,"DEEP_ROUTE").first()})
        println("039 selected historical ACTIVITY: divergent date/watch/questionnaire, native queue/cancel/dedupe, captured actual share JSON, response/cache isolated; latest+route exact unchanged")
    }
    @Test fun chartEveryPeriodCustomDatesAndSameDayTapValues() {
        val template=runBlocking{dao.activity(oldId)!!}
        val dates=listOf("2024-01-01","2025-06-30","2026-03-31","2026-05-31","2026-06-30","2026-06-30")
        val rows=dates.mapIndexed{i,d->AutomaticRepeatRoutes.Row(template.copy(id=i+1L,manualDateEpochDay=LocalDate.parse(d).toEpochDay()),600+i*600L,0,200)}
        content {Scaffold {pad->Column(Modifier.padding(pad).verticalScroll(rememberScrollState())){RepeatDynamicsChart(rows,true)}}}
        rule.onNodeWithText("Динамика времени общего подъёма · минуты").assertIsDisplayed()
        fun choose(p:String){rule.onNodeWithTag("chart-period").performScrollTo().performClick();rule.onAllNodesWithText(p).onLast().performClick()}
        fun bounds(first:String){rule.onNodeWithTag("chart-first-date").assertTextEquals(first);rule.onNodeWithTag("chart-last-date").assertTextEquals("2026-06-30")}
        bounds("2024-01-01")
        choose("Месяц");bounds("2026-05-31")
        choose("Сезон");bounds("2026-03-31")
        choose("Год");bounds("2025-06-30")
        choose("Всё");bounds("2024-01-01")
        val chart=rule.onNodeWithTag("repeat-time-chart").performScrollTo()
        chart.performTouchInput{click(Offset(width*.96f,height*(1f-50f/60f)))}
        rule.onNodeWithTag("chart-selected-value").assertTextEquals("${recordingDate(rows[4].activity)} · ${recordingDuration(rows[4].seconds)}")
        chart.performTouchInput{click(Offset(width*.96f,1f))}
        rule.onNodeWithTag("chart-selected-value").assertTextEquals("${recordingDate(rows[5].activity)} · ${recordingDuration(rows[5].seconds)}")
        choose("Период")
        fun setDate(old:String,date:LocalDate) {
            rule.onNode(hasText(old) and hasClickAction()).performScrollTo().performClick()
            onView(isAssignableFrom(DatePicker::class.java)).perform(object:ViewAction {
                override fun getConstraints():Matcher<View> = isAssignableFrom(DatePicker::class.java)
                override fun getDescription()="Set native date picker"
                override fun perform(controller:UiController,view:View){(view as DatePicker).updateDate(date.year,date.monthValue-1,date.dayOfMonth);controller.loopMainThreadUntilIdle()}
            })
            onView(withId(android.R.id.button1)).perform(click())
        }
        setDate("2024-01-01",LocalDate.parse("2026-03-31"));setDate("2026-06-30",LocalDate.parse("2026-05-31"))
        rule.onNodeWithTag("chart-first-date").assertTextEquals("2026-03-31");rule.onNodeWithTag("chart-last-date").assertTextEquals("2026-05-31")
        setDate("2026-03-31",LocalDate.parse("2026-06-30"))
        rule.onNodeWithText("Начало периода должно быть не позже конца").assertExists()
        screenshot("chart-periods-custom.png")
        println("039 native chart month/season/year/all/custom date pickers+inclusive endpoints+same-day distinct tap values PASS")
    }
    @Test fun missingHrCadenceAndWholeWatchVsAscentLabels() {
        val prefs=RepeatRoutePreferences(app);val original=prefs.enabled;prefs.enabled=true
        try {
            content {AutomaticRepeatScreen(vm,routeId){}}
            rule.waitUntil(20000){rule.onAllNodesWithText("2 прохождения · общий подъём").fetchSemanticsNodes().isNotEmpty()}
            rule.onNode(hasScrollAction()).performScrollToNode(hasText("Время часов всего хайка: ${recordingDuration(2000)}"))
            rule.onNodeWithText("Время часов всего хайка: ${recordingDuration(2000)}").assertIsDisplayed()
            rule.onAllNodesWithText("Пульс общего подъёма: нет измерений")[0].assertExists()
            rule.onAllNodesWithText("Каденс участка: нет измерений")[0].assertExists()
            rule.onNodeWithText("Δ пульс",substring=true).assertDoesNotExist();rule.onNodeWithText("Δ каденс",substring=true).assertDoesNotExist()
        } finally {prefs.enabled=original}
    }
    @Test fun fullAppMapHistoricalToolbarUsesDisplayedRecording() {
        content {TrailSenseApp(null,vm)}
        rule.onNodeWithText("Карта").performClick()
        var map:TrackMapView?=null
        fun find(v:android.view.View):TrackMapView? {if(v is TrackMapView)return v;if(v is android.view.ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i))?.let{return it};return null}
        rule.waitUntil(20000){rule.activity.runOnUiThread{map=find(rule.activity.window.decorView)};map!=null && map!!.overlays.filterIsInstance<org.osmdroid.views.overlay.Polyline>().size>=2}
        rule.activity.runOnUiThread{map!!.controller.setZoom(15.0);map!!.controller.setCenter(org.osmdroid.util.GeoPoint(49.003,-123.0))}
        rule.waitForIdle();Thread.sleep(500)
        rule.activity.runOnUiThread {
            val point=map!!.projection.toPixels(org.osmdroid.util.GeoPoint(49.003,-123.0),null)
            val now=android.os.SystemClock.uptimeMillis()
            listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP).forEachIndexed{i,action->val event=android.view.MotionEvent.obtain(now,now+i*70L,action,point.x.toFloat(),point.y.toFloat(),0);map!!.dispatchTouchEvent(event);event.recycle()}
        }
        rule.waitUntil(15000){rule.onAllNodesWithTag("map-open-$oldId").fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithTag("map-open-$oldId").performScrollTo().performClick()
        rule.waitUntil(15000){rule.onAllNodesWithTag("recording-detail-$oldId").fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithContentDescription("Меню хайка").performClick()
        rule.onNode(hasText("Отправить на анализ") and hasAnyAncestor(isPopup())).performClick()
        rule.onNodeWithText("Поставить в очередь").performClick()
        rule.waitUntil(15000){runBlocking{dao.pendingAiJobs().any{it.entityType=="ACTIVITY" && it.entityId==oldId}}}
        assertTrue(runBlocking{dao.pendingAiJobs().none{it.entityType=="ACTIVITY" && it.entityId==newId}})
        println("039 full app native map historical selection -> toolbar -> ACTIVITY selected ID PASS")
    }
    @Test fun fullApplicationCalendarScrollAndQuestionnaireScaffoldClipping() {
        content {TrailSenseApp(null,vm)}
        fun glyphs(node:SemanticsNodeInteraction,label:String) {
            val layouts=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            node.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult){it(layouts)}
            val l=layouts.single();assertEquals(label,1,l.lineCount);assertFalse(label,l.isLineEllipsized(0))
            val right=label.indices.maxOf{l.getBoundingBox(it).right}
            assertTrue("$label glyphs clipped at ${l.size.width}, actual right $right",right<=l.size.width+1f)
        }
        listOf("Хайки","Карта","Календарь","Настройки").forEach{glyphs(rule.onNodeWithTag("bottom-tab-$it",useUnmergedTree=true).assertIsDisplayed(),it)}
        rule.onNodeWithText("Календарь").performClick()
        rule.onNodeWithText("Вс").assertIsDisplayed()
        val weekdayBefore=rule.onNodeWithText("Вс").fetchSemanticsNode().boundsInRoot.top
        val scroll=rule.onNode(hasScrollAction()).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange]
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Это календарь назначенной схемы, не медицинская рекомендация."))
        rule.onNodeWithText("Это календарь назначенной схемы, не медицинская рекомендация.").assertIsDisplayed()
        if(scroll.maxValue()>0f) {
            rule.onNode(hasScrollAction()).performTouchInput{swipeUp()}
            val weekdays=rule.onAllNodesWithText("Вс").fetchSemanticsNodes()
            assertTrue("Week header must move with whole-page scroll, not stick in tiny panel",weekdays.isEmpty() || weekdays.single().boundsInRoot.top<weekdayBefore)
        }
        screenshot("full-app-calendar-scroll.png")
        rule.onNodeWithText("Хайки").performClick()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("TEST039 independent review"))
        rule.onNodeWithText("TEST039 independent review").performClick()
        rule.waitUntil(15000){rule.onAllNodesWithContentDescription("Открыть анкету").fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithContentDescription("Открыть анкету").performScrollTo().performClick()
        rule.onNodeWithTag("hike-questionnaire").performScrollTo()
        rule.onNodeWithContentDescription("Свернуть анкету").assertIsDisplayed()
        rule.onNodeWithContentDescription("Как я оцениваю сон: 5").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Выспалась?").assertIsDisplayed();rule.onNodeWithText("Да").assertIsDisplayed();rule.onNodeWithText("Нет").assertIsDisplayed()
        listOf("Выспалась?","Да","Нет").forEach{glyphs(rule.onNodeWithText(it),it)}
        val header=rule.onNodeWithContentDescription("Свернуть анкету").fetchSemanticsNode().boundsInRoot
        val root=rule.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue(header.top>=root.top && header.bottom<=root.bottom)
        screenshot("full-app-questionnaire-scaffold.png")
        println("039 actual TrailSenseApp Scaffold full-page calendar scroll and compact sleep/header no offscreen clipping")
    }
}
