package com.msp1974.vacompanion.utils

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.msp1974.vacompanion.device.DeviceManager
import com.msp1974.vacompanion.device.authentication.HttpClientProvider
import com.msp1974.vacompanion.settings.APPConfig
import io.github.z4kn4fein.semver.toVersion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import timber.log.Timber
import java.io.File

data class LatestRelease(
    var version: String = "0.0.0",
    var downloadURL: String = ""
)

class Updater(val context: Context, private val deviceManager: DeviceManager) {
    private val log = Logger()
    private val client = HttpClientProvider().get()
    private val downloadClient by lazy { HttpClientProvider(ignoreSslErrors = { deviceManager.config.ignoreSSLErrors }).get() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloading = AtomicBoolean(false)
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

    /**
     * The release the HA integration has staged for this device, if any. It is served from the
     * integration's /vaca static path, so airgapped devices need no access to GitHub.
     */
    private fun hostedRelease(): LatestRelease? {
        val config = deviceManager.config
        if (config.apkUpdateVersion.isBlank() || config.apkUpdateFile.isBlank()) return null
        val base = deviceManager.authenticationManager.getBaseUrl().toString().removeSuffix("/")
        return LatestRelease(config.apkUpdateVersion, "$base/vaca/custom/apk/${Uri.encode(config.apkUpdateFile)}")
    }

    /** True when the update comes from the HA server rather than GitHub. */
    val usesHomeAssistant: Boolean get() = hostedRelease() != null

    suspend fun getLatestRelease(forceUpdate: Boolean = true): LatestRelease {
        hostedRelease()?.let {
            latestRelease.version = it.version
            latestRelease.downloadURL = it.downloadURL
            return latestRelease
        }
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
        hostedRelease()?.let {
            if (runCatching { it.version.toVersion() >= version.toVersion() }.getOrDefault(false)) {
                latestRelease.version = it.version
                latestRelease.downloadURL = it.downloadURL
                return latestRelease
            }
        }
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
                val installed = context.packageManager.getPackageInfo(
                    context.packageName,
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
            // The manual update path runs in a different Updater than the one that looked the release up
            val downloadURL = latestRelease.downloadURL.ifEmpty { hostedRelease()?.downloadURL ?: "" }
            if (downloadURL != "" && downloadURL == hostedRelease()?.downloadURL) {
                // DownloadManager only runs on a network Android has validated as having internet,
                // so it never starts on an airgapped LAN. Fetch from the HA server ourselves.
                downloadFromHomeAssistant(downloadURL, callback)
            } else if (downloadURL != "") {
                val request =
                    DownloadManager.Request(downloadURL.toUri())
                val downloadManager =
                    context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

                val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "vaca.apk")
                if (file.exists()) {
                    log.d("File exists: $file")
                    file.delete()
                }
                request.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "vaca.apk")

                val downloadId = downloadManager.enqueue(request)
                waitForDownloadToComplete(downloadId, callback)
            }
        } catch (e: Exception) {
            log.e(e.message.toString())
        }
    }

    private fun downloadFromHomeAssistant(url: String, callback: (uri: String) -> Unit) {
        // A second tap on Update must not start a second download over the same file
        if (!downloading.compareAndSet(false, true)) {
            log.d("APK download already running")
            return
        }
        scope.launch {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            val file = File(dir, "vaca.apk")
            val part = File(dir, "vaca.apk.part")
            val ok = try {
                part.delete()
                downloadClient.prepareGet(url).execute { response ->
                    if (!response.status.isSuccess()) error("HTTP ${response.status.value}")
                    val expected = response.contentLength() ?: -1L
                    val channel = response.bodyAsChannel()
                    var total = 0L
                    var nextLog = 10L * 1024 * 1024
                    val started = System.currentTimeMillis()
                    part.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (!channel.isClosedForRead) {
                            val read = channel.readAvailable(buffer)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                            total += read
                            if (total >= nextLog) {
                                log.d("APK download: ${total / 1024 / 1024} MB of ${expected / 1024 / 1024} MB")
                                nextLog += 10L * 1024 * 1024
                            }
                        }
                    }
                    log.d("APK download read $total bytes in ${System.currentTimeMillis() - started} ms")
                    if (expected > 0 && total != expected) {
                        error("incomplete download: $total of $expected bytes")
                    }
                }
                file.delete()
                part.renameTo(file)
            } catch (e: Exception) {
                log.e("APK download from $url failed: ${e.message}")
                part.delete()
                false
            }
            downloading.set(false)
            withContext(Dispatchers.Main) {
                callback(if (ok) getContentURIFromFile("").toString() else "")
            }
        }
    }

    private fun waitForDownloadToComplete(id: Long, callback: (uri: String) -> Unit) {
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
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
        val f = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        val uri = FileProvider.getUriForFile(context.applicationContext, context.packageName + ".provider", File(f, "vaca.apk"))
        return uri
    }
}
