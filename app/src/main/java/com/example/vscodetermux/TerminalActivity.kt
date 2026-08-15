package com.example.vscodetermux

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.os.IBinder
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.ContextMenu
import android.view.ContextMenu.ContextMenuInfo
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView

class TerminalActivity : AppCompatActivity(), TerminalSessionClient by StubTerminalSessionClient() {

    private lateinit var terminalView: TerminalView
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var sessionAdapter: SessionAdapter

    private var boundService: ProotServerService? = null
    private var session: TerminalSession? = null
    private var redirected = false

    private val boldSpan = StyleSpan(Typeface.BOLD)
    private val italicSpan = StyleSpan(Typeface.ITALIC)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as ProotServerService.LocalBinder).getService()
            boundService = service
            service.sessionListChangedListener = { refreshSessionList() }
            setCurrentSession(service.getOrCreateTerminalSession())
            refreshSessionList()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            boundService?.sessionListChangedListener = null
            boundService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal)

        // Terminal not bootstrapped. Redirect to MainActivity.
        if (!RootfsManager(this).isBootstrapped()) {
            redirected = true
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        terminalView = findViewById(R.id.terminalView)
        drawerLayout = findViewById(R.id.drawerLayout)

        // IDEA Can this still be overriden i.e from env or termux config?
        // otherwise the font size could be added to the long press menu / right click
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
        terminalView.requestFocus()

        // Long-pressing the terminal shows a real Android context menu — same
        // mechanism Termux itself uses (registerForContextMenu + the standard
        // onCreateContextMenu/onContextItemSelected callbacks), rather than a
        // custom overlay.
        registerForContextMenu(terminalView)

        val sessionListView = findViewById<android.widget.ListView>(R.id.sessionListView)
        sessionAdapter = SessionAdapter()
        sessionListView.adapter = sessionAdapter
        sessionListView.setOnItemClickListener { _, _, position, _ ->
            val (id, _) = sessionAdapter.sessions[position]
            val newSession = boundService?.selectSession(id) ?: return@setOnItemClickListener
            setCurrentSession(newSession)
            drawerLayout.closeDrawer(GravityCompat.START)
        }
        sessionListView.setOnItemLongClickListener { _, _, position, _ ->
            val (id, targetSession) = sessionAdapter.sessions[position]
            showSessionActionsDialog(id, targetSession)
            true
        }

        findViewById<View>(R.id.newSessionButton).setOnClickListener {
            spawnAndSwitch()
            drawerLayout.closeDrawer(GravityCompat.START)
        }
    }

    private fun spawnAndSwitch() {
        val service = boundService ?: return
        val (_, newSession) = service.createNewTerminalSession()
        setCurrentSession(newSession)
        refreshSessionList()
    }

    /** Attach the given session to the TerminalView and update client wiring. */
    private fun setCurrentSession(newSession: TerminalSession) {
        val service = boundService
        if (session !== newSession && service != null) {
            session?.updateTerminalSessionClient(service)
        }
        session = newSession
        newSession.updateTerminalSessionClient(this)
        terminalView.attachSession(newSession)
        terminalView.onScreenUpdated()
        sessionAdapter.notifyDataSetChanged()
    }

    private fun refreshSessionList() {
        val service = boundService ?: return
        val sessions = service.getOrderedSessions()
        if (sessions.isEmpty()) {
            // Every session has ended (e.g. the user typed `exit` in the last
            // one) — nothing left to show, so close like Termux itself does.
            finish()
            return
        }
        sessionAdapter.sessions = sessions
        sessionAdapter.notifyDataSetChanged()

        // Only fall over if the currently displayed session was actually
        // removed (explicitly dismissed) — not just because it finished
        // naturally and the service's "active" pointer moved elsewhere.
        // Finished sessions persist in the list now specifically so their
        // output stays reviewable; switching away the instant a script
        // completes would defeat that.
        val stillExists = sessions.any { it.second === session }
        if (!stillExists) {
            val activeId = service.getActiveSessionId()
            val fallback = if (activeId != -1) service.getSession(activeId) else sessions.firstOrNull()?.second
            fallback?.let { setCurrentSession(it) }
        }
    }

    private fun showSessionActionsDialog(id: Int, targetSession: TerminalSession) {
        val closeLabel = if (targetSession.isRunning) getString(R.string.kill_session) else getString(R.string.close_session)
        val options = arrayOf(getString(R.string.rename_session), closeLabel)
        AlertDialog.Builder(this)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showRenameDialog(id, targetSession)
                    1 -> {
                        if (targetSession.isRunning) {
                            AlertDialog.Builder(this)
                                .setMessage(R.string.confirm_kill_process)
                                .setPositiveButton(android.R.string.ok) { _, _ ->
                                    boundService?.dismissSession(id)
                                    sessionAdapter.notifyDataSetChanged()
                                }
                                .setNegativeButton(android.R.string.cancel, null)
                                .show()
                        } else {
                            boundService?.dismissSession(id)
                            sessionAdapter.notifyDataSetChanged()
                        }
                    }
                }
            }
            .show()
    }

    private fun showRenameDialog(id: Int, targetSession: TerminalSession) {
        val input = EditText(this).apply {
            setText(targetSession.mSessionName ?: "")
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.rename_session)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                boundService?.renameSession(id, input.text.toString())
                sessionAdapter.notifyDataSetChanged()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showKillSessionDialog(targetSession: TerminalSession) {
        AlertDialog.Builder(this)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setMessage(R.string.confirm_kill_process)
            .setPositiveButton(android.R.string.yes) { dialog, _ ->
                dialog.dismiss()
                targetSession.finishIfRunning()
            }
            .setNegativeButton(android.R.string.no, null)
            .show()
    }

    // --- Terminal long-press context menu (New/Rename/Kill session, Reset terminal) ---

    private val CONTEXT_MENU_NEW_SESSION = 1
    private val CONTEXT_MENU_RENAME_SESSION = 2
    private val CONTEXT_MENU_KILL_SESSION = 3
    private val CONTEXT_MENU_RESET_TERMINAL = 4

    override fun onCreateContextMenu(menu: ContextMenu, v: View, menuInfo: ContextMenuInfo?) {
        super.onCreateContextMenu(menu, v, menuInfo)
        val current = session ?: return
        menu.add(Menu.NONE, CONTEXT_MENU_NEW_SESSION, Menu.NONE, R.string.new_session)
        menu.add(Menu.NONE, CONTEXT_MENU_RENAME_SESSION, Menu.NONE, R.string.rename_session)
        menu.add(Menu.NONE, CONTEXT_MENU_KILL_SESSION, Menu.NONE, if (current.isRunning) R.string.kill_session else R.string.close_session)
        menu.add(Menu.NONE, CONTEXT_MENU_RESET_TERMINAL, Menu.NONE, R.string.reset_terminal)

        // IDEA font size but per terminal and separate from the globally / persisted setting
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        val current = session ?: return super.onContextItemSelected(item)
        val activeId = boundService?.getActiveSessionId() ?: -1
        return when (item.itemId) {
            CONTEXT_MENU_NEW_SESSION -> {
                spawnAndSwitch()
                true
            }
            CONTEXT_MENU_RENAME_SESSION -> {
                showRenameDialog(activeId, current)
                true
            }
            CONTEXT_MENU_KILL_SESSION -> {
                if (current.isRunning) {
                    showKillSessionDialog(current)
                } else {
                    boundService?.dismissSession(activeId)
                }
                true
            }
            CONTEXT_MENU_RESET_TERMINAL -> {
                current.reset()
                terminalView.onScreenUpdated()
                true
            }
            else -> super.onContextItemSelected(item)
        }
    }

    /** Builds the "[N] name\ntitle" styled row text, same format as Termux's own drawer. */
    private inner class SessionAdapter : BaseAdapter() {
        var sessions: List<Pair<Int, TerminalSession>> = emptyList()

        override fun getCount() = sessions.size
        override fun getItem(position: Int) = sessions[position]
        override fun getItemId(position: Int) = sessions[position].first.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = (convertView as? android.widget.TextView) ?: LayoutInflater.from(this@TerminalActivity)
                .inflate(R.layout.session_list_item, parent, false) as android.widget.TextView

            val (id, sessionAtRow) = sessions[position]

            val numberPart = "[${position + 1}] "
            val namePart = sessionAtRow.mSessionName ?: ""
            val titlePart = sessionAtRow.title?.takeIf { it.isNotEmpty() }
                ?.let { (if (namePart.isEmpty()) "" else "\n") + it } ?: ""

            val fullTitle = numberPart + namePart + titlePart
            val styled = SpannableString(fullTitle)
            styled.setSpan(boldSpan, 0, numberPart.length + namePart.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            styled.setSpan(italicSpan, numberPart.length + namePart.length, fullTitle.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            view.text = styled

            if (sessionAtRow.isRunning) {
                view.paintFlags = view.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            } else {
                view.paintFlags = view.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            }
            val defaultColor = Color.parseColor("#e0e0e0")
            view.setTextColor(if (sessionAtRow.isRunning || sessionAtRow.exitStatus == 0) defaultColor else Color.RED)
            view.isActivated = (id == boundService?.getActiveSessionId())
            return view
        }
    }

    override fun onStart() {
        super.onStart()
        if (redirected) return
        bindService(Intent(this, ProotServerService::class.java), connection, BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (redirected) return
        val service = boundService
        if (session != null && service != null) {
            session?.updateTerminalSessionClient(service)
            service.sessionListChangedListener = null
        }
        unbindService(connection)
        boundService = null
    }

    override fun onTextChanged(changedSession: TerminalSession) {
        terminalView.onScreenUpdated()
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        // TerminalSession invokes onSessionFinished on whichever client is
        // currently attached to it — which is this Activity, not
        // ProotServerService, whenever the Activity is in the foreground
        // displaying the exact session that just exited. So the real
        // cleanup (removing it from the tracked map, picking a fallback
        // session) has to be triggered from here in that case; it doesn't
        // happen automatically just because the service also implements
        // TerminalSessionClient.
        boundService?.handleSessionFinished(finishedSession)
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
        if (text.isNullOrEmpty()) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (!text.isNullOrEmpty()) {
            terminalView.mEmulator?.paste(text)
        }
    }
}
