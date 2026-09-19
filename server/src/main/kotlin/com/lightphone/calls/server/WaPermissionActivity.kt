package com.lightphone.calls.server

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import com.thelightphone.sdk.shared.LightServiceMethod

/**
 * AOSP runtime-permission host for the SDK's permission-request flow (wired
 * as [com.thelightphone.sdk.server.LightSdkServer.permissionActivity] in
 * [ServerBootstrapProvider]). Asks the standard way — the user grants in the
 * system dialog. Reads the requested permission from
 * [LightServiceMethod.RequestPermissionComponent.PERMISSION_NAME_KEY].
 */
class WaPermissionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val permission = intent.getStringExtra(
            LightServiceMethod.RequestPermissionComponent.PERMISSION_NAME_KEY
        ) ?: Manifest.permission.RECORD_AUDIO
        requestPermissions(arrayOf(permission), REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        finish()
    }

    companion object {
        const val REQUEST_CODE = 1
    }
}
