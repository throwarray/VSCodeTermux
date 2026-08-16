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

        var deferFragmentSetup = false

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
                        deferFragmentSetup = true
                        importAndOpen(listOf(uri))
                    }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris ->
                    deferFragmentSetup = true
                    importAndOpen(uris)
                }
            }
            Intent.ACTION_VIEW -> {
                intent.data?.let { uri ->
                    deferFragmentSetup = true
                    importAndOpen(listOf(uri))
                }
            }
        }

        if (!deferFragmentSetup) setupWebViewFragment(null)
    }

    /**
     * Imports each shared file (see VscodeTermuxApp.importSharedFile — off
     * the main thread, this does blocking I/O), then opens code-server
     * with all of them as tabs via VS Code Web's own documented `payload`
     * query parameter. That parameter is only read once at initial page
     * load — not a live-session API — which is exactly what this is: a
     * brand new WebViewFragment/WebView every time, never reusing an
     * already-loaded one, so there's no "already loaded, can't renavigate"
     * problem to work around here.
     */
    private fun importAndOpen(uris: List<Uri>) {
        val resolver = contentResolver
        // The two share-sheet entries are the same Activity via
        // activity-alias (see manifest) — intent.component carries which one was tapped
        val openAsFolder = intent.component?.className?.endsWith(".OpenFolderShareTarget") == true
        Thread {
            val imported = uris.mapNotNull { VscodeTermuxApp.instance.importSharedFile(it, resolver) }
            runOnUiThread {
                val url = imported.takeIf { it.isNotEmpty() }
                    ?.let { VscodeTermuxApp.instance.codeServerOpenFileUrl(it, openAsFolder) }
                setupWebViewFragment(url)
            }
        }.start()
    }

    /** contentResolver access needs the Activity, so this stays here rather
     *  than in VscodeTermuxApp — the actual copy logic does live there. */
    private fun setupWebViewFragment(initialUrl: String?) {
        // If retained fragment is found, attach it to the container via replace()
        var fragment = supportFragmentManager.findFragmentByTag(fragmentTag) as? WebViewFragment
        if (fragment == null) {
            fragment = WebViewFragment().apply {
                if (initialUrl != null) {
                    arguments = Bundle().apply { putString(ARG_INITIAL_URL, initialUrl) }
                }
            }
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
                val f = supportFragmentManager.findFragmentByTag(fragmentTag) as? WebViewFragment
                val webView = f?.takeIf { it.isWebViewReady }?.webView

                if (webView?.canGoBack() == true) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })
    }

    companion object {
        const val ARG_INITIAL_URL = "initial_url"
    }
}
