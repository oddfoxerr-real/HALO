package dev.oddfoxerr.halo

import android.app.Application

/** Schedules background updates even if the Music and Alerts screens are never opened. */
class HaloApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        HaloUpdates.initialize(this)
    }
}
