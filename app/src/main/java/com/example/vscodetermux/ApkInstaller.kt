package com.example.vscodetermux

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * Unused PackageInstaller UI without root. (* after copying the apk to shared storage)
 * Usage from an Activity:
 *   val installer = ApkInstaller(this) { }
 *   installer.pickAndInstall()
 *
 * Deliberately just a file picker rather than scanning known build-output
 * paths — it doesn't need to know anything about the project or destination
 */
class ApkInstaller(
    private val activity: AppCompatActivity,
    private val onPicked: (Uri) -> Unit = {}
) {
    private val picker: ActivityResultLauncher<Array<String>> =
        activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                onPicked(uri)
                installFromUri(activity, uri)
            }
        }

    fun pickAndInstall() {
        picker.launch(arrayOf("application/vnd.android.package-archive"))
    }

    companion object {
        /**
         * Fires the Android package installer flow for a content:// Uri.
         * The host package installer handles the APK install prompt
         */
        fun installFromUri(context: Context, uri: Uri) {
            val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            }
            context.startActivity(intent)
        }
    }
}
