package com.example.vscodetermux

import android.webkit.WebView
import org.json.JSONObject

/**
 * code-server's /login page is a normal, human-typed password prompt by
 * default. But the only thing that can reach this loopback port at all is
 * this app's own WebView — so within the app,
 * auth doesn't need to be a step the person actually sees or does. It only
 * matters for someone deliberately connecting from a different device on
 * the network, who'd need the real password (logged once by
 * start-code-server.sh, and always readable from config.yaml).
 */
object CodeServerAutoLogin {

    /**
     * If [url] is code-server's login page and a password is available,
     * fills it in and submits the form. Silently does nothing otherwise —
     * the normal login prompt is left in place as a fallback (e.g. before
     * code-server has generated config.yaml on first boot).
     */
    fun maybeInject(view: WebView, url: String) {
        if (!url.contains("/login")) return
        val password = VscodeTermuxApp.instance.codeServerPassword() ?: return

        val js = """
            (function() {
                var input = document.querySelector('input[name="password"]');
                var form = document.querySelector('form');
                if (!input || !form) return;
                input.value = ${JSONObject.quote(password)};
                // Some forms gate the submit button on seeing a real input
                // event before enabling/validating it — setting .value
                // directly doesn't fire one on its own.
                input.dispatchEvent(new Event('input', { bubbles: true }));

                // form.submit() deliberately does NOT fire the form's
                // 'submit' event (that's the spec) — so if code-server's
                // login page intercepts submit via JS to do the actual auth
                // (fetch/XHR rather than a plain browser POST), .submit()
                // silently skips right past that handler. Click the real
                // submit button so it goes through the same path a person
                // tapping it would, falling back to requestSubmit() (which,
                // unlike submit(), does fire 'submit') if there's no button.
                var button = form.querySelector('button[type="submit"], input[type="submit"]');
                if (button) {
                    button.click();
                } else if (form.requestSubmit) {
                    form.requestSubmit();
                } else {
                    form.submit();
                }
            })();
        """.trimIndent()

        view.evaluateJavascript(js, null)
    }
}
