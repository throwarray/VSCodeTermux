package com.example.vscodetermux

import android.content.Context
import android.util.Log
import java.io.File
import java.net.URL

/**
 * This file could be refactored or split into two classes for legibility
 */
class RootfsManager(private val context: Context) {

    private val app get() = context.applicationContext as VscodeTermuxApp

    companion object {
        const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
        const val TERMUX_HOME = "/data/data/com.termux/files/home"
        const val VSCODETERMUX_LIBEXEC = "$TERMUX_PREFIX/libexec/vscodetermux"
        private const val BOOTSTRAP_TAG = "bootstrap-2026.07.12-r1+apt.android-7"

        // NOTE only tested arm; may or may not work on other platforms.
        private val BOOTSTRAP_SHA256 = mapOf(
            "aarch64" to "b6706d470a3e3fcf7cd5c056757c25abd0f61687a40f90ce809289efcc6969fd",
            "arm" to "a41de2c9169e7508ace32b62895cc0d0aede206156f624749d1c198d40329db3",
            "i686" to "75409ab96a402d6b22cb4a6aed6da87060fa548082737291cdb7290dc5b4eaa6",
            "x86_64" to "debec6f8ae9060c25ce1011ba3e2f172079ef97920140d749ae095c785e85236"
        )
    }

    fun isBootstrapped(): Boolean {
        val bash = File(app.rootfsDir, "bin/bash")
        return bash.exists() && bash.length() > 0
    }

    // Must match common.sh's _marker_path() naming ("vscodetermux-<name>-installed").
    fun isToolchainInstalled(): Boolean =
        File(app.rootfsDir, "etc/vscodetermux-toolchain-installed").exists()

    private fun resolveTermuxArch(): String =
        when (val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull()) {
            "arm64-v8a" -> "aarch64"
            "armeabi-v7a" -> "arm"
            "x86_64" -> "x86_64"
            "x86" -> "i686"
            else -> throw IllegalStateException("Unsupported ABI: $abi")
        }

    fun isProotInstalled(): Boolean {
        val proot = File(app.binDir, "proot")
        val talloc = File(app.binDir, "libtalloc.so.2")
        val shmem = File(app.binDir, "libandroid-shmem.so")
        return proot.exists() && proot.length() > 0 &&
            talloc.exists() && talloc.length() > 0 &&
            shmem.exists() && shmem.length() > 0
    }

    fun fetchProot(onLine: (String) -> Unit): Int {
        val termuxArch = resolveTermuxArch()
        val repo = "https://packages.termux.dev/apt/termux-main"
        onLine("Fetching Termux $termuxArch package index…")
        val packagesText = URL(
            "$repo/dists/stable/main/binary-$termuxArch/Packages").readText()

        val closure = resolveDependencyClosure(packagesText, "proot")
        app.binDir.mkdirs()
        File(app.binDir, "proot-libexec").mkdirs()

        for (pkgName in closure) {
            val pkg = resolveTermuxPackage(packagesText, pkgName)
                ?: throw IllegalStateException(
                    "$pkgName not found in Termux $termuxArch index")
            onLine("Downloading $pkgName…")
            val files = extractTermuxDebEntries(repo, pkg) {
                it.endsWith("/bin/proot") || 
                it.contains("/libexec/proot/") || 
                (it.contains("/lib/") && it.contains(".so"))
            }
            files.forEach { (path, data) ->
                val base = path.substringAfterLast('/')
                val dest = when {
                    path.endsWith("/bin/proot") -> File(app.binDir, "proot")
                    path.contains("/libexec/proot/") -> File(
                        app.binDir, "proot-libexec/$base")
                    else -> File(app.binDir, base)
                }
                dest.writeBytes(data)
            }
        }

        File(app.binDir, "proot").setExecutable(true, false)
        File(app.binDir, "proot-libexec").listFiles()?.forEach { 
            it.setExecutable(true, false) }

        if (!File(app.binDir, "proot").exists() || File(app.binDir, "proot").length() == 0L) {
            throw IllegalStateException(
                "proot binary missing after extraction — package layout may have changed upstream")
        }
        onLine("proot installed from Termux repo ($termuxArch): $closure")
        return 0
    }

