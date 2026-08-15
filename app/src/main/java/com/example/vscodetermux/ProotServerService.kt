package com.example.vscodetermux

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlin.concurrent.thread

class ProotServerService : Service(), TerminalSessionClient by StubTerminalSessionClient() {

    private lateinit var manager: RootfsManager
    private var terminalSession: TerminalSession? = null
    private val terminalSessions = mutableMapOf<Int, TerminalSession>()
    private var nextSessionId = 0
    private var activeSessionId = -1

    inner class LocalBinder : Binder() {
        fun getService(): ProotServerService = this@ProotServerService
    }

    // Notifies TerminalActivity's drawer to refresh when the session list changes
    // (a session finishes on its own, e.g. user typed `exit`).
    var sessionListChangedListener: (() -> Unit)? = null

    // Notifies MainActivity once the real setup terminal session exists,
    // so it can attach its TerminalView instead of showing the pre-session
    // status placeholder.
    var setupSessionListener: ((TerminalSession) -> Unit)? = null

    @Synchronized
    fun getExistingSetupSession(): TerminalSession? = terminalSessions[setupSessionId]

    fun isDevtoolsInstalled(): Boolean = manager.isToolchainInstalled()

    fun isBaseSetupReady(): Boolean = isReady

    private var setupSessionId: Int = -1

    private fun spawnSession(): Pair<Int, TerminalSession> {
        val cmd = manager.interactiveShellCommand()
        val session = TerminalSession(
            cmd[0],
            filesDir.absolutePath,
            cmd.drop(1).toTypedArray(),
            manager.interactiveShellEnv(),
            2000,
            this
        )
        val id = nextSessionId++
        terminalSessions[id] = session
        activeSessionId = id
        terminalSession = session
        return Pair(id, session)
    }

    /**
     * Spawns (or returns the existing) real interactive terminal session
     * running toolchain-setup + code-server, tracked in the same session
     * map as everything else — so it shows up in TerminalActivity's own
     * drawer, and any interactive prompt during setup (like the dpkg
     * conffile prompt that used to hang the old log-only HUD with no way
     * to respond) can actually be answered.
     */
    @Synchronized
    fun getOrCreateSetupSession(port: Int): Pair<Int, TerminalSession> {
        terminalSessions[setupSessionId]?.let { existing ->
            if (existing.isRunning) {
                Log.i(TAG, "getOrCreateSetupSession: reusing existing RUNNING session id=$setupSessionId")
                return Pair(setupSessionId, existing)
            }
            Log.w(TAG, "getOrCreateSetupSession: existing session id=$setupSessionId is DEAD (isRunning=false) — discarding, creating fresh")
            terminalSessions.remove(setupSessionId)
        }
        Log.i(TAG, "getOrCreateSetupSession: creating a fresh session")
        val cmd = manager.setupAndServerCommand()
        Log.i(TAG, "getOrCreateSetupSession: cmd=${cmd.joinToString(" | ")}")
        val session = TerminalSession(
            cmd[0],
            filesDir.absolutePath,
            cmd.drop(1).toTypedArray(),
            manager.setupAndServerEnv(port),
            2000,
            this
        )
        session.mSessionName = "Setup"
        val id = nextSessionId++
        terminalSessions[id] = session
        setupSessionId = id
        if (activeSessionId == -1) {
            activeSessionId = id
            terminalSession = session
        }
        Log.i(TAG, "getOrCreateSetupSession: created session id=$id, isRunning=${session.isRunning}")
        return Pair(id, session)
    }

    @Synchronized
    fun getOrCreateTerminalSession(): TerminalSession {
        if (activeSessionId != -1 && activeSessionId in terminalSessions) {
            return terminalSessions[activeSessionId]!!
        }
        return spawnSession().second
    }

    private var devtoolsSessionId: Int = -1

    /**
     * Opt-in Android devtools install session (JDK/SDK/NDK/gradle/cmake,
     * ~5GB) — separate from the base setup session so it only runs when
     * the user explicitly asks for it via a button, not unconditionally
     * on first launch.
     */
    @Synchronized
    fun getOrCreateDevtoolsSession(): Pair<Int, TerminalSession> {
        terminalSessions[devtoolsSessionId]?.let { return Pair(devtoolsSessionId, it) }
        val cmd = manager.devtoolsSetupCommand()
        val session = TerminalSession(
            cmd[0],
            filesDir.absolutePath,
            cmd.drop(1).toTypedArray(),
            manager.interactiveShellEnv(),
            2000,
            this
        )
        session.mSessionName = "Android devtools install"
        val id = nextSessionId++
        terminalSessions[id] = session
        devtoolsSessionId = id
        mainHandler.post { sessionListChangedListener?.invoke() }
        return Pair(id, session)
    }

    @Synchronized
    fun createNewTerminalSession(): Pair<Int, TerminalSession> = spawnSession()

