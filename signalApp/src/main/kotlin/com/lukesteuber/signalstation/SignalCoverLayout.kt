package com.lukesteuber.signalstation

import android.content.Context
import android.os.Build
import android.view.WindowInsets as AndroidWindowInsets
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import coredevices.pebble.signal.LocalSignalDialogBottomInset

@android.annotation.SuppressLint("ViewConstructor")
internal class SignalCoverFrame(context: Context, private val manufacturer: String = Build.MANUFACTURER,
    private val model: String = Build.MODEL) : FrameLayout(context) {
    var bottomInset by mutableIntStateOf(0)
        private set
    private var cutouts = emptyList<SignalCoverCutout>()

    override fun onApplyWindowInsets(insets: AndroidWindowInsets): AndroidWindowInsets {
        cutouts = if (Build.VERSION.SDK_INT >= 28) insets.displayCutout?.boundingRects.orEmpty()
            .map { SignalCoverCutout(it.left, it.top, it.right, it.bottom) } else emptyList()
        updateInset()
        return super.onApplyWindowInsets(insets)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateInset()
    }

    private fun updateInset() {
        bottomInset = signalCoverBottomInset(manufacturer, model, width, height, cutouts)
    }
}

@Composable
internal fun SignalCoverContent(bottomInset: Int, content: @Composable () -> Unit) {
    val dialogInset = with(LocalDensity.current) { bottomInset.toDp() }
    CompositionLocalProvider(LocalSignalDialogBottomInset provides dialogInset) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets(0, 0, 0, bottomInset))) { content() }
    }
}
