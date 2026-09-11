package coredevices.pebble.signal

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*

internal enum class SignalPage(val title: String) {
    Today("Now"), Live("Around me"), Conversation("Ask"), Capture("Collection presets"), History("History"), Presence("Around me"), Sessions("Record over time"), Memory("Patterns"), Sources("Sources and permissions"), Settings("Settings")
}

internal data class SignalRoute(val page: SignalPage, val detailId: String? = null, val fieldTest: Boolean = false)

/** Activity-scoped memory only: never serialize questions, readings, or credentials into Bundles. */
class SignalUiSession {
    internal var route by mutableStateOf(SignalRoute(SignalPage.Today))
    internal var stack by mutableStateOf(emptyList<SignalRoute>())
    internal var questionDraft by mutableStateOf("")
    internal var historyQuestion by mutableStateOf(false)
    internal var consumedQuestionToken = ""
    internal var consumedSavedQuestionToken = ""
    internal var settingsSection by mutableStateOf("menu")
    internal var aroundTab by mutableStateOf("signals")
    internal val values = mutableMapOf<String, Any>()
    internal val lists = mutableMapOf<String, LazyListState>()
    internal fun closeAskPanel() {
        @Suppress("UNCHECKED_CAST")
        (values["ask.panel"] as? MutableState<String>)?.value = ""
    }
    internal fun navigate(page: SignalPage) {
        val next = SignalRoute(page)
        if (next != route) { stack = stack + route; route = next }
    }
    internal fun detail(id: String) { stack = stack + route; route = route.copy(detailId = id, fieldTest = false) }
    internal fun back() { route = stack.lastOrNull() ?: SignalRoute(SignalPage.Today); stack = stack.dropLast(1) }
    internal fun tab(page: SignalPage) { stack = emptyList(); route = SignalRoute(page) }
}
internal val LocalSignalUiSession = staticCompositionLocalOf { SignalUiSession() }
@Composable
internal fun <T> signalUiState(key: String, initial: () -> T): MutableState<T> {
    val session = LocalSignalUiSession.current
    @Suppress("UNCHECKED_CAST")
    return session.values.getOrPut(key) { mutableStateOf(initial()) } as MutableState<T>
}
@Composable
internal fun signalListState(key: String): LazyListState = LocalSignalUiSession.current.lists.getOrPut(key) { LazyListState() }