    @Synchronized
    fun getOrderedSessions(): List<Pair<Int, TerminalSession>> {
        return terminalSessions.keys.sorted().map { id -> Pair(id, terminalSessions[id]!!) }
    }

    @Synchronized
    fun getActiveSessionId(): Int = activeSessionId

    @Synchronized
    fun getSession(id: Int): TerminalSession? = terminalSessions[id]

    @Synchronized
    fun renameSession(id: Int, name: String) {
        terminalSessions[id]?.mSessionName = name
    }

    @Synchronized
    fun selectSession(id: Int): TerminalSession? {
        return terminalSessions[id]?.also {
            activeSessionId = id
            terminalSession = it
        }
    }

    /**
     * Kills the given session outright. If it was the active session, falls
     * back to another remaining session (or leaves activeSessionId unset if
     * that was the last one — the caller is responsible for spawning a new
     * session or navigating away in that case).
     */
    @Synchronized
    fun killSession(id: Int) {
        val session = terminalSessions.remove(id) ?: return
        session.finishIfRunning()
        if (activeSessionId == id) {
            val next = terminalSessions.keys.firstOrNull()
            activeSessionId = next ?: -1
            terminalSession = next?.let { terminalSessions[it] }
        }
    }

    @Volatile private var isRunning = false
    @Volatile private var isReady = false
    @Volatile private var lastStatus = "Starting…"

