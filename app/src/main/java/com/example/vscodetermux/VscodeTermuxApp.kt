package com.example.vscodetermux

import android.app.Application

import java.io.File

class VscodeTermuxApp : Application() {
    // application.filesDir
    
    val rootfsDir: File by lazy { File(filesDir, "termux") }

    val homeDir: File by lazy { File(filesDir, "home") }

    val binDir: File by lazy { File(filesDir, "bin") }

    val scriptsDir: File by lazy { File(filesDir, "scripts") }

    val sharedStorageDir: File by lazy { File("/storage/emulated/0") }

    fun codeServerPort(): Int {
        val prefs = getSharedPreferences("vscodetermux", MODE_PRIVATE)
        
        return prefs.getInt("code_server_port", CODE_SERVER_PORT)
    }

    /** code-server serves TLS on 127.0.0.1 using the cert bundled at
     *  assets/tls/ (see start-code-server.sh), which is declared as a
     *  trust anchor in res/xml/network_security_config.xml — so the
     *  WebView trusts it natively, no runtime SSL bypass needed. */
    fun codeServerUrl(): String = "https://127.0.0.1:${codeServerPort()}/"

    /** Reads the password code-server was configured with (see
     *  start-code-server.sh), for auto-filling the WebView's login prompt.
     *  Returns null before code-server has written its config.yaml yet. */
    fun codeServerPassword(): String? {
        val configFile = File(homeDir, ".local/share/code-server/config.yaml")
        if (!configFile.exists()) return null
        return try {
            configFile.readLines()
                .firstOrNull { it.trimStart().startsWith("password:") }
                ?.substringAfter("password:")
                ?.trim()
                ?.trim('"')
                ?.takeIf { it.isNotEmpty() }
        } catch (e: java.io.IOException) {
            null
        }
    }

    fun setCodeServerPort(port: Int) {
        // IDEA update vscode config to match

        getSharedPreferences(
            "vscodetermux",
            MODE_PRIVATE
        ).edit().putInt("code_server_port", port).apply()
    }

    companion object {
        const val CODE_SERVER_PORT = 3033
        lateinit var instance: VscodeTermuxApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        rootfsDir.mkdirs()
        binDir.mkdirs()

        val stager = AssetStager(assets)
        // Bootstrap scripts are pure app logic (not user content), so they
        // should always match what's currently installed — same as the
        // app's own compiled code doesn't need a "first run only" flag.
        // Otherwise every script fix needs a full Clear Data to take
        // effect, dragging the whole SDK/NDK/apt toolchain download along
        // with it even though that lives entirely separately (rootfsDir,
        // gated by its own markers) and isn't what actually changed.
        stager.stageAlways("scripts", scriptsDir)
        // workspace/examples/boot are staged verbatim under scriptsDir/assets/*
        // (scriptsDir is bind-mounted as $VSCODETERMUX_LIBEXEC inside proot).
        // scripts/common.sh's place_staged_assets() decides where each one lands in $HOME —
        // that's the single source of truth for that mapping now, not here.
        stager.stageIfMissing("workspace", File(scriptsDir, "assets/workspace"))
        stager.stageIfMissing("examples", File(scriptsDir, "assets/examples"))
        stager.stageIfMissing("boot", File(scriptsDir, "assets/boot"))
        stager.stageIfMissing("tls", File(scriptsDir, "assets/tls"))
    }
}
