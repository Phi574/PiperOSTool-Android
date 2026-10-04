package com.piperostool

import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.liquidglass.LiquidGlassView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class UpdatedFeatureUiInstrumentedTest {
    @Test
    fun browserAndJunkEntryPointsUseNativeGlass() {
        ActivityScenario.launch(AppJunkCleanerActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val home = activity.layoutInflater.inflate(R.layout.fragment_home, null)
                val settings = activity.layoutInflater.inflate(R.layout.fragment_setting, null)
                assertNotNull(home.findViewById<LiquidGlassView>(R.id.homePiperBrowser))
                assertNotNull(settings.findViewById<LiquidGlassView>(R.id.settingsJunkGlass))
                val root = activity.findViewById<View>(android.R.id.content)
                assertTrue(countGlassSurfaces(root) >= 2)
            }
        }
    }

    private fun countGlassSurfaces(view: View): Int {
        var count = if (view is LiquidGlassView) 1 else 0
        if (view is android.view.ViewGroup) {
            for (index in 0 until view.childCount) count += countGlassSurfaces(view.getChildAt(index))
        }
        return count
    }

    @Test
    fun browserUsesBottomPiperExitWithoutOldTitleBar() {
        ActivityScenario.launch(PiperBrowserActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val exit = activity.findViewById<ImageButton>(R.id.btnExitBrowser)
                val logo = activity.findViewById<ImageView>(R.id.browserStartLogo)
                assertNotNull(exit)
                assertEquals(0, activity.resources.getIdentifier("browserTopBar", "id", activity.packageName))
                assertEquals(ImageView.ScaleType.CENTER_INSIDE, exit.scaleType)
                assertEquals(ImageView.ScaleType.CENTER_INSIDE, logo.scaleType)
                assertTrue(logo.layoutParams.width <= (72 * activity.resources.displayMetrics.density).roundToInt())
            }
        }
    }

    @Test
    fun reusableIconsStayInsideTheirCompactBounds() {
        ActivityScenario.launch(PiperBrowserActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val appRow = activity.layoutInflater.inflate(R.layout.item_app_grid, null)
                val appIcon = appRow.findViewById<ImageView>(R.id.ivAppIcon)
                val fileRow = activity.layoutInflater.inflate(R.layout.item_archive_entry, null)
                val fileIcon = fileRow.findViewById<ImageView>(R.id.archiveEntryIcon)
                val density = activity.resources.displayMetrics.density

                assertEquals((50 * density).roundToInt(), appIcon.layoutParams.width)
                assertEquals(ImageView.ScaleType.FIT_CENTER, appIcon.scaleType)
                assertEquals((48 * density).roundToInt(), fileIcon.layoutParams.width)
                assertEquals(ImageView.ScaleType.CENTER_INSIDE, fileIcon.scaleType)
                assertEquals((6 * density).roundToInt(), fileIcon.paddingLeft)
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

    @Test
    fun modernHomeKeepsAppearanceControlsAvailable() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val previousStyle = PiperUiPreferences.style(context)
        try {
            PiperUiPreferences.setStyle(context, PiperUiStyle.MODERN)
            ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    assertEquals(View.GONE, activity.findViewById<View>(R.id.homeBackground).visibility)
                    activity.findViewById<View>(R.id.navSettings).performClick()
                    activity.supportFragmentManager.executePendingTransactions()
                    assertNotNull(activity.findViewById<View>(R.id.layoutUiStyle))
                    assertNotNull(activity.findViewById<View>(R.id.layoutColorMode))
                    assertNotNull(activity.findViewById<View>(R.id.layoutLanguage))
                }
            }
        } finally {
            PiperUiPreferences.setStyle(context, previousStyle)
        }
    }
}
