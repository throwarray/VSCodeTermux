package com.example.vscodetermux

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import androidx.fragment.app.Fragment

/**
 * Holds the WebView in a retained Fragment (retainInstance = true)
 * Returning to the Activity (e.g. via recents) doesn't reload or lose editor/terminal state.
 * The Fragment is created synchronously and is deliberately not a bound Service
 */
class WebViewFragment : Fragment() {

    lateinit var webView: WebView
        private set

    val isWebViewReady: Boolean
        get() = ::webView.isInitialized

    private var loadedPort = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        retainInstance = true
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        if (!::webView.isInitialized) {
            webView = WebView(requireContext())
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                useWideViewPort = true
                loadWithOverviewMode = true
                userAgentString = userAgentString.replace("; wv", "").replace("Mobile", "")
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                databasePath = requireActivity().filesDir.absolutePath
            }

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    CodeServerAutoLogin.maybeInject(view, url)
                    activity?.findViewById<ProgressBar>(R.id.loadingSpinner)?.visibility = View.GONE
                    // code-server does a full page navigation (not client-side
                    // routing) whenever a different folder is opened, which
                    // pushes a WebView history entry. Without clearing it, the
                    // Android back button pops into the previous folder's
                    // stale page instead of leaving the app — VS Code's own
                    // UI (tabs, breadcrumbs) is how "back" should work here,
                    // not browser history.
                    view.clearHistory()
                    // navigator.clipboard exists per spec but silently fails
                    // Shimming ClipboardManager through a JS interface (AndroidClipboardBridge)
                    val bridgeJs = """
                        (function() {
                            if (!navigator.clipboard) { navigator.clipboard = {}; }
                            navigator.clipboard.writeText = function(text) {
                                AndroidClipboardBridge.setClipboard(String(text));
                                return Promise.resolve();
                            };
                            navigator.clipboard.readText = function() {
                                return Promise.resolve(AndroidClipboardBridge.getClipboard() || "");
                            };
                        })();
                    """.trimIndent()
                    webView.evaluateJavascript(bridgeJs, null)
                }
            }
            webView.webChromeClient = WebChromeClient()
            webView.addJavascriptInterface(ClipboardBridge(requireContext()), "AndroidClipboardBridge")

            if (savedInstanceState != null) {
                webView.restoreState(savedInstanceState)
                loadedPort = VscodeTermuxApp.instance.codeServerPort()
            }
        } else {
            // Fragment view was recreated (e.g. after a config change)
            // but the WebView itself already exists from before. If the WebView
            // still has a parent from the previous view hierarchy, we need to
            // remove it before returning it (Fragment manager will add it to the
            // new container).
            (webView.parent as? ViewGroup)?.removeView(webView)
        }
        loadCurrentPortIfChanged()
        return webView
    }

    /**
     * Loads code-server's URL if we haven't loaded anything yet, or if the
     * configured port no longer matches what's currently displayed — e.g.
     * the user changed the port and restarted the server while this
     * Fragment (and its WebView) was retained in the background. Without
     * this, the WebView would just keep silently showing/retrying the old
     * port's (now dead) connection.
     */
    private fun loadCurrentPortIfChanged() {
        val currentPort = VscodeTermuxApp.instance.codeServerPort()
        if (loadedPort == currentPort) return
        if (loadedPort != -1) {
            // Clean up the old port's leftover per-origin storage
            // (localStorage/IndexedDB/etc.) — otherwise every port change
            // just leaves another orphaned origin's data sitting around
            // indefinitely with nothing left able to reach or clear it.
            android.webkit.WebStorage.getInstance().deleteOrigin("https://127.0.0.1:$loadedPort")
        }
        loadedPort = currentPort
        webView.loadUrl(VscodeTermuxApp.instance.codeServerUrl())
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::webView.isInitialized) webView.saveState(outState)
    }

    override fun onDestroyView() {
        // Deliberately not destroying the WebView;
        // torn down in onDestroy(), when the fragment is disposed.
        super.onDestroyView()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }

    /** Bridged methods added to the JavaScript context */
    private class ClipboardBridge(private val context: Context) {
        @JavascriptInterface
        fun setClipboard(text: String) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("code-server", text))
        }

        @JavascriptInterface
        fun getClipboard(): String {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            return clipboard.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(context)?.toString() ?: ""
        }
    }
}
