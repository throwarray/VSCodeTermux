package com.example.vscodetermux

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

/**
 * A plain in-app browser with an address bar — registered as an ACTION_VIEW
 * target for http/https (see manifest), so it's offered as an option
 * whenever anything tries to open a web link, including
 * WebViewFragment's own external-link redirect. That's the actual
 * mechanism here: VS Code Web has no stable API to intercept its own
 * internal link clicks (registerExternalUriOpener is still a proposed,
 * unstable API — checked directly against microsoft/vscode's source, not
 * assumed), so this works around that at the OS level instead of trying
 * to reach inside VS Code Web for something it doesn't expose.
 */
class BrowserActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var addressBar: EditText
    private lateinit var backButton: ImageButton
    private lateinit var forwardButton: ImageButton
    private lateinit var reloadStopButton: ImageButton
    private var isLoading = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browser)

        webView = findViewById(R.id.browserWebView)
        addressBar = findViewById(R.id.addressBar)
        backButton = findViewById(R.id.backButton)
        forwardButton = findViewById(R.id.forwardButton)
        reloadStopButton = findViewById(R.id.reloadStopButton)
        val progress = findViewById<ProgressBar>(R.id.browserLoadingSpinner)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.visibility = if (newProgress in 1..99) android.view.View.VISIBLE else android.view.View.GONE
                progress.progress = newProgress
            }
        }

        webView.webViewClient = object : WebViewClient() {
            // Only http/https stay in this browser — mailto:, tel:, market:,
            // intent: and similar are handed off to whatever app actually
            // handles them, same reasoning as WebViewFragment's own
            // external-link handling.
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: android.webkit.WebResourceRequest
            ): Boolean {
                if (request.url.scheme == "http" || request.url.scheme == "https") return false
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, request.url))
                    true
                } catch (e: ActivityNotFoundException) {
                    true
                }
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                addressBar.setText(url)
                isLoading = true
                updateToolbarState()
            }

            override fun onPageFinished(view: WebView, url: String) {
                isLoading = false
                updateToolbarState()
            }
        }

        backButton.setOnClickListener { if (webView.canGoBack()) webView.goBack() }
        forwardButton.setOnClickListener { if (webView.canGoForward()) webView.goForward() }
        reloadStopButton.setOnClickListener {
            if (isLoading) webView.stopLoading() else webView.reload()
        }

        addressBar.setOnEditorActionListener { _, actionId, event ->
            val submitted = actionId == EditorInfo.IME_ACTION_GO ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (submitted) navigateToAddressBarText()
            submitted
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        updateToolbarState()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val text = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.toString()
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }?.trim()
        if (text.isNullOrEmpty()) return
        loadAddress(text)
    }

    private fun navigateToAddressBarText() {
        val text = addressBar.text.toString().trim()
        if (text.isEmpty()) return
        loadAddress(text)
        currentFocus?.let {
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(it.windowToken, 0)
        }
    }

    private fun loadAddress(text: String) {
        val url = if (text.contains("://")) text else "https://$text"
        addressBar.setText(url)
        webView.loadUrl(url)
    }

    /** Back/forward enabled state, and the reload button doubling as stop
     *  while a page is actively loading — same convention as any browser. */
    private fun updateToolbarState() {
        backButton.isEnabled = webView.canGoBack()
        backButton.alpha = if (backButton.isEnabled) 1.0f else 0.35f
        forwardButton.isEnabled = webView.canGoForward()
        forwardButton.alpha = if (forwardButton.isEnabled) 1.0f else 0.35f
        reloadStopButton.setImageResource(
            if (isLoading) R.drawable.ic_browser_stop else R.drawable.ic_browser_reload
        )
        reloadStopButton.contentDescription = if (isLoading) "Stop" else "Reload"
    }
}
