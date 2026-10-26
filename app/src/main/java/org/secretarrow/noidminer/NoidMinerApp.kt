package org.secretarrow.noidminer

import android.app.Application
import org.secretarrow.noidminer.di.ServiceLocator
import org.secretarrow.noidminer.pow.NativePow

class NoidMinerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        // Warm the native cores so the backend label is ready on first paint.
        NativePow.ensureLoaded()
        ServiceLocator.hardwareInfo()
    }
}
