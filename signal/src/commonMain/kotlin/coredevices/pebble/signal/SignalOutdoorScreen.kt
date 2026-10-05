package coredevices.pebble.signal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

@Composable
internal fun OutdoorConnectionDialog(initial: HomeConnection, state: SignalState, station: SignalStation, dismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var place by remember { mutableStateOf(initial.outdoor?.place) }
    var query by remember { mutableStateOf("") }
    var showResults by remember { mutableStateOf(false) }
    var sources by remember { mutableStateOf(initial.outdoor?.sources ?: emptySet()) }
    var tideStation by remember { mutableStateOf(initial.outdoor?.tideStation.orEmpty()) }
    var radius by remember { mutableStateOf((initial.outdoor?.earthquakeRadiusKm ?: 100).toString()) }
    var accepted by remember { mutableStateOf(false) }
    var attempted by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    DisposableEffect(station) { onDispose { station.cancelWeatherPlaceSearch() } }
    val config = place?.let { OutdoorConfig(it, sources, tideStation.trim(), radius.toIntOrNull() ?: 0) }
    val candidate = initial.copy(name = name.trim(), baseUrl = "", credentialKey = "", allowPrivateHttp = false, mqtt = null, outdoor = config)
    val error = runCatching { validateOutdoorConnection(candidate) }.exceptionOrNull()?.message
    val scroll = rememberScrollState()
    AlertDialog(onDismissRequest = dismiss, title = { Text("Weather & environment") }, text = {
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        fun finishTyping() { focus.clearFocus(); keyboard?.hide() }
        Column(Modifier.verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Outdoor public data alongside your room sensors. No watch, subscription key or controller is required. These are on-demand views, not emergency notifications.")
            if (state.homeBusy) {
                Text("Reading the selected public services…", Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                if (attempted) Text(state.homeStatus, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                OutlinedTextField(name, { name = it.take(100); accepted = false }, label = { Text("Connection name") }, modifier = Modifier.fillMaxWidth())
                Text("Choose a place", style = MaterialTheme.typography.titleMedium)
                place?.let { Text("${it.name}\n${it.latitude}, ${it.longitude}") }
                state.settings.weatherPlace?.takeIf { it != place }?.let { saved ->
                    TextButton({ finishTyping(); place = saved; accepted = false }) { Text("Use saved weather place: ${saved.name}") }
                }
                OutlinedTextField(query, { query = it.take(100); showResults = false }, label = { Text("City and country") }, modifier = Modifier.fillMaxWidth())
                Text("Search sends your typed city to Open-Meteo. It does not request this phone's location.", style = MaterialTheme.typography.bodySmall)
                TextButton({ finishTyping(); showResults = true; station.searchWeatherPlaces(query) }, enabled = !state.weatherSearching && query.trim().length >= 2) { Text("Search Open-Meteo") }
                if (showResults) {
                    Text(state.weatherSearchStatus, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    state.weatherPlaces.forEach { found ->
                        TextButton({ finishTyping(); place = found; accepted = false; showResults = false }, modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)) { Text("${found.name}\n${found.latitude}, ${found.longitude}") }
                    }
                }
                Text("Sources", style = MaterialTheme.typography.titleMedium)
                SignalOutdoorSources.sources.forEach { source ->
                    SignalToggle(source.name, source.key in sources) { enabled -> sources = if (enabled) sources + source.key else sources - source.key; accepted = false }
                }
                if ("weather.pollen" in sources) Text("Pollen is modeled, seasonal and available only in the provider's European coverage. Missing pollen is not zero pollen.")
                if ("environment.alerts" in sources) Text("NWS checks whether it covers this place. Other countries' official alert providers are not yet configured. Check official alerts directly in an emergency.")
                if ("environment.tides" in sources) {
                    OutlinedTextField(tideStation, { tideStation = it.take(7); accepted = false }, label = { Text("NOAA station ID (7 digits)") }, modifier = Modifier.fillMaxWidth())
                    TextButton({ uri.openUri("https://tidesandcurrents.noaa.gov/map/") }) { Text("Find a NOAA station") }
                    Text("The preview verifies the station name and distance. Predictions use metres above MLLW, with UTC times. No nearest station is silently chosen. Not for navigation.")
                }
                if ("environment.earthquakes" in sources) {
                    OutlinedTextField(radius, { radius = it.take(3); accepted = false }, label = { Text("Earthquake radius (10–500 km)") }, modifier = Modifier.fillMaxWidth())
                    Text("USGS: latest 20 matching earthquakes from the past 24 hours. This is not an early-warning service.")
                }
                Text("Outgoing data", style = MaterialTheme.typography.titleMedium)
                Text("The chosen coordinates go to enabled Open-Meteo/CAMS, NWS and USGS sources. NOAA receives only your station ID and requested dates. Requests may expose your IP address. Reading and saving do not share anything with a language model.")
                SignalToggle("I agree to request these public sources", accepted) { accepted = it }
                if (place != null && sources.isNotEmpty() && error != null) Text(error)
                Button({ finishTyping(); attempted = true; station.previewOutdoorConnection(candidate, accepted) },
                    enabled = accepted && name.isNotBlank() && error == null,
                    modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)) { Text("Preview selected sources") }
            }
        }
    }, confirmButton = {}, dismissButton = { TextButton(dismiss) { Text("Cancel") } })
}
