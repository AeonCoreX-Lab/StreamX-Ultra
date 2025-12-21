package com.aeoncorex.streamx.services

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import com.aeoncorex.streamx.model.GitHubRelease
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

object UpdateChecker {

    private const val GITHUB_API_URL = "https://api.github.com/repos/cybernahid-dev/StreamX-Ultra/releases/latest"

    private fun getCurrentVersionCode(context: Context): Int {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode
        } catch (e: Exception) { -1 }
    }

    suspend fun checkForUpdate(context: Context): GitHubRelease? {
        return withContext(Dispatchers.IO) {
            try {
                val json = URL(GITHUB_API_URL).readText()
                val latestRelease = Gson().fromJson(json, GitHubRelease::class.java)

                val remoteVersionCode = latestRelease.body.lines()
                    .find { it.startsWith("versionCode:") }
                    ?.substringAfter(":")?.trim()?.toIntOrNull() ?: -1
                
                val currentVersionCode = getCurrentVersionCode(context)

                if (remoteVersionCode > currentVersionCode) latestRelease else null
            } catch (e: Exception) { null }
        }
    }

    fun downloadAndInstall(context: Context, release: GitHubRelease) {
        val apkAsset = release.assets.find { it.browser_download_url.endsWith(".apk") } ?: return
        val downloadUrl = apkAsset.browser_download_url
        val fileName = "StreamX-Ultra-v${release.tag_name}.apk"
        
        // ডিলিট করুন যদি একই নামের পুরনো ফাইল থাকে
        val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
        if (file.exists()) file.delete()

        val request = DownloadManager.Request(Uri.parse(downloadUrl))
            .setTitle("Downloading StreamX Ultra Update")
            .setDescription("Version ${release.tag_name}")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = downloadManager.enqueue(request)

        val onComplete = object : BroadcastReceiver() {
            override fun onReceive(ctxt: Context, intent: Intent) {
                if (downloadId == intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)) {
                    context.unregisterReceiver(this)
                    installApk(context, file)
                }
            }
        }
        context.registerReceiver(onComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
    }

    private fun installApk(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(installIntent)
    }
}