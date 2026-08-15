package com.example.vscodetermux

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity



class ComplianceActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_compliance)
        
        // TODO Add JDK/SDK/NDK and various linux packages
        val licensesText = findViewById<TextView>(R.id.licensesText)
        licensesText.text = """
VSCodeTermux - Open Source Licenses

This application includes code and dependencies from:

• VS Code Server
  https://github.com/coder/code-server
  License: MIT

• Alpine Linux
  https://www.alpinelinux.org/
  License: Multiple (GPL, MIT, and others)

• proot
  https://proot-me.github.io/
  License: GPL-2.0

• Termux
  https://termux.dev/
  License: GPL-2.0

• Android
  License: Apache 2.0

Full source code and license details available at:
https://github.com/yourusername/VSCodeTermux

This application is provided as-is for educational and development purposes.
        """.trimIndent()
    }
}
