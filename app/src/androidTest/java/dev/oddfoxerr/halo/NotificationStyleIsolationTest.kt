package dev.oddfoxerr.halo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationStyleIsolationTest {
    @Test fun alertSettingsNeverOverwriteMusicSettingsOrSavedDesign() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ProfileStore(context)
        val original = store.notificationEdgeDesign
        try {
            store.notificationEdgeDesign = EdgeMode.LIQUID_EDGE
            val notification = NotificationLighting.config(store, 0xFF8790FB.toInt(), EdgeMode.LIQUID_EDGE)
            assertEquals(EdgeMode.LIQUID_EDGE, notification.mode)
            assertFalse(notification.retreatInOtherApps)
            assertEquals(0xFF8790FB.toInt(), notification.color)
            val music = MusicConfiguration.read(context)
            // Notification rendering must not save any MusicConfiguration data.
            val independent = NotificationLighting.config(store, 0xFF4BE49D.toInt(), EdgeMode.HAIRLINE)
            assertEquals(EdgeMode.HAIRLINE, independent.mode)
            assertEquals(music.mode, MusicConfiguration.read(context).mode)
            assertTrue(independent.corner >= 0f)
            assertTrue(independent.thickness >= .6f)
        } finally {
            store.notificationEdgeDesign = original
        }
    }
}
