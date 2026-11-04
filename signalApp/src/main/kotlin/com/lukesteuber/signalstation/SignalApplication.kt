package com.lukesteuber.signalstation

import android.app.Application
import coredevices.pebble.signal.AndroidSignalStation
import io.rebble.pebblekit2.client.DefaultPebbleAndroidAppPicker

class SignalApplication : Application(), coredevices.pebble.signal.SignalObservationHost {
    override lateinit var station: AndroidSignalStation
        private set
    lateinit var watchLink: PebbleAppLink
        private set
    lateinit var garmin: ConnectIqConnection
        private set
    override fun onCreate() {
        super.onCreate()
        if (com.lukesteuber.localmodels.LocalModels.isModelProcess(this)) return
        com.lukesteuber.localmodels.LocalModels.get(this)
        DefaultPebbleAndroidAppPicker.getInstance(this).enableAutoSelect = false
        watchLink = PebbleAppLink(this)
        garmin = ConnectIqConnection(this, BuildConfig.GARMIN_TETHERED)
        val garminLink = GarminAppLink(garmin)
        val watches = CombinedWatchLink(watchLink, garminLink)
        garminLink.onChanged = watches::sync
        station = AndroidSignalStation(this, watches)
        watchLink.request = station::handleWatchRequest
        garminLink.request = station::handleWatchRequest
        station.initialize()
    }
}