    override fun onCreate() {
        super.onCreate()
        manager = RootfsManager(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RUN_BOOT_SCRIPTS_ONLY) {
            // Real device boot — run genuine .termux/boot interop scripts
            // only. Deliberately does NOT touch isReady/isRunning/
            // serverProcess or anything else the full startup sequence
            // uses, since this has nothing to do with code-server: it
            // shouldn't start (or appear to have started) just because
            // the device rebooted. It only starts when the app itself is
            // opened — see startServerIfNeeded() in MainActivity.
            startForeground(NOTIF_ID, buildNotification("Running .termux/boot scripts…"))
            thread(start = true) {
                try {
                    if (manager.isBootstrapped()) {
                        manager.runBootScripts { line -> Log.i(TAG, "[boot] $line") }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Boot scripts failed", e)
                } finally {
                    stopSelf()
                }
            }
            return START_NOT_STICKY
        }

        startForeground(NOTIF_ID, buildNotification(lastStatus))

        synchronized(this) {
            if (isReady) {
                Log.i(TAG, "onStartCommand: already isReady=true, just re-broadcasting")
                broadcastReady()
                return START_STICKY
            }
            if (isRunning) {
                Log.i(TAG, "onStartCommand: already isRunning=true, a startup is already in flight")
                broadcastStatus(lastStatus)
                return START_STICKY
            }
            Log.i(TAG, "onStartCommand: starting fresh (isReady=false, isRunning=false)")
            isRunning = true
        }

        runStartupSequence()
        return START_STICKY
    }

    /**
     * Stops the currently running code-server process (if any) and starts a
     * fresh one, picking up whatever port is configured right now. Needed
     * because changing the port alone doesn't do anything to an already
     * -running server — without this, the only way to actually apply a new
     * port was to force-stop the whole app from Android's app info screen.
     */
    fun restartServer() {
        synchronized(this) {
            if (isRunning) return // a startup/restart is already in flight
            isReady = false
            isRunning = true
        }
        broadcastStatus("Restarting code-server…")
        if (setupSessionId != -1) {
            killSession(setupSessionId)
            setupSessionId = -1
        }
        runStartupSequence()
    }

    private fun runStartupSequence() {
        thread(start = true) {
            try {
                if (!manager.isProotInstalled()) {
                    broadcastStatus("Fetching proot from the Termux package repo…")
                    val rc = manager.fetchProot { line -> broadcastStatus(line) }
                    if (rc != 0) throw IllegalStateException("proot fetch failed (exit $rc)")
                }
                if (!manager.isBootstrapped()) {
                    broadcastStatus("Bootstrapping Termux…")
                    val rc = manager.runBootstrap { line -> broadcastStatus(line) }
                    if (rc != 0) throw IllegalStateException("Termux bootstrap failed (exit $rc)")
                }

                // From here on, proot + the Termux userland actually exist,
                // so a real PTY session is possible — toolchain setup and
                // code-server both run as one real interactive terminal
                // session instead of a one-way log stream. This is what
                // actually fixes hangs on interactive prompts (dpkg
                // conffile prompts, etc.) that previously had no way to be
                // answered at all.
                broadcastStatus("Starting setup terminal…")
                val port = VscodeTermuxApp.instance.codeServerPort()

                // TerminalSession must be constructed on the main thread —
                // it has a Looper; this background thread() does not,
                // unless explicitly prepared. Constructing it here directly
                // would silently kill this thread on an uncaught exception
                // the moment the constructor touches anything Looper-
                // dependent (e.g. a Handler for I/O callbacks), with no
                // crash and no visible error — exactly matching "nothing
                // happens, no session, no error shown".
                val latch = java.util.concurrent.CountDownLatch(1)
                var session: TerminalSession? = null
                var sessionError: Throwable? = null
                mainHandler.post {
                    try {
                        session = getOrCreateSetupSession(port).second
                        sessionListChangedListener?.invoke()
                        setupSessionListener?.invoke(session!!)
                    } catch (e: Throwable) {
                        sessionError = e
                    } finally {
                        latch.countDown()
                    }
                }
                latch.await()
                sessionError?.let { throw it }
                val activeSession = session ?: throw IllegalStateException("setup session was never created")

                broadcastStatus("Waiting for code-server to be ready…")
                waitForPort(port, isAlive = { activeSession.isRunning }, maxRetries = 1200, intervalMs = 1000)

                isReady = true
                broadcastReady()
            } catch (e: Exception) {
                Log.e(TAG, "Setup failed", e)
                broadcastStatus("ERROR: ${e.message}")
            } finally {
                isRunning = false
            }
        }
    }

    override fun onDestroy() {
        ProotServerService.isServerReady = false
        terminalSessions.values.forEach { it.finishIfRunning() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun broadcastStatus(msg: String) {
        lastStatus = msg
        Log.d(TAG, msg)
        mainHandler.post { statusListener?.invoke(msg) }
    }

    private fun waitForPort(port: Int, isAlive: () -> Boolean, maxRetries: Int, intervalMs: Long = 1000) {
        val statusEveryN = (60_000 / intervalMs).coerceAtLeast(1)
        repeat(maxRetries) { attempt ->
            if (!isAlive()) {
                throw IllegalStateException(
                    "code-server's terminal session ended before port $port ever became available — check the setup terminal for the actual failure, this timeout isn't it"
                )
            }
            try {
                java.net.Socket("127.0.0.1", port).close()
                return
            } catch (e: Exception) {
                if (attempt > 0 && attempt % statusEveryN == 0L) {
                    broadcastStatus("Still waiting for code-server (${attempt * intervalMs / 1000}s so far — first run installs Node.js and compiles native modules, this can take several minutes)…")
                }
                if (attempt < maxRetries - 1) Thread.sleep(intervalMs)
            }
        }
        throw IllegalStateException("code-server port $port did not become available after ${maxRetries * intervalMs / 1000}s")
    }

    private fun broadcastReady() {
        updateNotification("code-server is ready")
        ProotServerService.isServerReady = true
        mainHandler.post { readyListener?.invoke() }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Dev server", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VSCodeTermux")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(text))
    }

    /**
     * Removes a finished session from the tracked map and, if it was the
     * active one, falls back to another remaining session. Called from
     * onSessionFinished — but note that callback fires on whichever
     * TerminalSessionClient is currently attached to the session (this
     * service, or TerminalActivity if it's in the foreground displaying
     * that exact session), so this needs to be reachable from both places
     * rather than assumed to always run here.
     */
    @Synchronized
    fun handleSessionFinished(finishedSession: TerminalSession) {
        // Deliberately NOT removed from terminalSessions here — a session
        // whose process just exited (whether it succeeded, failed, or was
        // killed) still has scrollback worth reviewing (e.g. a devtools
        // install's output). Removing it immediately made that
        // impossible: the moment a script finished, its whole output
        // vanished from the drawer with no way to review it. Only
        // explicit dismissal (dismissSession) actually removes it.
        val id = terminalSessions.entries.firstOrNull { it.value === finishedSession }?.key
        if (id != null && activeSessionId == id) {
            val next = terminalSessions.entries.firstOrNull { it.value.isRunning }?.key
            activeSessionId = next ?: -1
            terminalSession = next?.let { terminalSessions[it] }
        }
        if (finishedSession === terminalSession) terminalSession = null
        mainHandler.post { sessionListChangedListener?.invoke() }
    }

    /** Explicit user-initiated removal of a (typically already-finished) session from the list. */
    @Synchronized
    fun dismissSession(id: Int) {
        terminalSessions[id]?.finishIfRunning()
        terminalSessions.remove(id)
        if (activeSessionId == id) {
            val next = terminalSessions.keys.firstOrNull()
            activeSessionId = next ?: -1
            terminalSession = next?.let { terminalSessions[it] }
        }
        mainHandler.post { sessionListChangedListener?.invoke() }
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        handleSessionFinished(finishedSession)
    }

    companion object {
        private const val TAG = "VSCodeTermux"
        private const val CHANNEL_ID = "vscodetermux_server"
        private const val NOTIF_ID = 1
        const val ACTION_RUN_BOOT_SCRIPTS_ONLY = "com.example.vscodetermux.RUN_BOOT_SCRIPTS_ONLY"

        var statusListener: ((String) -> Unit)? = null
        var readyListener: (() -> Unit)? = null
        @Volatile var isServerReady = false
    }
}