    private data class TermuxPkg(
        val filename: String, val sha256: String, val depends: String)

    private fun resolveDependencyClosure(
        packagesText: String, rootPkg: String): List<String> {
        val resolved = LinkedHashSet<String>()
        val queue = ArrayDeque(listOf(rootPkg))
        while (queue.isNotEmpty()) {
            val name = queue.removeFirst()
            if (!resolved.add(name)) continue
            val pkg = resolveTermuxPackage(packagesText, name) ?: continue
            for (rawDep in pkg.depends.split(",")) {
                val depName = rawDep.trim().substringBefore(
                    '|').trim().substringBefore(' ').trim()
                if (depName.isNotEmpty() && depName !in resolved) queue.addLast(depName)
            }
        }
        return resolved.toList()
    }

    private fun resolveTermuxPackage(packagesText: String, name: String): TermuxPkg? {
        for (stanza in packagesText.split("\n\n")) {
            var pkg: String? = null
            var filename: String? = null
            var sha256: String? = null
            var depends = ""
            for (line in stanza.lines()) {
                when {
                    line.startsWith("Package: ") -> 
                        pkg = line.removePrefix("Package: ").trim()
                    line.startsWith("Filename: ") -> 
                        filename = line.removePrefix("Filename: ").trim()
                    line.startsWith("SHA256: ") -> 
                        sha256 = line.removePrefix("SHA256: ").trim()
                    line.startsWith("Depends: ") -> 
                        depends = line.removePrefix("Depends: ").trim()
                }
            }
            if (pkg == name && filename != null && sha256 != null) 
                return TermuxPkg(filename, sha256, depends)
        }
        return null
    }

    private fun extractTermuxDebEntries(repo: String, pkg: TermuxPkg, 
    pathFilter: (String) -> Boolean): Map<String, ByteArray> {
        val bytes = URL("$repo/${pkg.filename}").readBytes()
        val actualSha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        if (actualSha != pkg.sha256) {
            throw IllegalStateException(
                "Checksum mismatch fetching ${pkg.filename} — refusing package.")
        }

        val realFiles = mutableMapOf<String, ByteArray>()
        val symlinks = mutableMapOf<String, String>()
        val fullPaths = mutableMapOf<String, String>()

        org.apache.commons.compress.archivers.ar.ArArchiveInputStream(bytes.inputStream()).use { 
            ar ->

            var entry = ar.nextEntry
            while (entry != null) {
                if (entry.name.startsWith("data.tar")) {
                    val decompressed = when {
                        entry.name.endsWith(".xz") -> org.tukaani.xz.XZInputStream(ar)
                        entry.name.endsWith(".zst") -> 
                            com.github.luben.zstd.ZstdInputStream(ar)
                        entry.name.endsWith(".gz") -> java.util.zip.GZIPInputStream(ar)
                        else -> ar
                    }
                    org.apache.commons.compress.archivers.tar.TarArchiveInputStream(decompressed).use { 
                        tar ->
                        var tarEntry = tar.nextEntry
                        while (tarEntry != null) {
                            if (pathFilter(tarEntry.name)) {
                                val base = tarEntry.name.substringAfterLast('/')
                                fullPaths[base] = tarEntry.name
                                when {
                                    tarEntry.isSymbolicLink || 
                                    tarEntry.isLink -> 
                                        symlinks[base] = tarEntry.linkName.substringAfterLast('/')
                                    !tarEntry.isDirectory -> realFiles[base] = tar.readBytes()
                                }
                            }
                            tarEntry = tar.nextEntry
                        }
                    }
                    val resolved = mutableMapOf<String, ByteArray>()
                    for ((base, data) in realFiles) resolved[fullPaths.getValue(base)] = data
                    for ((alias, target) in symlinks) {
                        var t = target
                        var depth = 0
                        while (symlinks.containsKey(t) && depth < 8) { 
                            t = symlinks.getValue(t); depth++ }
                        realFiles[t]?.let { resolved[fullPaths.getValue(alias)] = it }
                    }
                    return resolved
                }
                entry = ar.nextEntry
            }
        }
        throw IllegalStateException("${pkg.filename} had no data.tar.* member")
    }

