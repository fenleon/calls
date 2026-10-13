package com.lightphone.calls.screens

import android.Manifest
import com.thelightphone.sdk.checkPermission
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.shared.asKotlinResult

/** Whether RECORD_AUDIO is currently granted (queried through the SDK's
 *  server channel — the same path the request launcher uses). */
internal suspend fun micPermissionGranted(): Boolean =
    checkPermission(Manifest.permission.RECORD_AUDIO).asKotlinResult
        .map { it.permissionResult == LightServiceMethod.GetPermission.Result.Granted }
        .getOrDefault(false)

/** Whether CAMERA is currently granted — same query path as the mic. Video
 *  surfaces gate their taps on this and request through
 *  [rememberPermissionRequestLauncher] (the mic pattern); no grant means no
 *  camera open, never a crash. */
internal suspend fun cameraPermissionGranted(): Boolean =
    checkPermission(Manifest.permission.CAMERA).asKotlinResult
        .map { it.permissionResult == LightServiceMethod.GetPermission.Result.Granted }
        .getOrDefault(false)
