package com.lukesteuber.signalstation

import coredevices.pebble.signal.AndroidSignalStation
import io.rebble.pebblekit2.client.BasePebbleListenerService
import io.rebble.pebblekit2.common.model.*
import kotlinx.coroutines.*
import java.util.UUID

class SignalListener : BasePebbleListenerService() {
    private val uuid = UUID.fromString(AndroidSignalStation.APP_UUID)
    private val link get() = (application as SignalApplication).watchLink
    override fun onAppOpened(watchappUUID: UUID, watch: WatchIdentifier) { if (watchappUUID == uuid) link.opened(watch.value) }
    override fun onAppClosed(watchappUUID: UUID, watch: WatchIdentifier) { if (watchappUUID == uuid) link.closed(watch.value) }
    override suspend fun onMessageReceived(watchappUUID: UUID, data: PebbleDictionary, watch: WatchIdentifier): ReceiveResult {
        if (watchappUUID != uuid) return ReceiveResult.Nack
        return link.receive(watch.value, data)
    }
}
