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

    /**
     * URL to open code-server directly to the files at [guestPaths]
     * (absolute paths as seen from inside the guest — see
     * importSharedFile) as tabs, instead of the default workspace view —
     * VS Code Web's own documented `payload` query parameter (openFile),
     * evaluated once at initial page load. Paired with `folder=` pointing
     * at the first file's containing directory, mirroring code-server's
     * own documented example exactly (FAQ: "How do I open a file...")
     * rather than assuming payload alone is sufficient.
     */
    fun codeServerOpenFileUrl(guestPaths: List<String>): String {
        val host = "127.0.0.1:${codeServerPort()}"
        val entries = guestPaths.joinToString(",") { path ->
            """["openFile","vscode-remote://$host$path"]"""
        }
        val payload = java.net.URLEncoder.encode("[$entries]", "UTF-8")
        val folder = java.net.URLEncoder.encode(guestPaths.first().substringBeforeLast('/'), "UTF-8")
        return "${codeServerUrl()}?folder=$folder&payload=$payload"
    }

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

    /** Makes a file shared from another app (Share/Open with →
     *  VSCodeTermux) openable inside the guest, returning its absolute
     *  guest-side path. If it's actually backed by shared storage (a real
     *  file manager sharing something from /storage/emulated/0, or a
     *  MediaStore item) that's already reachable through the /sdcard
     *  bind-mount (see RootfsManager) — returns its real path directly, no
     *  copy, nothing written to disk at all. Most third-party apps' own
     *  private content genuinely has no real path to point at (that's the
     *  actual point of scoped storage isolating one app's files from
     *  another); only that case falls back to an actual copy, under
     *  $HOME/.local/share/vscodetermux/shared/ — deliberately not
     *  $HOME/workspace, which should only ever contain what's actually in
     *  the project. Call off the main thread — this does blocking I/O. */
    fun importSharedFile(uri: android.net.Uri, resolver: android.content.ContentResolver): String? {
        resolveSharedStoragePath(uri, resolver)?.let { relativePath ->
            if (File(sharedStorageDir, relativePath).exists()) {
                return "/sdcard/$relativePath"
            }
        }

        val sharedDir = File(homeDir, ".local/share/vscodetermux/shared").apply { mkdirs() }
        val name = resolveDisplayName(uri, resolver)
            ?: uri.lastPathSegment
            ?: "shared-${System.currentTimeMillis()}"
        val dest = uniqueFile(sharedDir, name)

        return copySharedFile(uri, resolver, dest)
            ?.let { "${RootfsManager.TERMUX_HOME}/${it.relativeTo(homeDir).path}" }
    }

    private fun copySharedFile(uri: android.net.Uri, resolver: android.content.ContentResolver, dest: File): File? {
        return try {
            resolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            dest
        } catch (e: java.io.IOException) {
            android.util.Log.w("VSCodeTermux", "importSharedFile failed for $uri: ${e.message}")
            null
        }
    }

    /** Path relative to sharedStorageDir (what to append to /sdcard inside
     *  the guest) if [uri] is actually backed by shared storage — null for
     *  anything else (private app content with no real path, other
     *  storage volumes this app doesn't bind-mount, etc). Covers the two
     *  common cases (a real file manager's DocumentsProvider URI, and
     *  MediaStore's deprecated-but-often-still-populated DATA column) —
     *  not exhaustive, since there's no general API for this by design. */
    private fun resolveSharedStoragePath(uri: android.net.Uri, resolver: android.content.ContentResolver): String? {
        if (uri.authority == "com.android.externalstorage.documents") {
            val docId = try {
                android.provider.DocumentsContract.getDocumentId(uri)
            } catch (e: IllegalArgumentException) {
                return null
            }
            val parts = docId.split(":", limit = 2)
            return if (parts.size == 2 && parts[0].equals("primary", ignoreCase = true)) parts[1] else null
        }

        return try {
            resolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.DATA), null, null, null)
                ?.use { cursor ->
                    val idx = cursor.getColumnIndex(android.provider.MediaStore.MediaColumns.DATA)
                    if (idx < 0 || !cursor.moveToFirst()) return@use null
                    cursor.getString(idx)?.removePrefix("${sharedStorageDir.absolutePath}/")
                }
        } catch (e: Exception) {
            null
        }
    }

    private fun resolveDisplayName(uri: android.net.Uri, resolver: android.content.ContentResolver): String? {
        if (uri.scheme != "content") return null
        return resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
    }

    /** Avoids clobbering an existing file that happens to share the same name. */
    private fun uniqueFile(dir: File, name: String): File {
        if (!File(dir, name).exists()) return File(dir, name)
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 1
        var candidate = File(dir, "$base-$n$ext")
        while (candidate.exists()) {
            n++
            candidate = File(dir, "$base-$n$ext")
        }
        return candidate
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
