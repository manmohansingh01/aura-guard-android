package com.auraguard.app

import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import com.auraguard.app.core.AuraViewModel
import com.auraguard.app.ui.navigation.AuraNavHost
import com.auraguard.app.ui.theme.AuraGuardTheme
import com.auraguard.app.ui.theme.OpsBackground

/**
 * Single-activity host. Owns the one system interaction that must happen
 * at the Activity level — the MediaProjection screen-capture permission
 * dialog — and otherwise just hosts the Compose nav graph over the single
 * app-wide AuraViewModel (the pipeline orchestrator).
 *
 * This deliberately does NOT use `by viewModels()`: the pipeline must
 * survive and keep running even when this Activity isn't the thing on
 * screen (an operator switches away to the drone app while AURA Guard's
 * floating overlay — see ScreenCaptureService/OverlayController — keeps
 * drawing detections on top of it), so the ViewModel is owned by
 * AuraGuardApp instead, and this Activity and the overlay both just read
 * the same instance rather than each getting their own.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: AuraViewModel get() = (application as AuraGuardApp).auraViewModel

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.onScreenCapturePermissionResult(result.resultCode, result.data)
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Notification permission is only needed for the foreground-service notice; capture still works without it. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep the display on while this is a live command-and-control surface — an operator
        // actively watching a perimeter feed should never have the screen dim/lock underneath them.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            AuraGuardTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = OpsBackground) {
                    AuraNavHost(
                        viewModel = viewModel,
                        onRequestScreenCapture = { launchScreenCapturePermission() }
                    )
                }
            }
        }
    }

    private fun launchScreenCapturePermission() {
        viewModel.requestScreenCapture()
        val manager = getSystemService(MediaProjectionManager::class.java)
        screenCaptureLauncher.launch(manager.createScreenCaptureIntent())
    }
}
