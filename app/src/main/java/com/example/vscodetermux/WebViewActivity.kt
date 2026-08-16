package com.example.vscodetermux

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

class WebViewActivity : AppCompatActivity() {

    private val fragmentTag = "webview_fragment"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_webview)

        if (!RootfsManager(this).isBootstrapped()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        // WIP
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                if (intent.type == "text/plain" && intent.hasExtra(Intent.EXTRA_TEXT)) {
                    intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
                        // WebViewFragment's ClipboardBridge already routes
                        // code-server's navigator.clipboard.readText() through

                        // needs a normal paste inside the editor to pick it up.
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("shared", text))
                    }
                } else {
                    intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uri ->
                        importSharedUris(listOf(uri))
                    }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris ->
                    importSharedUris(uris)
                }
            }
        }

        
        // If retained fragment is found, attach it to the container via replace()
        var fragment = supportFragmentManager.findFragmentByTag(fragmentTag) as? WebViewFragment
        if (fragment == null) {
            fragment = WebViewFragment()
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.webViewContainer, fragment, fragmentTag)
            .commit()
        supportFragmentManager.executePendingTransactions()
        
        // Hide the spinner if already loaded.
        // (onPageFinished won't fire again for an already-loaded page).
        if (fragment.isWebViewReady) {
            findViewById<android.widget.ProgressBar>(R.id.loadingSpinner).visibility = android.view.View.GONE
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val fragment = supportFragmentManager.findFragmentByTag(fragmentTag) as? WebViewFragment
                val webView = fragment?.takeIf { it.isWebViewReady }?.webView

                if (webView?.canGoBack() == true) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })
    }

    /** contentResolver access needs the Activity, so this stays here rather
     *  than in VscodeTermuxApp — the actual copy logic does live there. */
    private fun importSharedUris(uris: List<Uri>) {
        val resolver = contentResolver
        Thread {
            uris.forEach { VscodeTermuxApp.instance.importSharedFile(it, resolver) }
        }.start()
    }
}
