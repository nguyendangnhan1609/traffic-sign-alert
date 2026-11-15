package vn.trafficalert.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import vn.trafficalert.app.MainViewModel
import vn.trafficalert.app.R
import vn.trafficalert.app.settings.AppSettings
import vn.trafficalert.core.RepeatMode
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val current by vm.settings.collectAsState()
    var apiKey by remember { mutableStateOf(current.apiKey) }
    var model by remember { mutableStateOf(current.model) }
    var cloud by remember { mutableStateOf(current.cloudEnabled) }
    var mode by remember { mutableStateOf(current.repeatMode) }
    var interval by remember { mutableFloatStateOf(current.repeatIntervalSec.toFloat()) }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.settings_back)) }
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)
        }

        Text(stringResource(R.string.settings_repeat_section), style = MaterialTheme.typography.titleMedium)
        RepeatOption(stringResource(R.string.settings_repeat_when_speeding), mode == RepeatMode.WHEN_SPEEDING) {
            mode = RepeatMode.WHEN_SPEEDING
        }
        RepeatOption(stringResource(R.string.settings_repeat_always), mode == RepeatMode.ALWAYS_UNTIL_ACK) {
            mode = RepeatMode.ALWAYS_UNTIL_ACK
        }
        Text(stringResource(R.string.settings_repeat_interval, interval.roundToInt()))
        Slider(value = interval, onValueChange = { interval = it }, valueRange = 5f..30f, steps = 4)

        Text(stringResource(R.string.settings_cloud_section), style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_cloud_enabled), Modifier.weight(1f))
            Switch(checked = cloud, onCheckedChange = { cloud = it })
        }
        OutlinedTextField(
            value = apiKey, onValueChange = { apiKey = it }, singleLine = true,
            label = { Text(stringResource(R.string.settings_api_key)) },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(stringResource(R.string.settings_api_key_hint), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        OutlinedTextField(
            value = model, onValueChange = { model = it }, singleLine = true,
            label = { Text(stringResource(R.string.settings_model)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(stringResource(R.string.settings_model_hint), style = MaterialTheme.typography.bodySmall, color = Color.Gray)

        Button(
            onClick = {
                vm.saveSettings(
                    AppSettings(
                        apiKey = apiKey.trim(),
                        model = model.trim().ifEmpty { AppSettings.DEFAULT_MODEL },
                        cloudEnabled = cloud,
                        repeatMode = mode,
                        repeatIntervalSec = interval.roundToInt(),
                    )
                )
                onBack()
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text(stringResource(R.string.settings_save)) }
    }
}

@Composable
private fun RepeatOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label)
    }
}
