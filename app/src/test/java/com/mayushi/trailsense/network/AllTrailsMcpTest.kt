package com.mayushi.trailsense.network

import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import com.mayushi.trailsense.domain.TrackPoint

class AllTrailsMcpTest {
    @Test fun routeValidation() {
        assertTrue(AllTrailsMcpClient.validRouteUrl("https://www.alltrails.com/en-gb/trail/canada/bc/example?x=1"))
        for(url in listOf("https://alltrails.com.evil/trail/a/b/c","http://alltrails.com/trail/a/b/c","https://alltrails.com/profile/person","https://evil@alltrails.com/trail/a/b/c")) assertFalse(url,AllTrailsMcpClient.validRouteUrl(url))
    }
    @Test fun structuredNullableAndDedupe() {
        val row=JSONObject().put("id",123).put("name","Nearby").put("alltrails_web_url","https://alltrails.com/trail/a/b/c").put("length_meters",JSONObject.NULL)
        val result=JSONObject().put("trails",org.json.JSONArray().put(row).put(row))
        val decoded=AllTrailsMcpClient.decode(JSONObject().put("result",JSONObject().put("structuredContent",result)).toString())
        val candidates=AllTrailsMcpClient.candidates(decoded)
        assertEquals(1,candidates.size); assertNull(candidates.first().length)
    }
    @Test fun fallbackAndSse() {
        val response=JSONObject().put("result",JSONObject().put("content",org.json.JSONArray().put(JSONObject().put("type","text").put("text","{\"trails\":[]}")))).toString()
        assertTrue(AllTrailsMcpClient.candidates(AllTrailsMcpClient.decode("event: message\ndata: $response\n\n")).isEmpty())
    }
    @Test fun protocolErrorDistinctFromEmpty() {
        for(s in listOf("{\"error\":{\"code\":-1}}","{\"result\":{\"isError\":true}}","invalid")) {
            try { AllTrailsMcpClient.decode(s); fail("Must not treat error as empty candidates") } catch(_: AllTrailsMcpClient.Unavailable) { }
        }
    }
    @Test fun syntheticHttp429StopsWithoutRetry() = kotlinx.coroutines.runBlocking {
        var requests=0
        val http=okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(429).message("rate limited").header("Retry-After","60").body("".let { okhttp3.ResponseBody.create(null,it) }).build()
        }.build()
        try { AllTrailsMcpClient(http).search(AllTrailsMcpClient.Area(49.0,-123.1,49.1,-123.0),10000.0,500.0);fail("429 is unavailable, not empty") } catch(e: AllTrailsMcpClient.Unavailable) { assertTrue(e.message!!.contains("60")) }
        assertEquals(1,requests)
    }
    @Test fun syntheticCancellationCancelsActualOkHttpCall() = kotlinx.coroutines.runBlocking {
        val started=java.util.concurrent.CountDownLatch(1)
        var call: okhttp3.Call?=null
        val http=okhttp3.OkHttpClient.Builder().eventListener(object: okhttp3.EventListener() { override fun callStart(c: okhttp3.Call) { call=c } }).addInterceptor { chain ->
            started.countDown();Thread.sleep(250)
            throw java.io.IOException("Synthetic delayed transport")
        }.build()
        val job=launch { AllTrailsMcpClient(http).search(AllTrailsMcpClient.Area(49.0,-123.1,49.1,-123.0),10000.0,500.0) }
        kotlinx.coroutines.delay(30)
        assertTrue(started.await(1,java.util.concurrent.TimeUnit.SECONDS))
        job.cancel();job.join();assertTrue(call!!.isCanceled())
    }
    @Test fun roundedExpandedAreaOnly() {
        val area=AllTrailsMcpClient.Area.from(listOf(TrackPoint(49.35123,-123.21123,null,null,null),TrackPoint(49.56789,-123.15678,null,null,null)))
        assertEquals(49.33,area.south,.000001); assertEquals(-123.24,area.west,.000001)
        val text=area.input().toString()
        assertFalse(text.contains("49.35123")); assertFalse(text.contains("timestamp")); assertFalse(text.contains("points"))
    }
}