    fun runBootstrap(onLine: (String) -> Unit): Int {
        val arch = resolveTermuxArch()
        val expectedSha = BOOTSTRAP_SHA256.getValue(arch)
        val tagEncoded = BOOTSTRAP_TAG.replace("+", "%2B")
        val url = "https://github.com/termux/termux-packages/releases/download/$tagEncoded/bootstrap-$arch.zip"

        onLine("Downloading Termux bootstrap ($arch)…")
        val bytes = URL(url).readBytes()
        val actualSha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        if (actualSha != expectedSha) {
            throw IllegalStateException("Checksum mismatch downloading Termux bootstrap — refusing to extract an unverified archive.")
        }

        onLine("Extracting Termux bootstrap…")
        app.rootfsDir.mkdirs()
        val symlinksText = extractBootstrapZip(bytes, app.rootfsDir)
        File(app.rootfsDir, "tmp").mkdirs()

        onLine("Recreating symlinks…")
        for (line in symlinksText.lines()) {
            if (line.isBlank()) continue
            val parts = line.split("←", limit = 2)
            if (parts.size != 2) continue
            val (target, link) = parts
            val linkFile = File(app.rootfsDir, link.removePrefix("./"))
            linkFile.parentFile?.mkdirs()
            linkFile.delete()
            try {
                java.nio.file.Files.createSymbolicLink(linkFile.toPath(), File(target).toPath())
            } catch (e: Exception) {
                Log.d("VSCodeTermux", "Symlink failed ($link -> $target): ${e.message}")
            }
        }

        app.homeDir.mkdirs()
        File(app.rootfsDir, "libexec/vscodetermux").mkdirs()

        val lockFile = File(app.rootfsDir, "etc/termux/termux-bootstrap/second-stage/termux-bootstrap-second-stage.sh.lock")
        if (!lockFile.exists()) {
            onLine("Running Termux bootstrap second stage…")
            val rc = execInProot(
                listOf("$TERMUX_PREFIX/bin/bash", "$TERMUX_PREFIX/etc/termux/termux-bootstrap/second-stage/termux-bootstrap-second-stage.sh"),
                onLine
            )
            if (rc != 0) throw IllegalStateException(
                "Termux bootstrap second stage failed (exit $rc)")
        }

        return 0
    }

