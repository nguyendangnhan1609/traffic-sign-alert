package vn.trafficalert.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import vn.trafficalert.app.ui.MainScreen
import vn.trafficalert.app.ui.PermissionScreen
import vn.trafficalert.app.ui.SettingsScreen

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) { App(viewModel) }
        }
    }
}

private val PERMISSIONS = arrayOf(
    Manifest.permission.CAMERA,
    Manifest.permission.RECORD_AUDIO,
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

private fun Context.has(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

@Composable
private fun App(vm: MainViewModel) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("ui", Context.MODE_PRIVATE) }
    var disclaimerAccepted by remember { mutableStateOf(prefs.getBoolean("disclaimer_accepted", false)) }
    var cameraGranted by remember { mutableStateOf(context.has(Manifest.permission.CAMERA)) }
    var askedOnce by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    fun refreshPermissions() {
        cameraGranted = context.has(Manifest.permission.CAMERA)
        vm.onMicPermission(context.has(Manifest.permission.RECORD_AUDIO))
        vm.onLocationPermission(
            context.has(Manifest.permission.ACCESS_FINE_LOCATION) || context.has(Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        askedOnce = true
        refreshPermissions()
    }

    // Người dùng có thể cấp quyền micro/vị trí trong cài đặt hệ thống rồi quay lại app.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME && context.has(Manifest.permission.CAMERA)) refreshPermissions()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    if (!disclaimerAccepted) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.disclaimer_title)) },
            text = { Text(stringResource(R.string.disclaimer_text)) },
            confirmButton = {
                TextButton(onClick = {
                    prefs.edit().putBoolean("disclaimer_accepted", true).apply()
                    disclaimerAccepted = true
                }) { Text(stringResource(R.string.disclaimer_accept)) }
            },
        )
        return
    }

    when {
        !cameraGranted -> PermissionScreen(
            denied = askedOnce,
            onGrant = { launcher.launch(PERMISSIONS) },
            onOpenSettings = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            },
            onReturn = { refreshPermissions() },
        )
        showSettings -> {
            BackHandler { showSettings = false }
            SettingsScreen(vm, onBack = { showSettings = false })
        }
        else -> MainScreen(vm, onOpenSettings = { showSettings = true })
    }
}
