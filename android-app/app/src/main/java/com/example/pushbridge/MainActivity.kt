package com.example.pushbridge

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.example.pushbridge.data.PushBridgeFirebaseManager
import com.example.pushbridge.theme.PushBridgeTheme

class MainActivity : ComponentActivity() {

    private val permissionRefreshTrigger = mutableStateOf(0)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        permissionRefreshTrigger.value++
        PushBridgeFirebaseManager.registerDeviceState(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        PushBridgeFirebaseManager.initialize(applicationContext)

        setContent {
            val activeThemeId by PushBridgeFirebaseManager.selectedTheme.collectAsState()
            PushBridgeTheme(themeId = activeThemeId) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainNavigation(
                        onRequestSmsPermission = { requestSmsAndNotificationPermissions() },
                        permissionTrigger = permissionRefreshTrigger.value
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        permissionRefreshTrigger.value++
        PushBridgeFirebaseManager.registerDeviceState(this)
        PushBridgeFirebaseManager.touchHeartbeat(this)
    }

    private fun requestSmsAndNotificationPermissions() {
        val permissionsToRequest = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_MMS,
            Manifest.permission.READ_CONTACTS
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val ungranted = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (ungranted.isNotEmpty()) {
            requestPermissionLauncher.launch(ungranted.toTypedArray())
        }
    }
}
