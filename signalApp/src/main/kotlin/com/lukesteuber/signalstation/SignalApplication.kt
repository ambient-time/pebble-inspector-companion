package com.lukesteuber.signalstation

import android.app.Application
import coredevices.pebble.signal.AndroidSignalStation
import io.rebble.pebblekit2.client.DefaultPebbleAndroidAppPicker

class SignalApplication : Application(), coredevices.pebble.signal.SignalObservationHost {
    override lateinit var station: AndroidSignalStation
        private set
    lateinit var watchLink: PebbleAppLink
        private set
    override fun onCreate() {
        super.onCreate()
        DefaultPebbleAndroidAppPicker.getInstance(this).enableAutoSelect = false
        watchLink = PebbleAppLink(this)
        station = AndroidSignalStation(this, watchLink)
        watchLink.request = station::handleWatchRequest
        station.initialize()
    }
}
