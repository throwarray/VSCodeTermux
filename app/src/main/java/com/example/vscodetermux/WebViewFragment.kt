package com.example.vscodetermux

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
    private var hadLoadError = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        retainInstance = true
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        if (!::webView.isInitialized) {
            webView = WebView(requireContext())

            webView.setBackgroundColor(android.graphics.Color.BLACK)
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
                setSupportMultipleWindows(true)
                javaScriptCanOpenWindowsAutomatically = true
            }

            webView.webViewClient = object : WebViewClient() {
                // A link to an external host would navigate the main WebView. 
                // Instead 127.0.0.1 stays in-app; anything else goes to the
                // system browser instead of clobbering the editor.
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: android.webkit.WebResourceRequest
                ): Boolean {
                    if (!request.isForMainFrame || request.url.host in LOCAL_HOSTS) return false
                    return try {
                        view.context.startActivity(Intent(Intent.ACTION_VIEW, request.url))
                        true
                    } catch (e: android.content.ActivityNotFoundException) {
                        false
                    }
                }

                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    hadLoadError = false
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: android.webkit.WebResourceRequest,
                    errorResponse: android.webkit.WebResourceResponse
                ) {
                    if (request.isForMainFrame) hadLoadError = true
                }

                override fun onReceivedError(
                    view: WebView,
                    request: android.webkit.WebResourceRequest,
                    error: android.webkit.WebResourceError
                ) {
                    if (request.isForMainFrame) hadLoadError = true
                }

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
            webView.webChromeClient = object : WebChromeClient() {
                // target="_blank" links (window.open()) — without this;(For
                // an in-*editor* preview specifically — e.g. Live Server —
                // that's VS Code's own Simple Browser extension/setting,
                // not something this outer shell controls.)
                override fun onCreateWindow(
                    view: WebView,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: android.os.Message
                ): Boolean {
                    val catcher = WebView(view.context)
                    catcher.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            v: WebView,
                            request: android.webkit.WebResourceRequest
                        ): Boolean {
                            try {
                                view.context.startActivity(Intent(Intent.ACTION_VIEW, request.url))
                            } catch (e: android.content.ActivityNotFoundException) {
                                // No browser installed — nothing to do.
                            }
                            return true
                        }
                    }
                    (resultMsg.obj as WebView.WebViewTransport).webView = catcher
                    resultMsg.sendToTarget()
                    return true
                }
            }
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
        val isFirstLoad = loadedPort == -1
        if (!isFirstLoad) {
            // Clean up the old port's leftover per-origin storage
            // (localStorage/IndexedDB/etc.) — otherwise every port change
            // just leaves another orphaned origin's data sitting around
            // indefinitely with nothing left able to reach or clear it.
            android.webkit.WebStorage.getInstance().deleteOrigin("https://127.0.0.1:$loadedPort")
        }
        loadedPort = currentPort
        val initialUrl = arguments?.getString(WebViewActivity.ARG_INITIAL_URL)
        webView.loadUrl(if (isFirstLoad && initialUrl != null) initialUrl else VscodeTermuxApp.instance.codeServerUrl())
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::webView.isInitialized) webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()

        if (::webView.isInitialized && hadLoadError) {
            webView.reload()
        }
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

    companion object {
        // Matches network_security_config.xml's own trusted-host scope.
        private val LOCAL_HOSTS = setOf("127.0.0.1", "localhost")
    }
}
