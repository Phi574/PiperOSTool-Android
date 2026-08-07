package com.piperostool

import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpdatedFeatureUiInstrumentedTest {
    @Test
    fun browserUsesBottomPiperExitWithoutOldTitleBar() {
        ActivityScenario.launch(PiperBrowserActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull(activity.findViewById<View>(R.id.btnExitBrowser))
                assertEquals(0, activity.resources.getIdentifier("browserTopBar", "id", activity.packageName))
            }
        }
    }

    @Test
    fun fakeMapExposesWaypointAndRouteSuggestionControls() {
        ActivityScenario.launch(FakeMapActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.btnModeRoute).performClick()
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.btnPickWaypoint).visibility)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.fakeMapWaypointActions).visibility)
                assertNotNull(activity.findViewById<View>(R.id.btnRouteSuggestions))
            }
        }
    }

    @Test
    fun fileManagerLoadsItsSemanticFileList() {
        ActivityScenario.launch(PiperFileManagerActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull(activity.findViewById<View>(R.id.fileManagerFiles))
                assertNotNull(activity.findViewById<View>(R.id.btnFileManagerMore))
            }
        }
    }
}
