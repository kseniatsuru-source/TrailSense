package com.mayushi.trailsense

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mayushi.trailsense.domain.TrackPoint
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.util.GeoPoint

@RunWith(AndroidJUnit4::class)
class MapGesture035Test {
    @Test fun actualInjectedOneFingerPanTwoFingerZoomAndRecompositionPreserveCamera() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val route=mutableStateOf(listOf(TrackPoint(49.0,-123.0),TrackPoint(49.01,-123.01),TrackPoint(49.02,-123.0)))
        val range=mutableStateOf<ClosedFloatingPointRange<Float>?>(null)
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        lateinit var map:TrackMapView
        fun find(v:View):TrackMapView? { if(v is TrackMapView)return v; if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i))?.let{return it};return null }
        scenario.onActivity { a -> a.setContent { TrailSenseTheme { TopographicTrackMap(route.value,highlightedRange=range.value) } } }
        Thread.sleep(1500)
        scenario.onActivity { map=find(it.window.decorView)!!;map.controller.setZoom(14.0);map.controller.setCenter(GeoPoint(49.01,-123.0)) }
        fun camera():Triple<Double,Double,Double> { var out=Triple(0.0,0.0,0.0);scenario.onActivity { out=Triple(map.zoomLevelDouble,map.mapCenter.latitude,map.mapCenter.longitude) };return out }
        val location=IntArray(2);scenario.onActivity { map.getLocationOnScreen(location) }
        var width=0;var height=0;scenario.onActivity { width=map.width;height=map.height }
        val x=width*.5f;val y=height*.5f
        val start=camera()
        val down=SystemClock.uptimeMillis()
        fun one(action:Int,px:Float,py:Float,t:Long) { val e=MotionEvent.obtain(down,t,action,px,py,0);e.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;instrumentation.runOnMainSync { map.dispatchTouchEvent(e) };e.recycle() }
        one(MotionEvent.ACTION_DOWN,x,y,down)
        for(i in 1..10)one(MotionEvent.ACTION_MOVE,x+12*i,y+3*i,down+20*i)
        one(MotionEvent.ACTION_UP,x+120,y+30,down+250)
        Thread.sleep(500)
        val panned=camera();assertTrue("one finger must pan",kotlin.math.abs(panned.second-start.second)+kotlin.math.abs(panned.third-start.third)>0.00001)
        assertEquals(start.first,panned.first,.001)
        val pinchDown=SystemClock.uptimeMillis()
        fun multi(action:Int,separation:Float,t:Long) {
            val properties=Array(2){i->MotionEvent.PointerProperties().apply{id=i;toolType=MotionEvent.TOOL_TYPE_FINGER}}
            val coordinates=Array(2){i->MotionEvent.PointerCoords().apply{this.x=x+(if(i==0)-1 else 1)*separation+20;this.y=y;pressure=1f;size=1f}}
            val e=MotionEvent.obtain(pinchDown,t,action,2,properties,coordinates,0,0,1f,1f,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0)
            instrumentation.runOnMainSync { map.dispatchTouchEvent(e) };e.recycle()
        }
        one(MotionEvent.ACTION_DOWN,x-120,y,pinchDown)
        multi(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),120f,pinchDown+30)
        for(i in 1..15){multi(MotionEvent.ACTION_MOVE,120f+i*20,pinchDown+40+i*30);Thread.sleep(30)}
        multi(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),420f,pinchDown+550)
        one(MotionEvent.ACTION_UP,x-400,y,pinchDown+580)
        Thread.sleep(700)
        val pinched=camera()
        assertTrue("two fingers must zoom: $panned -> $pinched",kotlin.math.abs(pinched.first-panned.first)>.1)
        // osmdroid center is quantized to world pixels; allow sub-pixel geographic rounding.
        assertEquals(panned.second,pinched.second,.00005);assertEquals(panned.third,pinched.third,.00005)
        val stable=camera()
        scenario.onActivity { range.value=.2f.. .8f };Thread.sleep(500)
        assertEquals(stable,camera())
        scenario.onActivity { route.value=listOf(TrackPoint(48.99,-123.0,inferred=true))+route.value;range.value=null };Thread.sleep(500)
        assertEquals(stable,camera())
        println("MAP035 actual UI injection: one-finger pan, two-finger zoom-only, slider/recovery redraw center+zoom exact PASS $stable")
        scenario.close()
    }
}
