package com.piperostool

import android.graphics.Bitmap
import android.view.View
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.liquidglass.LiquidGlassView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt
import java.io.File

@RunWith(AndroidJUnit4::class)
class UpdatedFeatureUiInstrumentedTest {
    @Test
    fun browserEntryPointUsesNativeGlassAndJunkEntryIsGone() {
        val context = ContextThemeWrapper(
            InstrumentationRegistry.getInstrumentation().targetContext,
            R.style.Theme_PiperOSTool
        )
        val inflater = LayoutInflater.from(context)
        val home = inflater.inflate(R.layout.fragment_home, null)
        val settings = inflater.inflate(R.layout.fragment_setting, null)
        assertNotNull(home.findViewById<LiquidGlassView>(R.id.homePiperBrowser))
        assertEquals(0, context.resources.getIdentifier("settingsStorageSurface", "id", context.packageName))
        assertEquals(0, context.resources.getIdentifier("btnCleanJunk", "id", context.packageName))
        assertNotNull(settings.findViewById<View>(R.id.settingsAccountSurface))
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

    @Test
    fun modernHomeNavigationSelectsTabsWithoutGlassOrSlidingIndicator() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val previousStyle = PiperUiPreferences.style(context)
        val previousColorMode = PiperUiPreferences.colorMode(context)
        try {
            PiperUiPreferences.setStyle(context, PiperUiStyle.MODERN)
            for (mode in listOf(PiperColorMode.LIGHT, PiperColorMode.DARK)) {
                PiperUiPreferences.setColorMode(context, mode)
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
                    scenario.onActivity { activity ->
                        val nav = activity.findViewById<FrameLayout>(R.id.bottomNavCard)
                        assertTrue(nav.javaClass != LiquidGlassView::class.java)
                        assertEquals(1, nav.childCount)
                        val homeLabel = activity.findViewById<TextView>(R.id.txtHome)
                        val settingsLabel = activity.findViewById<TextView>(R.id.txtSettings)
                        activity.findViewById<View>(R.id.navSettings).performClick()
                        assertNotNull(activity.findViewById<View>(R.id.layoutColorMode))
                        assertEquals(settingsLabel.currentTextColor, activity.findViewById<ImageView>(R.id.iconSettings).imageTintList?.defaultColor)
                        assertTrue(settingsLabel.currentTextColor != homeLabel.currentTextColor)
                    }
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    scenario.onActivity { activity ->
                        assertNotNull(activity.findViewById<View>(R.id.layoutColorMode))
                    }
                    Thread.sleep(250L) // Allow the page enter animation to finish before capture.
                    val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                    assertNotNull(screenshot)
                    File(context.getExternalFilesDir(null), "nav-${mode.key}.png").outputStream().use {
                        screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    screenshot.recycle()
                }
            }
        } finally {
            PiperUiPreferences.setColorMode(context, previousColorMode)
            PiperUiPreferences.setStyle(context, previousStyle)
        }
    }
}
