package com.piperostool

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.piperostool.privileged.PiperServiceState
import com.piperostool.privileged.client.PiperPrivilegedClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivilegedLifecycleInstrumentedTest {
    @Test fun serviceCanStartAndStopWithoutHanging() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PiperPrivilegedClient(context).use { client ->
            assertTrue("PPS did not bind", client.connect())
            assertNotNull(client.status())
            assertTrue("PPS did not accept refresh", client.refresh())
            assertTrue("PPS did not accept shutdown", client.shutdown())
            assertEquals(PiperServiceState.STOPPED, client.status()?.state)
        }
    }

    @Test fun catalogContainsEverySpreadsheetRow() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val catalog = org.json.JSONArray(context.assets.open("phone_catalog.json").bufferedReader().use { it.readText() })
        assertEquals(1307, catalog.length())
        val rows = (0 until catalog.length()).map { catalog.getJSONObject(it).getInt("row") }
        assertEquals(rows.size, rows.toSet().size)
    }

}
