package com.raymanvh.waterhsync

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.raymanvh.waterhsync.ui.MainScreen
import com.raymanvh.waterhsync.ui.WaterHTheme

class MainActivity : ComponentActivity() {

    private lateinit var controller: SyncController
    private var pendingAction: (() -> Unit)? = null
    private var asked = false

    private val bluetoothPermissions = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
    )

    // Notifications are optional (only the background-sync notice uses them).
    private val permissions = bluetoothPermissions + Manifest.permission.POST_NOTIFICATIONS

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val action = pendingAction
            pendingAction = null
            if (bluetoothPermissions.all(::granted)) {
                action?.invoke()
            } else {
                Toast.makeText(this, "WaterH Sync needs Nearby devices to talk to the bottle.", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        controller = SyncController.get(this)
        controller.ensureServiceRunning()
        setContent {
            WaterHTheme {
                MainScreen(controller, withPermissions = ::withPermissions)
            }
        }
    }

    override fun onStop() {
        controller.stopScan()
        super.onStop()
    }

    private fun granted(permission: String) =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Runs [action] once Bluetooth permissions are granted. Also asks for
     * notifications once per launch, but never blocks on them.
     */
    private fun withPermissions(action: () -> Unit) {
        val bluetoothOk = bluetoothPermissions.all(::granted)
        val notificationsOk = granted(Manifest.permission.POST_NOTIFICATIONS)
        if (bluetoothOk && (notificationsOk || asked)) {
            action()
            return
        }
        asked = true
        pendingAction = action
        permissionLauncher.launch(permissions)
    }
}
