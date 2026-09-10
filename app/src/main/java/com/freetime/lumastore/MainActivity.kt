package com.freetime.lumastore

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.freetime.lumastore.data.AppRepository
import com.freetime.lumastore.install.ApkInstaller
import com.freetime.lumastore.ui.theme.LumaStoreTheme

class MainActivity : ComponentActivity() {
    private val repository = AppRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LumaStoreTheme {
                StoreScreen(
                    repository = repository,
                    canInstallPackages = { canInstallUnknownApps() },
                    requestInstallPermission = { openInstallPermission() },
                    install = { app, onProgress, onReady, onError ->
                        ApkInstaller.downloadAndInstall(
                            context = this,
                            packageName = app.id,
                            apkUrl = app.apkUrl,
                            onProgress = { runOnUiThread { onProgress(it) } },
                            onReady = { runOnUiThread(onReady) },
                            onError = { error -> runOnUiThread { onError(error) } }
                        )
                    }
                )
            }
        }
    }

    private fun canInstallUnknownApps(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()

    private fun openInstallPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }
}
