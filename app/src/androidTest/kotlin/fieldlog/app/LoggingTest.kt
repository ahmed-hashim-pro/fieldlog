package fieldlog.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Drives the real activity on a device: a geologist logging one borehole, offline. */
@RunWith(AndroidJUnit4::class)
class LoggingTest {
    @get:Rule
    val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun freshNotebookOffline() {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
        dir.listFiles()?.forEach { it.deleteRecursively() }
        // Start offline, as in the field: no relay needed for this test.
        dir.resolve("device.properties").writeText("device=test-phone\nonline=false\n")
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun close() {
        scenario?.close()
    }

    @Test
    fun logABoreholeUntilStrataWouldAcceptIt() {
        compose.onNodeWithTag("units").performTextInput("topsoil, clay, chalk")
        compose.onNodeWithTag("addHole").performClick()
        compose.onNodeWithTag("holeName").performTextInput("BH01")
        compose.onNodeWithTag("x").performTextInput("10")
        compose.onNodeWithTag("y").performTextInput("20")
        compose.onNodeWithTag("collar").performTextInput("50,5")

        compose.onNodeWithTag("addInterval").performClick()
        compose.onNodeWithTag("to:0").performTextInput("0.4")
        compose.onNodeWithTag("addInterval").performClick()
        // Leave a gap: the app says so, in strata's words.
        compose.onNodeWithTag("from:1").performTextReplacement("0.5")
        compose.onNodeWithTag("to:1").performTextInput("3.2")
        compose.onNode(hasTestTag("problem") and hasText("gap between 0.4 and 0.5 m", substring = true)).assertIsDisplayed()
        compose.onNodeWithTag("from:1").performTextReplacement("0.4")
        compose.onNode(hasTestTag("problem")).assertDoesNotExist()

        compose.onNodeWithTag("flag:water-strike").performClick()
        compose.onNodeWithTag("notes").performTextInput("Firm grey clay.")
        compose.onNodeWithTag("back").performClick()

        compose.onNode(hasTestTag("hole:BH01")).assertIsDisplayed()
        compose.onNode(hasText("ready")).assertIsDisplayed()
        compose.onNode(hasTestTag("status") and hasText("offline", substring = true)).assertIsDisplayed()
        compose.onNodeWithTag("export").performClick()
        compose.onNode(hasTestTag("csvPreview") and hasText("BH01,10,20,50.5,topsoil,0,0.4", substring = true)).assertIsDisplayed()
    }

    @Test
    fun offlineEditsSurviveTheAppBeingKilled() {
        compose.onNodeWithTag("addHole").performClick()
        compose.onNodeWithTag("holeName").performTextInput("TP3")
        compose.onNodeWithTag("notes").performTextInput("Seepage at 1.1 m.")
        scenario?.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithTag("hole:TP3").performClick()
        compose.onNode(hasTestTag("notes") and hasText("Seepage at 1.1 m.", substring = true)).assertIsDisplayed()
        compose.onNode(hasTestTag("status") and hasText("changes not sent", substring = true)).assertIsDisplayed()
    }

    @Test
    fun aRemovedHoleComesBackWithEverythingItHad() {
        compose.onNodeWithTag("addHole").performClick()
        compose.onNodeWithTag("holeName").performTextInput("BH07")
        compose.onNodeWithTag("flag:refusal").performClick()
        compose.onNodeWithTag("removeHole").performClick()
        compose.onNode(hasTestTag("hole:BH07")).assertDoesNotExist()
        compose.onNode(hasText("Undo")).performClick()
        compose.onNode(hasTestTag("hole:BH07") and hasText("refusal", substring = true)).assertIsDisplayed()
    }
}