    private fun extractBootstrapZip(bytes: ByteArray, destDir: File): String {
        var symlinksText = ""
        val channel = org.apache.commons.compress.utils.SeekableInMemoryByteChannel(bytes)
        org.apache.commons.compress.archivers.zip.ZipFile(channel).use { zip ->
            val entries = java.util.Collections.list(zip.entries)
            for (entry in entries) {
                if (entry.name == "SYMLINKS.txt") {
                    symlinksText = zip.getInputStream(entry).readBytes().toString(
                        Charsets.UTF_8)
                } else if (entry.isDirectory) {
                    File(destDir, entry.name).mkdirs()
                } else {
                    val outFile = File(destDir, entry.name)
                    outFile.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input -> 
                        outFile.outputStream().use { output -> 
                            input.copyTo(output) } }
                    if (entry.unixMode and 0b001_000_000 != 0) 
                        outFile.setExecutable(true, false)
                }
            }
        }
        return symlinksText
    }

    fun interactiveShellCommand(): List<String> =
        buildProotCommand(listOf("$TERMUX_PREFIX/bin/bash", "-l"))

    /**
     * Setup if not done already and start the code-server interactive terminal session.
     */
    fun setupAndServerCommand(): List<String> =
        buildProotCommand(
            listOf(
                "$TERMUX_PREFIX/bin/bash", "-lc",
                "bash $VSCODETERMUX_LIBEXEC/install-toolchain.sh && exec bash $VSCODETERMUX_LIBEXEC/start-code-server.sh"
            )
        )

    fun setupAndServerEnv(port: Int): Array<String> =
        interactiveShellEnv() + "CODE_SERVER_PORT=$port"

    /**
     * Install (JDK/SDK/NDK/gradle/cmake, ~5GB) — Triggered explicitly via a button.
     */
    fun devtoolsSetupCommand(): List<String> =
        buildProotCommand(listOf("$TERMUX_PREFIX/bin/bash", 
            "$VSCODETERMUX_LIBEXEC/install-android-devtools.sh"))

    fun runBootScripts(onLine: (String) -> Unit): Int =
        execInProot(listOf("$TERMUX_PREFIX/bin/bash", 
            "$VSCODETERMUX_LIBEXEC/run-boot-scripts.sh"), onLine)

    /** Env vars every proot invocation needs — the one place that defines them. */
    private fun commonProotEnv(): Map<String, String> = mapOf(
        "HOME" to TERMUX_HOME,
        "PREFIX" to TERMUX_PREFIX,
        "TERMUX_PREFIX" to TERMUX_PREFIX,
        "PATH" to "$TERMUX_PREFIX/bin:$TERMUX_PREFIX/bin/applets",
        "LD_LIBRARY_PATH" to app.binDir.absolutePath,
        "PROOT_LOADER" to
            File(app.binDir, "proot-libexec/loader").absolutePath,
        "PROOT_LOADER_32" to
            File(app.binDir, "proot-libexec/loader32").absolutePath,
        "PROOT_TMP_DIR" to prootHostTmpDir().absolutePath,
        "TMPDIR" to prootHostTmpDir().absolutePath,
        "TERMUX_PKG_NO_MIRROR_SELECT" to "true"
    )

    /**
     * Full replacement environment for processes (TerminalSession) that take
     * an explicit envp array instead of inheriting the host's environment —
     * so, unlike [buildProotProcess], this also needs TERM/LANG spelled out.
     */
    fun interactiveShellEnv(): Array<String> =
        (commonProotEnv() + mapOf(
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8"
        )).map { (k, v) -> "$k=$v" }.toTypedArray()

    private fun prootHostTmpDir(): File = File(
        app.filesDir, "proot-tmp").apply { mkdirs() }

    private fun execInProot(cmd: List<String>, onLine: (String) -> Unit): Int =
        buildProotProcess(cmd, onLine).waitFor()

    private fun buildProotProcess(cmd: List<String>, 
    onLine: (String) -> Unit, extraEnv: Map<String, String> = emptyMap()): Process {
        val fullCmd = buildProotCommand(cmd)
        val pb = ProcessBuilder(fullCmd)

        pb.environment().putAll(commonProotEnv())
        extraEnv.forEach { (k, v) -> pb.environment()[k] = v }

        val proc = pb.redirectErrorStream(true).start()
        Thread {
            try {
                proc.inputStream.bufferedReader().forEachLine(onLine)
            } catch (e: java.io.IOException) { Log.d(
                "VSCodeTermux", 
                "Reader thread ending (process likely destroyed): ${e.message}")
            }
        }.start()
        return proc
    }

    private fun usrBinCompatDir(): File {
        val dir = File(app.filesDir, "usr-bin-compat").apply { mkdirs() }
        val env = File(dir, "env")
        if (!env.exists()) {
            env.writeText("#!/bin/sh\nexec $TERMUX_PREFIX/bin/env \"\$@\"\n")
            env.setExecutable(true, false)
        }
        return dir
    }

    private fun buildProotCommand(cmd: List<String>): List<String> {
        val proot = File(app.binDir, "proot").absolutePath
        val cacheDir = File(context.cacheDir, "termux-cache").apply { mkdirs() }
        File(cacheDir, "apt/archives/partial").mkdirs()

        // Idempotent, not just done once during bootstrap
        // — would otherwise break this bind mount on upgrade specifically.
        File(app.rootfsDir, "libexec/vscodetermux").mkdirs()
        val fullCmd = mutableListOf(
            proot,
            "--link2symlink",
            "--kill-on-exit",
            "-b", "${app.rootfsDir.absolutePath}:$TERMUX_PREFIX",
            "-b", "${app.homeDir.absolutePath}:$TERMUX_HOME",
            "-b", "${app.sharedStorageDir.absolutePath}:/sdcard",
            "-b", "${app.scriptsDir.absolutePath}:$VSCODETERMUX_LIBEXEC",
            "-b", "${cacheDir.absolutePath}:/data/data/com.termux/cache",
            "-b", "${usrBinCompatDir().absolutePath}:/usr/bin",
            "-w", TERMUX_HOME
        )
        fullCmd += cmd
        return fullCmd
    }
}
