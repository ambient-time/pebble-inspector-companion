package coredevices.pebble.signal

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun HomeMqttFields(config: HomeMqttConfig, change: (HomeMqttConfig) -> Unit) {
    Text("Readings", style = MaterialTheme.typography.titleMedium)
    Text("Use exact topics. For JSON, enter a field such as temperature or data.temperature. Use $ for a plain number; leave an unused field blank.")
    config.topics.forEachIndexed { index, topic ->
        fun update(next: HomeMqttTopic) = change(config.copy(topics = config.topics.mapIndexed { i, old -> if (i == index) next else old }))
        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Reading ${index + 1}", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(topic.name, { update(topic.copy(name = it.take(100))) }, label = { Text("Reading name") }, singleLine = true)
                OutlinedTextField(topic.topic, { update(topic.copy(topic = it.take(256))) }, label = { Text("Topic") }, singleLine = true)
                OutlinedTextField(topic.temperaturePath, { update(topic.copy(temperaturePath = it.take(128))) }, label = { Text("Temperature field") }, singleLine = true)
                SignalChoice("Temperature source unit", topic.temperatureUnit, listOf("°C" to "°C", "°F" to "°F")) { update(topic.copy(temperatureUnit = it)) }
                OutlinedTextField(topic.humidityPath, { update(topic.copy(humidityPath = it.take(128))) }, label = { Text("Humidity field") }, singleLine = true)
                var advanced by remember { mutableStateOf(false) }
                TextButton({ advanced = !advanced }) { Text(if (advanced) "Hide timestamp & freshness" else "Timestamp & freshness") }
                if (advanced) {
                    Text("Only map a timestamp if the publisher defines it as sample time. Accepts ISO 8601 with timezone or Unix seconds/milliseconds. Without it, retained readings have unknown measurement age.")
                    OutlinedTextField(topic.timestampPath, { update(topic.copy(timestampPath = it.take(128))) }, label = { Text("Sample timestamp field (optional)") }, singleLine = true)
                    var stale by remember(topic.staleAfterSeconds) { mutableStateOf(topic.staleAfterSeconds.toString()) }
                    OutlinedTextField(stale, { value ->
                        stale = value.take(7)
                        update(topic.copy(staleAfterSeconds = stale.toIntOrNull() ?: 0))
                    }, label = { Text("Stale after (seconds)") }, singleLine = true)
                }
                if (config.topics.size > 1) TextButton({ change(config.copy(topics = config.topics.filterIndexed { i, _ -> i != index })) }) { Text("Remove reading ${index + 1}") }
            }
        }
    }
    if (config.topics.size < 20) TextButton({ change(config.copy(topics = config.topics + HomeMqttTopic())) }) { Text("Add reading") }
}
