package dev.oddfoxerr.halo

import org.junit.Assert.*
import org.junit.Test

class HaloRulesTest {
    @Test fun packageRouting() {
        assertEquals(CharacterApp.DISCORD, CharacterApp.fromPackage("com.discord"))
        assertEquals(CharacterApp.WHATSAPP, CharacterApp.fromPackage("com.whatsapp.w4b"))
        assertEquals(CharacterApp.OTHER, CharacterApp.fromPackage("org.random.app"))
    }
    @Test fun deduplicatedNotifications() {
        val tracker = NotificationTracker()
        assertNotNull(tracker.posted("1", "com.discord"))
        assertNull(tracker.posted("1", "com.discord"))
        assertEquals(1, tracker.size())
        assertTrue(tracker.removed("1"))
        assertEquals(0, tracker.size())
    }
    @Test fun quietHoursOvernight() {
        assertTrue(QuietHoursPolicy.isQuiet(true, 22 * 60, 7 * 60, 23 * 60))
        assertTrue(QuietHoursPolicy.isQuiet(true, 22 * 60, 7 * 60, 6 * 60))
        assertFalse(QuietHoursPolicy.isQuiet(true, 22 * 60, 7 * 60, 12 * 60))
        assertFalse(QuietHoursPolicy.isQuiet(false, 22 * 60, 7 * 60, 23 * 60))
    }
    @Test fun silentIndicatorIsSuppressed() {
        val tracker = NotificationTracker()
        tracker.posted("silent", "com.discord", indicatorEligible = false)
        tracker.posted("alerting", "com.whatsapp", indicatorEligible = true)
        assertEquals(listOf("com.whatsapp"), tracker.newestIndicatorPackages())
        assertFalse(NotificationEligibilityPolicy.isAlerting(false, 2))
        assertTrue(NotificationEligibilityPolicy.isAlerting(true, 2))
        tracker.posted("silent", "com.discord", indicatorEligible = true)
        assertEquals(listOf("com.whatsapp", "com.discord"), tracker.newestIndicatorPackages())
    }
    @Test fun ruleCanOverrideCharacter() {
        val rule = ApplicationRule("com.discord", CharacterApp.OTHER, false, 0xFFAABBCCL)
        assertEquals(CharacterApp.OTHER, rule.character)
        assertFalse(rule.enabled)
        assertEquals(0xFFAABBCCL, rule.colorOverride)
    }
}
