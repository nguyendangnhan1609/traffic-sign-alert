package vn.trafficalert.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import vn.trafficalert.app.R

@Composable
fun PermissionScreen(denied: Boolean, onGrant: () -> Unit, onOpenSettings: () -> Unit, onReturn: () -> Unit) {
    // Người dùng có thể cấp quyền trong cài đặt hệ thống rồi quay lại.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) onReturn() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    Column(
        Modifier.fillMaxSize().background(Color.Black).verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.permission_title), style = MaterialTheme.typography.headlineSmall, color = Color.White)
        Text(stringResource(R.string.permission_camera), color = Color.White)
        Text(stringResource(R.string.permission_mic), color = Color.White)
        Text(stringResource(R.string.permission_location), color = Color.White)
        Text(stringResource(R.string.permission_note), color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        if (denied) {
            Text(stringResource(R.string.permission_camera_denied), color = Color(0xFFFF8A80))
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.permission_open_settings))
            }
        }
        Button(onClick = onGrant, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text(stringResource(R.string.permission_grant))
        }
    }
}
