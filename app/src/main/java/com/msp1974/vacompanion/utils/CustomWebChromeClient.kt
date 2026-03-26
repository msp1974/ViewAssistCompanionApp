package com.msp1974.vacompanion.utils

import android.content.Context
import android.content.pm.PackageManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import androidx.core.app.ActivityCompat

class CustomWebChromeClient(private val context: Context) : WebChromeClient() {
    override fun onPermissionRequest(request: PermissionRequest?) {
        val alreadyGranted = ArrayList<String>()
        val toBeGranted = ArrayList<String>()
        request?.resources?.forEach {
            when (it) {
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> {
                    if (ActivityCompat.checkSelfPermission(
                            context,
                            android.Manifest.permission.RECORD_AUDIO,
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        alreadyGranted.add(it)
                    } else {
                        toBeGranted.add(android.Manifest.permission.RECORD_AUDIO)
                    }
                }
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> {
                    if (ActivityCompat.checkSelfPermission(
                            context,
                            android.Manifest.permission.CAMERA,
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        alreadyGranted.add(it)
                    } else {
                        toBeGranted.add(android.Manifest.permission.CAMERA)
                    }
                }
            }
        }
        if (alreadyGranted.isNotEmpty()) {
            request?.grant(alreadyGranted.toTypedArray())
        }
    }
}