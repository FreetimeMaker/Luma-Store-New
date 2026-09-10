package com.freetime.lumastore.install

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object ApkInstaller {
    fun downloadAndInstall(
        context: Context,
        packageName: String,
        apkUrl: String,
        onProgress: (Int) -> Unit,
        onReady: () -> Unit,
        onError: (Throwable) -> Unit
    ) {
        Thread {
            runCatching {
                val dir = File(context.cacheDir, "apks").apply { mkdirs() }
                val target = File(dir, "${packageName.replace('.', '_')}.apk")
                val connection = URL(apkUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = 15_000
                connection.readTimeout = 60_000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "Luma-Store/1.0")
                val total = connection.contentLengthLong
                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var read: Int
                        var downloaded = 0L
                        var lastProgress = -1
                        while (input.read(buffer).also { read = it } >= 0) {
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (total > 0) {
                                val progress = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                                if (progress != lastProgress) {
                                    lastProgress = progress
                                    onProgress(progress)
                                }
                            }
                        }
                    }
                }

                val uri: Uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    target
                )
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                onReady()
                context.startActivity(intent)
            }.onFailure(onError)
        }.start()
    }
}
