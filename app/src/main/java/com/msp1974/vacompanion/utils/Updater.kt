package com.msp1974.vacompanion.utils

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.msp1974.vacompanion.device.authentication.HttpClientProvider
import com.msp1974.vacompanion.settings.APPConfig
import io.github.z4kn4fein.semver.toVersion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import timber.log.Timber
import java.io.File

data class LatestRelease(
    var version: String = "0.0.0",
    var downloadURL: String = ""
)

class Updater(val activity: Activity) {
    private val log = Logger()
    private val client = HttpClientProvider().get()
    var latestRelease: LatestRelease = LatestRelease("0.0.0", "")

    private fun getDownloadLink(data: JsonObject): String {
        try {
            val assets = data.getOrDefault("assets", null)
            if (assets != null) {
                for (asset in assets as List<JsonObject>) {
                    if (asset.getOrDefault(
                            "content_type",
                            ""
                        ).toString()
                            .replace("\"", "") == "application/vnd.android.package-archive"
                    ) {
                        return asset.getOrDefault("browser_download_url", "").toString()
                                .replace("\"", "")
                    }
                }
            }
        } catch (e: Exception) {
            log.e(e.message.toString())
        }
        return ""
    }

    suspend fun getLatestRelease(forceUpdate: Boolean = true): LatestRelease {
        if (latestRelease.version == "0.0.0" || forceUpdate) {
            val data = githubApiGET("${APPConfig.GITHUB_API_URL}/latest")
            latestRelease.version =
                data.getOrDefault("name", "0.0.0").toString().replace("v", "").replace("\"", "")
            latestRelease.downloadURL = getDownloadLink(data)
            Timber.d("Latest release: ${latestRelease.version} -> ${latestRelease.downloadURL}")
        }
        return latestRelease
    }

    suspend fun getVersionRelease(version: String): LatestRelease {
        val data = githubApiGET("${APPConfig.GITHUB_API_URL}/tags/v$version")
        latestRelease.version =
            data.getOrDefault("name", "0.0.0").toString().replace("v", "").replace("\"", "")
        latestRelease.downloadURL = getDownloadLink(data)
        return latestRelease
    }

    suspend fun isUpdateAvailable(version: String = ""): Boolean {
        var release: LatestRelease
        try {
            if (version != "") {
                release = getVersionRelease(version)
            } else {
                release = getLatestRelease()
            }
            if (release.version != "0.0.0") {
                val installed = activity.packageManager.getPackageInfo(
                    activity.packageName,
                    0
                ).versionName.toString()
                return release.version.toVersion() > installed.toVersion()
            }
            return false
        } catch (e: Exception) {
            Timber.e(e.message.toString())
            return false
        }
    }

    fun requestDownload(callback: (uri: String) -> Unit) {
        try {
            if (latestRelease.downloadURL != "") {
                val request =
                    DownloadManager.Request(latestRelease.downloadURL.toUri())
                val downloadManager =
                    activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

                val file = File(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "vaca.apk")
                if (file.exists()) {
                    log.d("File exists: $file")
                    file.delete()
                }
                request.setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, "vaca.apk")

                val downloadId = downloadManager.enqueue(request)
                waitForDownloadToComplete(downloadId, callback)
            }
        } catch (e: Exception) {
            log.e(e.message.toString())
        }
    }

    private fun waitForDownloadToComplete(id: Long, callback: (uri: String) -> Unit) {
        val downloadManager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor: Cursor = downloadManager.query(DownloadManager.Query().setFilterById(id))
        if (cursor.moveToNext()) {
            val colIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
            if (colIdx >= 0) {
                val status = cursor.getInt(colIdx)
                when (status) {
                    DownloadManager.STATUS_FAILED -> {
                        log.e("APK download failed")
                        cursor.close()
                        callback("")
                        return
                    }
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val uriColIdx = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                        val localUri = cursor.getString(uriColIdx)
                        cursor.close()
                        log.d("APK download completed")
                        callback(getContentURIFromFile(localUri).toString())
                        return
                    }
                }
            }
        }
        cursor.close()
        Handler(Looper.getMainLooper()).postDelayed({
            waitForDownloadToComplete(id, callback)
        }, 1000)


    }

    private suspend fun githubApiGET(url: String): JsonObject {
        try {
            val response = client.get(url)
            if (!response.status.isSuccess()) {
                Timber.e("Unexpected code $response")
                return buildJsonObject { put("unexpected_code", response.status.value) }
            }
            val responseBody = response.bodyAsText()
            return JsonObject(Json.parseToJsonElement(responseBody).jsonObject)
        } catch (e: Exception) {
            log.e(e.message.toString())
            return buildJsonObject { put("error",e.message.toString() ) }
        }
    }

    private fun getContentURIFromFile(file: String): Uri {
        val f = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        val uri = FileProvider.getUriForFile(activity.applicationContext, activity.packageName + ".provider", File(f, "vaca.apk"))
        return uri
    }
}
