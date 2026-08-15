package com.example.vscodetermux

import android.content.Intent
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
}
