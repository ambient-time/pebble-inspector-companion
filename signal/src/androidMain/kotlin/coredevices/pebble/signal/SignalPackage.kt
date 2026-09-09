package coredevices.pebble.signal

internal fun signalPackageEnabled(packageName: String) =
    packageName == "com.lukesteuber.signalstation" || packageName.endsWith(".inspectorlab")
