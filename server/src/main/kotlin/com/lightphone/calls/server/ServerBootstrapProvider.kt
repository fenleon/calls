package com.lightphone.calls.server

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.database.Cursor
import android.net.Uri
import com.thelightphone.sdk.server.ClientCertType
import com.thelightphone.sdk.server.ClientFilterLevel
import com.thelightphone.sdk.server.LightSdkServer
import java.security.MessageDigest

// SHA-256 fingerprint of sdk/keys/lightsdk-dev.jks (alias: lightsdk-dev). Any
// APK signed with the workspace dev key is treated as Light-SDK-signed.
private const val LIGHTSDK_DEV_CERT_SHA256 =
    "B9C33E29B0CCAD2BFF11ACAB55F65A3C517EF4BC92CD9C77785366FA353D5F28"

/**
 * Single-module build: the former companion's server wiring runs here, inside
 * the merged tool APK. A ContentProvider is the only app-start hook with a
 * real [Context] that is not part of the tool-plugin scanned module — it wires
 * the SDK server (client filter, cert check) so the tool can bind to its own
 * LightSdkService. Providers run after `LightSdkApplication`'s bind is queued
 * but before the tool's first binder call, so `LightSdkApplication`'s bind is
 * unaffected.
 */
class ServerBootstrapProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val context = context?.applicationContext ?: return false
        WaCallEngine.attach(context)
        WaSyncService.tryStart(context)

        with(LightSdkServer) {
            defaultClientFilterLevel = ClientFilterLevel.AllowLightSignedApks
            checkCert = { callingPackage -> checkLightSdkCert(context, callingPackage) }
            // Platform relay (PLATFORM-RELAY.md): self-serving app, so forward
            // what we don't consume ourselves — Calls uses no hardware keys,
            // and haptics preferences come from the real device.
            provideSdkSettings = { RelaySdkServerSettings(it) }
            onDeviceKeyEvent = { _, event -> PlatformRelay.sendDeviceKeyEvent(event) }
            // Runtime permission flow: the call screens request RECORD_AUDIO
            // through the SDK flow; this APK hosts the AOSP dialog activity
            // (the WifiPermissionActivity pattern) and narrows the grantable
            // set to the mic.
            permissionActivity = WaPermissionActivity::class.java
            androidPermissionAllowed = { _, permissionName ->
                permissionName == android.Manifest.permission.RECORD_AUDIO
            }
        }
        PlatformRelay.bind(context)
        return true
    }

    private fun checkLightSdkCert(context: Context, callingPackage: String): ClientCertType {
        val info = try {
            context.packageManager.getPackageInfo(
                callingPackage,
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
        } catch (e: PackageManager.NameNotFoundException) {
            return ClientCertType.Unknown
        }
        val signingInfo = info.signingInfo ?: return ClientCertType.Unknown
        val signers: Array<Signature> = if (signingInfo.hasMultipleSigners()) {
            signingInfo.apkContentsSigners
        } else {
            signingInfo.signingCertificateHistory
        }
        val md = MessageDigest.getInstance("SHA-256")
        val matches = signers.any { sig ->
            md.digest(sig.toByteArray()).toHexString()
                .equals(LIGHTSDK_DEV_CERT_SHA256, ignoreCase = true)
        }
        return if (matches) ClientCertType.LightSdkSignedUnverified else ClientCertType.Unknown
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02X".format(it) }

    // The provider exists for its onCreate only; no content is served.
    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}
