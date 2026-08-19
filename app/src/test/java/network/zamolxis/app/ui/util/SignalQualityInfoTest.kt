package network.zamolxis.app.ui.util

import android.app.Application
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SignalCellular4Bar
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.SignalCellularConnectedNoInternet0Bar
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import network.zamolxis.app.test.RegisterComponentActivityRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SignalQualityInfoTest {
    // RSSI: Excellent (>-50), Good (-50 to -70), Fair (-70 to -85), Weak (-85 to -100), Very Weak (<-100)
    // SNR: Excellent (>10), Good (5-10), Fair (0-5), Poor (-5-0), Very Poor (<-5)

    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    /**
     * [getRssiInfo] / [getSnrInfo] became `@Composable` when their wording moved to
     * `stringResource`, so they can only be called from a composition. This evaluates
     * one inside the compose rule and hands the value back to the assertions, which
     * are otherwise unchanged — the resources carry the same English wording the
     * functions used to build by hand.
     */
    private fun <T : Any> evaluate(block: @Composable () -> T): T {
        lateinit var captured: T
        composeRule.setContent { captured = block() }
        return captured
    }

    // Test color constants (match production code)
    companion object {
        private val ColorExcellent = Color(0xFF4CAF50)
        private val ColorGood = Color(0xFF8BC34A)
        private val ColorFair = Color(0xFFFFC107)
        private val ColorPoor = Color(0xFFFF9800)
        private val ColorVeryPoor = Color(0xFFF44336)
    }

    @Test
    fun `getRssiInfo returns excellent for very strong signal`() {
        val info = evaluate { getRssiInfo(-40) }

        assertEquals(Icons.Default.SignalCellular4Bar, info.icon)
        assertEquals(ColorExcellent, info.color)
        assertTrue(info.text.contains("Excellent"))
        assertTrue(info.text.contains("-40 dBm"))
    }

    @Test
    fun `getRssiInfo returns excellent at boundary`() {
        val info = evaluate { getRssiInfo(-49) }

        assertEquals(Icons.Default.SignalCellular4Bar, info.icon)
        assertTrue(info.text.contains("Excellent"))
    }

    @Test
    fun `getRssiInfo returns good for strong signal`() {
        val info = evaluate { getRssiInfo(-60) }

        assertEquals(Icons.Default.SignalCellular4Bar, info.icon)
        assertEquals(ColorGood, info.color)
        assertTrue(info.text.contains("Good"))
        assertTrue(info.text.contains("-60 dBm"))
    }

    @Test
    fun `getRssiInfo returns good at lower boundary`() {
        val info = evaluate { getRssiInfo(-50) }

        assertEquals(Icons.Default.SignalCellular4Bar, info.icon)
        assertTrue(info.text.contains("Good"))
    }

    @Test
    fun `getRssiInfo returns fair for moderate signal`() {
        val info = evaluate { getRssiInfo(-75) }

        assertEquals(Icons.Default.SignalCellularAlt, info.icon)
        assertEquals(ColorFair, info.color)
        assertTrue(info.text.contains("Fair"))
        assertTrue(info.text.contains("-75 dBm"))
    }

    @Test
    fun `getRssiInfo returns fair at lower boundary`() {
        val info = evaluate { getRssiInfo(-70) }

        assertEquals(Icons.Default.SignalCellularAlt, info.icon)
        assertTrue(info.text.contains("Fair"))
    }

    @Test
    fun `getRssiInfo returns weak for low signal`() {
        val info = evaluate { getRssiInfo(-90) }

        assertEquals(Icons.Default.SignalCellularAlt, info.icon)
        assertEquals(ColorPoor, info.color)
        assertTrue(info.text.contains("Weak"))
        assertTrue(info.text.contains("-90 dBm"))
    }

    @Test
    fun `getRssiInfo returns weak at lower boundary`() {
        val info = evaluate { getRssiInfo(-85) }

        assertEquals(Icons.Default.SignalCellularAlt, info.icon)
        assertTrue(info.text.contains("Weak"))
    }

    @Test
    fun `getRssiInfo returns very weak for marginal signal`() {
        val info = evaluate { getRssiInfo(-110) }

        assertEquals(Icons.Default.SignalCellularConnectedNoInternet0Bar, info.icon)
        assertEquals(ColorVeryPoor, info.color)
        assertTrue(info.text.contains("Very Weak"))
        assertTrue(info.text.contains("-110 dBm"))
    }

    @Test
    fun `getRssiInfo returns very weak at boundary`() {
        val info = evaluate { getRssiInfo(-100) }

        assertEquals(Icons.Default.SignalCellularConnectedNoInternet0Bar, info.icon)
        assertTrue(info.text.contains("Very Weak"))
    }

    @Test
    fun `getSnrInfo returns excellent for clear signal`() {
        val info = evaluate { getSnrInfo(15.0f) }

        assertEquals(Icons.Default.SignalCellular4Bar, info.icon)
        assertEquals(ColorExcellent, info.color)
        assertTrue(info.text.contains("Excellent"))
        assertTrue(info.text.contains("15.0 dB"))
    }

    @Test
    fun `getSnrInfo returns excellent at boundary`() {
        val info = evaluate { getSnrInfo(10.1f) }

        assertEquals(Icons.Default.SignalCellular4Bar, info.icon)
        assertTrue(info.text.contains("Excellent"))
    }

    @Test
    fun `getSnrInfo returns good for low noise`() {
        val info = evaluate { getSnrInfo(7.5f) }

        assertEquals(Icons.Default.SignalCellular4Bar, info.icon)
        assertEquals(ColorGood, info.color)
        assertTrue(info.text.contains("Good"))
        assertTrue(info.text.contains("7.5 dB"))
    }

    @Test
    fun `getSnrInfo returns good at lower boundary`() {
        val info = evaluate { getSnrInfo(5.1f) }

        assertEquals(Icons.Default.SignalCellular4Bar, info.icon)
        assertTrue(info.text.contains("Good"))
    }

    @Test
    fun `getSnrInfo returns fair for moderate noise`() {
        val info = evaluate { getSnrInfo(2.5f) }

        assertEquals(Icons.Default.SignalCellularAlt, info.icon)
        assertEquals(ColorFair, info.color)
        assertTrue(info.text.contains("Fair"))
        assertTrue(info.text.contains("2.5 dB"))
    }

    @Test
    fun `getSnrInfo returns fair at lower boundary`() {
        val info = evaluate { getSnrInfo(0.1f) }

        assertEquals(Icons.Default.SignalCellularAlt, info.icon)
        assertTrue(info.text.contains("Fair"))
    }

    @Test
    fun `getSnrInfo returns poor for high noise`() {
        val info = evaluate { getSnrInfo(-2.5f) }

        assertEquals(Icons.Default.SignalCellularAlt, info.icon)
        assertEquals(ColorPoor, info.color)
        assertTrue(info.text.contains("Poor"))
        assertTrue(info.text.contains("-2.5 dB"))
    }

    @Test
    fun `getSnrInfo returns poor at lower boundary`() {
        val info = evaluate { getSnrInfo(-4.9f) }

        assertEquals(Icons.Default.SignalCellularAlt, info.icon)
        assertTrue(info.text.contains("Poor"))
    }

    @Test
    fun `getSnrInfo returns very poor for very high noise`() {
        val info = evaluate { getSnrInfo(-10.0f) }

        assertEquals(Icons.Default.SignalCellularConnectedNoInternet0Bar, info.icon)
        assertEquals(ColorVeryPoor, info.color)
        assertTrue(info.text.contains("Very Poor"))
        assertTrue(info.text.contains("-10.0 dB"))
    }

    @Test
    fun `getSnrInfo returns very poor at boundary`() {
        val info = evaluate { getSnrInfo(-5.0f) }

        assertEquals(Icons.Default.SignalCellularConnectedNoInternet0Bar, info.icon)
        assertTrue(info.text.contains("Very Poor"))
    }

    @Test
    fun `getSnrInfo formats decimal correctly`() {
        val info = evaluate { getSnrInfo(8.333333f) }

        assertTrue(info.text.contains("8.3 dB"))
    }
}
