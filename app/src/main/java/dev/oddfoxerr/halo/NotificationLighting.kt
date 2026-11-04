package dev.oddfoxerr.halo

import android.graphics.Color

/** Notifications share a tested GPU engine, not music settings. */
internal object NotificationLighting {
    fun config(store: ProfileStore, primary: Int, design: EdgeMode): MusicConfiguration {
        val accent = Color.rgb(
            (Color.red(primary) * .62f + 255f * .38f).toInt().coerceIn(0, 255),
            (Color.green(primary) * .62f + 255f * .38f).toInt().coerceIn(0, 255),
            (Color.blue(primary) * .62f + 255f * .38f).toInt().coerceIn(0, 255)
        )
        return MusicConfiguration(
            mode = design,
            color = primary,
            accent = accent,
            sensitivity = 1f,
            beatIntensity = 1f,
            dynamicForce = 2.2f,
            thickness = store.notificationBorderThickness.coerceIn(.6f, 3f),
            glow = store.notificationBorderGlow,
            brightness = 1f,
            spread = .85f,
            speed = 1f,
            corner = store.notificationBorderCorner,
            leftInset = store.notificationBorderLeft,
            rightInset = store.notificationBorderRight,
            topInset = store.notificationBorderTop,
            bottomInset = store.notificationBorderBottom,
            onlyPlaying = false,
            respectQuiet = false,
            retreatInOtherApps = false
        )
    }
}
