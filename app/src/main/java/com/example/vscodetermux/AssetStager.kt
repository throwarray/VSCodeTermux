package com.example.vscodetermux

import android.content.res.AssetManager
import java.io.File
import java.io.IOException

/**
 * Pulls raw asset trees out of the APK and stages them on disk verbatim.
 *
 * This is the *only* piece of the bootstrap that has to be Kotlin: bash
 * running inside proot has no way to reach into the APK's assets, so
 * something on this side of the fence has to extract them first. Everything
 * downstream of that — deciding that "workspace" assets belong at
 * $HOME/workspace, "examples" at $HOME/examples, etc — is deliberately kept
 * out of here and lives in scripts/common.sh's place_staged_assets() instead,
 * so there's one place to check for "where does X end up" rather than three.
 */
class AssetStager(private val assets: AssetManager) {

    /** Extracts assets/[name] into [dest] as-is, unless [dest] already has content. */
    fun stageIfMissing(name: String, dest: File) {
        if (dest.exists() && dest.list()?.isNotEmpty() == true) return
        dest.mkdirs()
        copyTree(name, dest)
    }

    /**
     * Like [stageIfMissing], but always re-extracts assets/[name] to match
     * whatever the currently-installed APK ships — for pure app logic
     * (bootstrap scripts, the bundled cert), never for content a user might
     * have edited, that's what [stageIfMissing] is for.
     *
     * Existing files are overwritten in place rather than wiping [dest]
     * first, so there's no window where a script that's mid-execution reads
     * a half-extracted directory. Anything under [dest] that no longer has
     * a matching asset (a script since removed/renamed) is pruned after.
     */
    fun stageAlways(name: String, dest: File) {
        dest.mkdirs()
        copyTree(name, dest)
        pruneOrphans(name, dest)
    }

    private fun pruneOrphans(assetPath: String, dest: File) {
        val assetChildren = (try {
            assets.list(assetPath)
        } catch (e: IOException) {
            null
        } ?: emptyArray()).toSet()

        dest.listFiles()?.forEach { child ->
            if (child.name !in assetChildren) {
                child.deleteRecursively()
            } else if (child.isDirectory) {
                pruneOrphans("$assetPath/${child.name}", child)
            }
        }
    }

    private fun copyTree(assetPath: String, dest: File) {
        val children = try {
            assets.list(assetPath)
        } catch (e: IOException) {
            null
        } ?: emptyArray()

        if (children.isEmpty()) {
            copyLeaf(assetPath, dest)
            return
        }

        dest.mkdirs()
        for (child in children) copyTree("$assetPath/$child", File(dest, child))
    }

    private fun copyLeaf(assetPath: String, dest: File) {
        dest.parentFile?.mkdirs()

        try {
            assets.open(assetPath).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: IOException) {
            // Nothing was actually there — e.g. a directory that only held
            // dotfiles (.gitkeep) which AAPT strips from the built APK.
            // Not an error, just nothing to stage.
            return
        }

        // Asset copies carry no permission metadata.
        if (dest.name == "gradlew" ||
            dest.name.endsWith(".sh") ||
            dest.name == "devcontainer") {
            dest.setExecutable(true, false)
        }
    }
}
