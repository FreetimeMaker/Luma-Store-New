package com.freetime.lumastore

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.freetime.lumastore.data.AppRepository
import com.freetime.lumastore.install.ApkInstaller
import com.freetime.lumastore.ui.theme.LumaStoreTheme

class MainActivity : ComponentActivity() {
    private val repository by lazy { AppRepository(applicationContext) }
    private val installedAppsRevision = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val revision = installedAppsRevision.intValue
            var showSettings by rememberSaveable { mutableStateOf(false) }

            LumaStoreTheme {
                if (showSettings) {
                    SettingsScreen(
                        repository = repository,
                        onBack = { showSettings = false },
                        onSourcesChanged = { }
                    )
                } else {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                                .padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = { showSettings = true }) {
                                Text("Einstellungen")
                            }
                        }

                        Box(modifier = Modifier.weight(1f)) {
                            StoreScreen(
                                repository = repository,
                                installedAppsRevision = revision,
                                installedVersionCode = { packageName -> installedVersionCode(packageName) },
                                installedVersionName = { packageName -> installedVersionName(packageName) },
                                openInstalledApp = { packageName -> openInstalledApp(packageName) },
                                canInstallPackages = { canInstallUnknownApps() },
                                requestInstallPermission = { openInstallPermission() },
                                install = { app, onProgress, onReady, onError ->
                                    ApkInstaller.downloadAndInstall(
                                        context = this@MainActivity,
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
            }
        }
    }

    override fun onResume() {
        super.onResume()
        installedAppsRevision.intValue++
    }

    private fun installedVersionCode(packageName: String): Long? = runCatching {
        val info = packageManager.getPackageInfo(packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }.getOrNull()

    private fun installedVersionName(packageName: String): String? = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName
    }.getOrNull()

    private fun openInstalledApp(packageName: String): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        return true
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
