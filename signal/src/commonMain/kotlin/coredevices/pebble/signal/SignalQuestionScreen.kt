package coredevices.pebble.signal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.time.Clock

@Composable
internal fun signalQuestionExpired(expiresAt: Long): Boolean {
    var now by remember(expiresAt) { mutableStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(expiresAt) { while (now < expiresAt) { delay(1000); now = Clock.System.now().toEpochMilliseconds() } }
    return expiresAt <= now
}

/** A separate review surface: never replaces the phone composer or its context. */
@Composable
@OptIn(ExperimentalComposeUiApi::class)
internal fun SignalWatchQuestionPanel(state: SignalState, station: SignalStation) {
    val review = state.watchQuestionReview ?: return
    var text by remember(review.draftId) { mutableStateOf(review.question) }
    var exact by remember(review) { mutableStateOf(false) }
    val prepared = review.reviewId.isNotBlank() && text == review.question
    val expired = signalQuestionExpired(review.expiresAt)
    BackHandler(enabled = !state.busy) { station.dismissWatchQuestionReview() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "title") { Text("Question from your watch", Modifier.semantics { heading() }) }
        item(key = "isolation") { Text("Your existing phone question and its evidence are unchanged.") }
        item(key = "provider") { Text("${providerLabel(review.provider)} · ${review.model}") }
        if (review.endpoint.isNotBlank()) item(key = "endpoint") { Text("Endpoint: ${review.endpoint}") }
        item(key = "question") {
            OutlinedTextField(text, { text = it; station.invalidateWatchQuestionReview() }, Modifier.fillMaxWidth(), label = { Text("Watch question") }, enabled = !state.busy)
        }
        review.contextDescription.lines().forEachIndexed { index, line -> item(key = "context:$index") { Text(line) } }
        item(key = "systems") { Text("Home: ${if (review.homeConnections.isEmpty()) "None" else review.homeConnections.joinToString()}.") }
        if (review.homeConnections.isNotEmpty()) item(key = "authority") { Text(if (review.homeActionsAllowed) "Reads and actions allowed for this question. Exact standing grants may execute." else "Read only. All Home actions are blocked for this question.") }
        if (prepared) {
            item(key = "counts") { Text("${review.observationCount} readings; ${review.priorTurnCount} linked answers; ${review.omittedRecords} records and ${review.omittedObservations} readings omitted. Originals remain in History.") }
            item(key = "disclosure") { TextButton({ exact = !exact }) { Text(if (exact) "Hide exact messages" else "Inspect exact messages") } }
        }
        if (exact && prepared) review.messages.forEachIndexed { index, (role, message) ->
            item(key = "message:$index") { SelectionContainer { Text("$role\n$message") } }
        }
        if (prepared && expired) item(key = "expired") { Text("Review expired. Review again before sending. Nothing was sent.") }
        item(key = "send") {
            if (!prepared || expired) Button({ station.reviewWatchQuestion(text) }, enabled = !state.busy && text.isNotBlank(), modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)) { Text("Review this question") }
            else Button(station::sendWatchQuestionReview, enabled = !state.busy, modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)) { Text("Send watch question once") }
        }
        item(key = "transmission") { Text("Only Send contacts the provider. An interrupted send will not automatically retry.") }
        item(key = "cancel") { TextButton(station::dismissWatchQuestionReview, enabled = !state.busy) { Text("Cancel watch question") } }
    }
}
