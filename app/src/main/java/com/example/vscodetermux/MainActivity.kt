package com.example.vscodetermux

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView

class MainActivity : AppCompatActivity(), TerminalSessionClient by StubTerminalSessionClient() {

    private lateinit var terminalView: TerminalView
    private lateinit var preSessionStatus: TextView
    private lateinit var preSessionStatusScroll: android.widget.ScrollView
    private lateinit var progressBar: ProgressBar
    private lateinit var openIdeButton: Button
    private lateinit var grantPermissionsButton: Button
    private var serverStartRequested = false
    private var loggedPermissionsGranted = false
    private var boundService: ProotServerService? = null
    private var attachedSession: TerminalSession? = null

    private val serviceConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, binder: android.os.IBinder?) {
            val service = (binder as? ProotServerService.LocalBinder)?.getService() ?: return
            boundService = service
            service.setupSessionListener = { session -> runOnUiThread { attachSetupSession(session) } }
            // Covers the case where the service was already running (setup
            // session already created) before this Activity bound to it —
            // the listener above only catches sessions created *after*
            // binding.
            service.getExistingSetupSession()?.let { attachSetupSession(it) }
            refreshDevtoolsButtonVisibility(service)
        }
        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            boundService = null
        }
    }

    private fun refreshDevtoolsButtonVisibility(service: ProotServerService) {
        val button = findViewById<Button>(R.id.installDevtoolsButton)
        when {
            service.isDevtoolsInstalled() -> button.visibility = View.GONE
            !service.isBaseSetupReady() -> {
                // Base setup (proot/bootstrap/pkg) isn't confirmed done yet —
                // running the devtools install against a not-yet-ready
                // environment is exactly the kind of half-finished-state bug
                // that's caused problems elsewhere in this project. Visible
                // so it's not a mystery why nothing seems to happen, but not
                // tappable until the base setup actually finishes.
                button.visibility = View.VISIBLE
                button.isEnabled = false
                button.text = getString(R.string.install_devtools_waiting)
            }
            else -> {
                button.visibility = View.VISIBLE
                button.isEnabled = true
                button.text = getString(R.string.install_devtools)
            }
        }
    }

    private fun attachSetupSession(session: TerminalSession) {
        if (attachedSession === session) return
        attachedSession?.updateTerminalSessionClient(boundService ?: return)
        attachedSession = session
        session.updateTerminalSessionClient(this)
        terminalView.attachSession(session)
        terminalView.onScreenUpdated()
        preSessionStatusScroll.visibility = View.GONE
        terminalView.visibility = View.VISIBLE
    }

    private fun updatePreSessionStatus(line: String) {
        if (attachedSession != null) return // real terminal is attached, no need for the placeholder anymore
        // Self-heal: the one-shot setupSessionListener callback (fired
        // from the background setup thread) can race with this Activity's
        // bind state and get missed — if that happens, this is the
        // fallback that actually recovers instead of leaving MainActivity
        // stuck showing only this placeholder indefinitely.
        boundService?.getExistingSetupSession()?.let {
            attachSetupSession(it)
            return
        }
        // Capped, unlike the old pre-terminal-view HUD which had no limit
        // at all — this phase (proot fetch + bootstrap extraction) is
        // short/low-volume compared to a full toolchain install (which
        // now goes through the real TerminalView, with its own proper
        // scrollback handling), but there's no reason to leave this
        // fully unbounded either.
        val current = preSessionStatus.text.split("\n")
        val updated = (current + line).takeLast(300).joinToString("\n")
        preSessionStatus.text = updated
        preSessionStatusScroll.post { preSessionStatusScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private val settingsResultLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshPermissionStatus()
        }

    private val legacyPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshPermissionStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        terminalView = findViewById(R.id.terminalView)
        preSessionStatus = findViewById(R.id.preSessionStatus)
        preSessionStatusScroll = findViewById(R.id.preSessionStatusScroll)
        progressBar = findViewById(R.id.progressBar)
        openIdeButton = findViewById(R.id.openIdeButton)

        val textSizePx = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics
        ).toInt()
        terminalView.setTextSize(textSizePx)
        terminalView.setTerminalViewClient(object : StubTerminalViewClient() {
            override fun onSingleTapUp(e: MotionEvent?) {
                terminalView.requestFocus()
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(terminalView, 0)
            }
        })

        findViewById<Button>(R.id.restartServerButton).setOnClickListener {
            restartServerAndUpdateUi("Restarting code-server…")
        }
        findViewById<Button>(R.id.copyLogButton).setOnClickListener {
            val text = terminalView.mEmulator?.screen?.transcriptText
            if (text.isNullOrEmpty()) {
                Toast.makeText(this, "Nothing to copy yet", Toast.LENGTH_SHORT).show()
            } else {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text))
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<Button>(R.id.clearLogButton).setOnClickListener {
            // Actually run `clear` in the shell
            attachedSession?.write("clear\r")
            preSessionStatus.text = ""
        }
        val portInput = findViewById<android.widget.EditText>(R.id.portInput)
        portInput.setText(VscodeTermuxApp.instance.codeServerPort().toString())
        findViewById<Button>(R.id.setPortButton).setOnClickListener {
            val port = portInput.text.toString().toIntOrNull()
            if (port == null || port !in 1024..65535) {
                Toast.makeText(this, "Enter a port between 1024 and 65535", Toast.LENGTH_SHORT).show()
            } else {
                VscodeTermuxApp.instance.setCodeServerPort(port)
                restartServerAndUpdateUi("Port set to $port — restarting code-server…")
            }
        }
        grantPermissionsButton = findViewById(R.id.grantPermissionsButton)
        grantPermissionsButton.setOnClickListener {
            requestNextMissingPermission()
        }
        openIdeButton.setOnClickListener {
            startActivity(Intent(this, WebViewActivity::class.java))
        }
        findViewById<Button>(R.id.openTerminalButton).setOnClickListener {
            startActivity(Intent(this, TerminalActivity::class.java))
        }
        findViewById<Button>(R.id.installDevtoolsButton).setOnClickListener {
            val service = boundService
            if (service == null || !service.isBaseSetupReady()) {
                Toast.makeText(this, "Wait for the base setup to finish first", Toast.LENGTH_SHORT).show()
            } else {
                val (id, _) = service.getOrCreateDevtoolsSession()
                service.selectSession(id)
                startActivity(Intent(this, TerminalActivity::class.java))
            }
        }
        findViewById<Button>(R.id.complianceButton).setOnClickListener {
            startActivity(Intent(this, ComplianceActivity::class.java))
        }
        findViewById<Button>(R.id.serverInfoButton).setOnClickListener {
            showServerInfoDialog()
        }

        ProotServerService.statusListener = ::updatePreSessionStatus
        ProotServerService.readyListener = { markServerReady() }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, ProotServerService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
        portCheckHandler.post(portCheckRunnable)
    }

    override fun onStop() {
        super.onStop()
        val service = boundService
        // Hand the session back to the service so it keeps receiving
        // onTextChanged/onSessionFinished callbacks while this Activity
        // isn't in the foreground — same pattern TerminalActivity uses.
        if (attachedSession != null && service != null) {
            attachedSession?.updateTerminalSessionClient(service)
            service.setupSessionListener = null
        }
        try {
            unbindService(serviceConnection)
        } catch (e: IllegalArgumentException) {
            // Wasn't actually bound (service never started) — fine to ignore.
        }
        boundService = null
        portCheckHandler.removeCallbacks(portCheckRunnable)
    }

    private var serverReady = false
    private val portCheckHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val portCheckInterval = 3000L

    private fun markServerReady() {
        if (serverReady) return
        serverReady = true
        progressBar.visibility = View.GONE
        openIdeButton.isEnabled = true
        boundService?.let { refreshDevtoolsButtonVisibility(it) }
    }

    private fun restartServerAndUpdateUi(message: String) {
        val service = boundService
        if (service == null) {
            Toast.makeText(this, "Server isn't running yet — nothing to restart", Toast.LENGTH_SHORT).show()
            return
        }
        serverReady = false
        openIdeButton.isEnabled = false
        progressBar.visibility = View.VISIBLE
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        attachedSession = null
        service.restartServer()
    }

    private fun checkPortNow() {
        val port = VscodeTermuxApp.instance.codeServerPort()
        Thread {
            val reachable = try {
                java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", port), 500) }
                true
            } catch (e: Exception) {
                false
            }
            if (reachable) portCheckHandler.post { markServerReady() }
        }.start()
    }

    private val portCheckRunnable = object : Runnable {
        override fun run() {
            if (attachedSession == null) {
                boundService?.getExistingSetupSession()?.let { attachSetupSession(it) }
            }
            if (!serverReady) checkPortNow()
            portCheckHandler.postDelayed(this, portCheckInterval)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStatus()
        boundService?.let { refreshDevtoolsButtonVisibility(it) }
    }

    private fun hasStoragePermission(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        android.os.Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun hasAllPermissions(): Boolean = hasStoragePermission()

    private fun refreshPermissionStatus() {
        when {
            hasAllPermissions() -> {
                grantPermissionsButton.visibility = View.GONE
                if (!loggedPermissionsGranted) {
                    loggedPermissionsGranted = true
                    updatePreSessionStatus("Permissions granted.")
                }
                startServerIfNeeded()
            }
            else -> {
                grantPermissionsButton.visibility = View.VISIBLE
                updatePreSessionStatus("Storage permission still needed — tap Grant.")
            }
        }
    }

    private fun requestNextMissingPermission() {
        if (!hasStoragePermission()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                settingsResultLauncher.launch(intent)
            } else {
                legacyPermissionLauncher.launch(
                    arrayOf(
                        android.Manifest.permission.READ_EXTERNAL_STORAGE,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                    )
                )
            }
            return
        }
        startServerIfNeeded()
    }

    private fun startServerIfNeeded() {
        if (serverStartRequested) return
        serverStartRequested = true
        progressBar.visibility = View.VISIBLE
        updatePreSessionStatus("Starting setup service…")
        ContextCompat.startForegroundService(this, Intent(this, ProotServerService::class.java))
    }

    private fun showServerInfoDialog() {
        val password = VscodeTermuxApp.instance.codeServerPassword()
        val url = VscodeTermuxApp.instance.codeServerUrl()

        // Only meant for the "connect from another device on the LAN" case
        // (see start-code-server.sh) — this app's own WebView never shows
        // this prompt, it logs itself in automatically.
        val message = if (password != null) {
            "$url\n\nPassword:\n$password"
        } else {
            "Not generated yet — code-server hasn't finished its first boot. Try again shortly."
        }

        val messageView = TextView(this).apply {
            text = message
            setPadding(48, 32, 48, 32)
            setTextIsSelectable(true)
        }

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.server_info))
            .setView(messageView)
            .setPositiveButton("Close", null)

        if (password != null) {
            dialog.setNeutralButton("Copy password") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("code-server password", password))
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }

    override fun onTextChanged(changedSession: TerminalSession) {
        if (changedSession === attachedSession) terminalView.onScreenUpdated()
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        // Setup session ending unexpectedly (e.g. code-server crashed) means
        // the server isn't ready anymore either — same handling TerminalActivity
        // gives any session, plus the session-list bookkeeping.
        if (finishedSession === attachedSession) {
            serverReady = false
            openIdeButton.isEnabled = false
        }
        boundService?.handleSessionFinished(finishedSession)
    }
}
