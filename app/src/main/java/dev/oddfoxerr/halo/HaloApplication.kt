package dev.oddfoxerr.halo

import android.app.Application

class HaloApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        HaloUpdater.schedule(this)
    }
}
