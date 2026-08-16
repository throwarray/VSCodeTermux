package com.example.vscodetermux

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service

import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder

import android.webkit.CookieManager;
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest

import android.widget.FrameLayout
import androidx.annotation.UiThread

class WebViewService : Service() {

    private lateinit var webView: WebView
    private lateinit var webViewContainer: FrameLayout
    private lateinit var webViewCookies: CookieManager

    inner class LocalBinder : Binder() {
        fun getWebView(): WebView = webView
        fun getContainer(): FrameLayout = webViewContainer
        fun getCookies(): CookieManager = webViewCookies
    }

    override fun onCreate() {
        super.onCreate()
        setupWebView()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundIfNeeded()
        return START_STICKY
    }

    private fun startForegroundIfNeeded() {
        val channelId = "webview_service_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "VS Code WebView"
            val descriptionText = "Keeps the WebView running when in the background."
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(channelId, name, importance).apply {
                description = descriptionText
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setContentTitle("VS Code WebView")
                .setContentText("The embedded editor is running.")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build()
        } else {
            Notification.Builder(this)
                .setContentTitle("VS Code WebView")
                .setContentText("The embedded editor is running.")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build()
        }
        startForeground(1, notification)
    }

    @UiThread
    private fun setupWebView() {
        webViewContainer = FrameLayout(this)

        webView = WebView(this).apply {
            // fix flash of white content.
            setBackgroundColor(android.graphics.Color.BLACK)
            settings.apply {
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
                databasePath = filesDir.absolutePath
            }

            // IDEA cache / database as mounted paths they can be cleaned.

            // The Interface is only needed for the clipboard methods.
            // IDEA: If extended consider adding a map of listeners for
            // onMessage actions originating from the within webview.
            // Custom interface behaviors can then be handled externally
            // without modifying the service and interface directly.

            addJavascriptInterface(object {
                @android.webkit.JavascriptInterface
                fun setClipboard(text: String) {
                    try {
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("web", text))
                    } catch (_: Exception) {
                    }
                }

                @android.webkit.JavascriptInterface
                fun getClipboard(): String {
                    return try {
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this@WebViewService)?.toString() ?: ""
                    } catch (_: Exception) {
                        ""
                    }
                }
            }, "AndroidClipboardBridge")

            webViewClient = object : WebViewClient() {

                //override fun shouldOverrideUrlLoading(_view: WebView, _request: WebResourceRequest): Boolean {
                //    return false
                //}

                override fun onPageFinished(view: WebView, url: String) {
                    CodeServerAutoLogin.maybeInject(view, url)
                    // See WebViewFragment's onPageFinished for why.
                    view.clearHistory()
                    // NOTE Add the bridge on load
                    try {
                        val bridgeJs = "(function(){if(!navigator.clipboard){navigator.clipboard={writeText:function(t){AndroidClipboardBridge.setClipboard(String(t));return Promise.resolve();},readText:function(){return Promise.resolve(AndroidClipboardBridge.getClipboard()||\\\"\\\")}}}})();"
                        view.evaluateJavascript(bridgeJs, null)
                    } catch (_: Exception) {
                    }
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    if (request.resources.any { it.contains("clipboard") }) {
                        request.grant(request.resources)
                    } else {
                        request.deny()
                    }
                }
            }

            loadUrl(VscodeTermuxApp.instance.codeServerUrl())
        }

        // NOTE global cookie store per app process
        webViewCookies = CookieManager.getInstance();
        webViewCookies.setAcceptCookie(true);
        webViewCookies.setAcceptThirdPartyCookies(webView, true);

        webViewContainer.addView(webView)
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
