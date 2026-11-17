package com.chaman.calllogeditor

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.Build
import android.os.Bundle
import android.provider.CallLog
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.ViewGroup
import android.widget.*
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.view.HapticFeedbackConstants
import android.view.ViewConfiguration
import kotlin.math.abs
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.text.SimpleDateFormat

data class CallEntry(
    val id: Long,
    val number: String,
    val type: Int,
    val date: Long,
    val duration: Long
)

// Missed / Rejected / Blocked: calls that are never answered (no duration, grouped by count).
internal fun isNoAnswerType(type: Int) =
    type == CallLog.Calls.MISSED_TYPE ||
        type == CallLog.Calls.REJECTED_TYPE ||
        type == CallLog.Calls.BLOCKED_TYPE

// One card in the list: a single call, or a group of same-number + same-type no-answer rows.
data class DisplayItem(val rows: List<CallEntry>) {
    val rep: CallEntry get() = rows.maxByOrNull { it.date }!!
    val count: Int get() = rows.size
    val grouped: Boolean get() = isNoAnswerType(rep.type)
    val key: String get() = if (grouped) "g|${rep.number}|${rep.type}" else "e|${rep.id}"
}

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var bottomBar: LinearLayout
    private lateinit var selectBar: LinearLayout
    private lateinit var selectCount: TextView
    private lateinit var undoBar: LinearLayout
    private lateinit var undoText: TextView
    private val hideUndo = Runnable { undoBar.visibility = View.GONE }
    private val entries = mutableListOf<CallEntry>()
    private var currentItems: List<DisplayItem> = emptyList()
    private var selectMode = false
    private val selectedKeys = linkedSetOf<String>()
    private var lastDeleted: List<CallEntry> = emptyList()
    private val names = HashMap<String, String>()
    private val lookedUp = HashSet<String>()
    private val requestCode = 410
    private val contactsCode = 411
    private lateinit var pager: SwipePager
    private lateinit var rootLayout: FrameLayout
    private lateinit var formHost: FrameLayout
    private lateinit var formError: TextView
    private lateinit var statsBody: LinearLayout
    private var statsInput: EditText? = null
    private var formOpen = false
    private var formToken = 0
    private val tabLabels = ArrayList<TextView>()
    private val tabLines = ArrayList<View>()
    private val MP = ViewGroup.LayoutParams.MATCH_PARENT
    private val WC = ViewGroup.LayoutParams.WRAP_CONTENT
    private var activeFilter: String = "All"
    private val filterChips = linkedMapOf<String, TextView>()
    // Palette: filled by applyPalette() (light / dark) before the UI is built.
    private var darkTheme = false
    private var ink = 0          // main text, selected chip background
    private var onInk = 0        // text drawn on top of ink
    private var muted = 0
    private var paper = 0        // page background
    private var line = 0
    private var cardBg = 0         // cards, search box, idle chips
    private var cardSel = 0      // selected card
    private var soft = 0         // small buttons, avatar background
    private var softBack = 0     // swipe background
    private var danger = 0
    private var dangerBg = 0
    private var panelBg = 0        // dark panels: summary, select bar, undo bar
    private var panelBtn = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        applyPalette()
        // dialogs, date picker and text fields follow the activity theme
        setTheme(if (darkTheme) R.style.AppDark else R.style.AppLight)
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        buildUi()
        checkPermissionsAndLoad()
    }

    // 0 = follow system, 1 = light, 2 = dark
    private fun themeMode(): Int =
        getSharedPreferences("calllog_ui", MODE_PRIVATE).getInt("theme_mode", 0)

    private fun resolveDark(): Boolean = when (themeMode()) {
        1 -> false
        2 -> true
        else -> (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    private fun applyPalette() {
        darkTheme = resolveDark()
        if (darkTheme) {
            ink = 0xFFF2F2F2.toInt(); onInk = 0xFF141414.toInt(); muted = 0xFFA0A0A0.toInt()
            paper = 0xFF0E0E0E.toInt(); line = 0xFF2E2E2E.toInt(); cardBg = 0xFF1A1A1A.toInt()
            cardSel = 0xFF262626.toInt(); soft = 0xFF2A2A2A.toInt(); softBack = 0xFF2A2A2A.toInt()
            danger = 0xFFE57373.toInt(); dangerBg = 0xFF3A2222.toInt()
            panelBg = 0xFF2C2C2C.toInt(); panelBtn = 0xFF444444.toInt()
        } else {
            ink = 0xFF171717.toInt(); onInk = 0xFFFFFFFF.toInt(); muted = 0xFF777777.toInt()
            paper = 0xFFF5F5F2.toInt(); line = 0xFFE7E7E2.toInt(); cardBg = 0xFFFFFFFF.toInt()
            cardSel = 0xFFEFEFEA.toInt(); soft = 0xFFF2F2EF.toInt(); softBack = 0xFFE9E9E4.toInt()
            danger = 0xFF8A2929.toInt(); dangerBg = 0xFFF8EEEE.toInt()
            panelBg = 0xFF202020.toInt(); panelBtn = 0xFF3A3A3A.toInt()
        }
    }

    private fun cycleTheme() {
        val next = (themeMode() + 1) % 3
        getSharedPreferences("calllog_ui", MODE_PRIVATE).edit().putInt("theme_mode", next).apply()
        Toast.makeText(this, "Theme: " + listOf("System", "Light", "Dark")[next], Toast.LENGTH_SHORT).show()
        recreate()
    }


    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int = 18, stroke: Int? = null): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }

    private fun textView(
        value: String, size: Float, color: Int, bold: Boolean = false
    ) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        includeFontPadding = false
    }

    private fun hideKeyboard() {
        val v = currentFocus ?: return
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(v.windowToken, 0)
    }

    private fun squareButton(symbol: String, desc: String, onClick: () -> Unit) = TextView(this).apply {
        text = symbol
        textSize = 24f
        gravity = Gravity.CENTER
        setTextColor(ink)
        contentDescription = desc
        background = rounded(cardBg, 16, line)
        setOnClickListener { onClick() }
    }

    private fun selectTab(i: Int) {
        tabLabels.forEachIndexed { idx, t -> t.setTextColor(if (idx == i) ink else muted) }
        tabLines.forEachIndexed { idx, v -> v.setBackgroundColor(if (idx == i) ink else line) }
    }

    private fun buildUi() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(8))
            setBackgroundColor(paper)
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(textView("CHAMAN", 11f, muted, true))
        brand.addView(textView("Call history", 29f, ink, true).apply { setPadding(0, dp(3), 0, 0) })
        top.addView(brand, LinearLayout.LayoutParams(0, WC, 1f))
        top.addView(squareButton("◐", "Theme: system, light or dark") { cycleTheme() },
            LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(8) })
        top.addView(squareButton("↻", "Refresh") { loadCalls() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        page.addView(top)

        // One quiet line instead of the old dark panel. Tap = permissions.
        status = textView("Checking permissions…", 12f, muted).apply {
            setPadding(0, dp(12), 0, 0)
            setOnClickListener { requestPermissions() }
        }
        page.addView(status)

        tabLabels.clear()
        tabLines.clear()
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Editor", "Search").forEachIndexed { i, name ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setOnClickListener { pager.goTo(i) }
            }
            val label = textView(name, 14f, muted, true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(11), 0, dp(11))
            }
            val bar = View(this)
            cell.addView(label, LinearLayout.LayoutParams(MP, WC))
            cell.addView(bar, LinearLayout.LayoutParams(MP, dp(2)))
            tabLabels.add(label)
            tabLines.add(bar)
            tabs.addView(cell, LinearLayout.LayoutParams(0, WC, 1f))
        }
        page.addView(tabs, LinearLayout.LayoutParams(MP, WC).apply { topMargin = dp(10) })

        pager = SwipePager(this) { idx -> selectTab(idx); hideKeyboard() }
        pager.addView(buildEditorPage(), FrameLayout.LayoutParams(MP, MP))
        pager.addView(buildSearchPage(), FrameLayout.LayoutParams(MP, MP))
        page.addView(pager, LinearLayout.LayoutParams(MP, 0, 1f).apply { topMargin = dp(14) })
        selectTab(0)

        undoText = textView("", 13f, 0xFFFFFFFF.toInt(), true)
        undoBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(8), dp(8))
            background = rounded(panelBg, 15)
            elevation = dp(6).toFloat()
            visibility = View.GONE
        }
        undoBar.addView(undoText, LinearLayout.LayoutParams(0, WC, 1f))
        undoBar.addView(actionButton("UNDO", 0xFFFFFFFF.toInt(), panelBtn) { restoreDeleted() },
            LinearLayout.LayoutParams(dp(68), dp(34)))

        formHost = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(paper)
            isClickable = true
        }
        rootLayout = FrameLayout(this)
        rootLayout.addView(page, FrameLayout.LayoutParams(MP, MP))
        rootLayout.addView(formHost, FrameLayout.LayoutParams(MP, MP))
        rootLayout.addView(undoBar, FrameLayout.LayoutParams(MP, WC, Gravity.BOTTOM)
            .apply { setMargins(dp(20), 0, dp(20), dp(76)) })
        // Keep content clear of status bar / nav bar / keyboard (Android 15 draws edge to edge).
        rootLayout.setOnApplyWindowInsetsListener { v, insets ->
            val topInset: Int
            val bottomInset: Int
            if (Build.VERSION.SDK_INT >= 30) {
                topInset = insets.getInsets(android.view.WindowInsets.Type.systemBars()).top
                bottomInset = insets.getInsets(
                    android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime()
                ).bottom
            } else {
                topInset = insets.systemWindowInsetTop
                bottomInset = insets.systemWindowInsetBottom
            }
            v.setPadding(0, topInset, 0, bottomInset)
            insets
        }
        setContentView(rootLayout)
    }

    // ---------------- Editor tab: filters + list + new entry ----------------
    private fun buildEditorPage(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val filters = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        filterChips.clear()
        listOf("All", "Incoming", "Outgoing", "Missed").forEach { filter ->
            val chip = TextView(this).apply {
                text = filter
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(dp(13), dp(9), dp(13), dp(9))
                setTextColor(if (activeFilter == filter) onInk else ink)
                background = rounded(if (activeFilter == filter) ink else cardBg, 30,
                    if (activeFilter == filter) ink else line)
                setOnClickListener {
                    if (activeFilter == filter) return@setOnClickListener
                    activeFilter = filter
                    updateFilterChips()
                    showListLoading()
                    list.postDelayed({ renderEntries() }, 140)
                }
            }
            filterChips[filter] = chip
            filters.addView(chip, LinearLayout.LayoutParams(WC, dp(36)).apply { marginEnd = dp(7) })
        }
        col.addView(filters)

        scrollView = ScrollView(this).apply { clipToPadding = false }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(16))
        }
        scrollView.addView(list)
        col.addView(scrollView, LinearLayout.LayoutParams(MP, 0, 1f))

        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(4))
        }
        val add = TextView(this).apply {
            text = "+  NEW ENTRY"
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(onInk)
            background = rounded(ink, 15)
            setOnClickListener { addNewEntry() }
        }
        bottomBar.addView(add, LinearLayout.LayoutParams(MP, dp(52)))

        selectBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(8), 0)
            background = rounded(panelBg, 15)
            visibility = View.GONE
        }
        selectCount = textView("0 selected", 13f, 0xFFFFFFFF.toInt(), true)
        selectBar.addView(selectCount, LinearLayout.LayoutParams(0, WC, 1f))
        selectBar.addView(actionButton("CANCEL", 0xFFFFFFFF.toInt(), panelBtn) { setSelectMode(false) },
            LinearLayout.LayoutParams(dp(72), dp(34)))
        selectBar.addView(actionButton("DELETE", 0xFFFFFFFF.toInt(), 0xFF8A2929.toInt()) { confirmDeleteSelected() },
            LinearLayout.LayoutParams(dp(72), dp(34)).apply { marginStart = dp(7) })
        col.addView(selectBar, LinearLayout.LayoutParams(MP, dp(52)).apply { topMargin = dp(10); bottomMargin = dp(4) })
        col.addView(bottomBar)
        return col
    }

    private fun updateFilterChips() {
        filterChips.forEach { (filter, chip) ->
            val selected = activeFilter == filter
            chip.setTextColor(if (selected) onInk else ink)
            chip.background = rounded(if (selected) ink else cardBg, 30,
                if (selected) ink else line)
        }
    }

    private fun showListLoading() {
        if (!::list.isInitialized) return
        list.removeAllViews()
        val loading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(28), dp(20), dp(28))
            background = rounded(cardBg, 18, line)
        }
        loading.addView(ProgressBar(this).apply { isIndeterminate = true },
            LinearLayout.LayoutParams(dp(24), dp(24)))
        loading.addView(textView("Loading call history…", 13f, muted, true).apply {
            setPadding(dp(12), 0, 0, 0)
        })
        list.addView(loading, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun has(permission: String) =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun checkPermissionsAndLoad() {
        if (has(Manifest.permission.READ_CALL_LOG)) {
            loadCalls()
            askContactsOnce()
        } else {
            status.text = "Call log permission is required."
            requestPermissions()
        }
    }

    private fun requestPermissions() {
        getSharedPreferences("chaman_prefs", MODE_PRIVATE).edit().putBoolean("contacts_asked", true).apply()
        requestPermissions(
            arrayOf(
                Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG,
                Manifest.permission.READ_CONTACTS
            ),
            requestCode
        )
    }

    // Contact names are optional: ask once, and the app works fine if it is denied.
    private fun askContactsOnce() {
        if (has(Manifest.permission.READ_CONTACTS)) return
        val prefs = getSharedPreferences("chaman_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("contacts_asked", false)) return
        prefs.edit().putBoolean("contacts_asked", true).apply()
        requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), contactsCode)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == contactsCode) {
            resolveNames()
            return
        }
        if (requestCode == this.requestCode) {
            status.text = "Read: ${has(Manifest.permission.READ_CALL_LOG)}  |  Write: ${has(Manifest.permission.WRITE_CALL_LOG)}"
            loadCalls()
        }
    }

    private fun loadCalls() {
        if (!has(Manifest.permission.READ_CALL_LOG)) {
            status.text = "READ_CALL_LOG permission not granted."
            return
        }
        entries.clear()
        try {
            val projection = arrayOf(
                CallLog.Calls._ID, CallLog.Calls.NUMBER,
                CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION
            )
            contentResolver.query(
                CallLog.Calls.CONTENT_URI, projection, null, null,
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor: Cursor ->
                val idCol = cursor.getColumnIndexOrThrow(CallLog.Calls._ID)
                val numberCol = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val typeCol = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                val dateCol = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
                val durationCol = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                while (cursor.moveToNext()) {
                    entries.add(
                        CallEntry(
                            cursor.getLong(idCol),
                            cursor.getString(numberCol) ?: "Unknown",
                            cursor.getInt(typeCol),
                            cursor.getLong(dateCol),
                            cursor.getLong(durationCol)
                        )
                    )
                }
            }
            status.text = "${entries.size} records  ·  Write ${if (has(Manifest.permission.WRITE_CALL_LOG)) "available" else "restricted"}"
            renderEntries()
            resolveNames()
        } catch (e: SecurityException) {
            status.text = "Access denied by Android: ${e.message ?: "permission restricted"}"
        } catch (e: Exception) {
            status.text = "Could not read call history: ${e.message}"
        }
    }


    private fun buildItems(filtered: List<CallEntry>): List<DisplayItem> {
        val groups = LinkedHashMap<String, MutableList<CallEntry>>()
        filtered.forEach { e ->
            val k = if (isNoAnswerType(e.type)) "g|${e.number}|${e.type}" else "e|${e.id}"
            groups.getOrPut(k) { mutableListOf() }.add(e)
        }
        return groups.values.map { DisplayItem(it) }
    }

    private fun resolveNames() {
        if (!has(Manifest.permission.READ_CONTACTS)) return
        val numbers = entries.map { it.number }.distinct()
            .filter { it !in lookedUp && it != "Unknown" && it.isNotBlank() }
        if (numbers.isEmpty()) return
        Thread {
            val found = HashMap<String, String>()
            numbers.forEach { n ->
                try {
                    val uri = Uri.withAppendedPath(
                        ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(n)
                    )
                    contentResolver.query(
                        uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
                    )?.use { c ->
                        if (c.moveToFirst()) c.getString(0)?.let { found[n] = it }
                    }
                } catch (e: Exception) {
                }
            }
            runOnUiThread {
                lookedUp.addAll(numbers)
                names.putAll(found)
                if (found.isNotEmpty()) renderEntries()
            }
        }.start()
    }

    private fun renderEntries() {
        if (!::list.isInitialized) return
        val keepScroll = scrollView.scrollY
        list.removeAllViews()
        val query = ""
        val filtered = entries.filter { entry ->
            val name = names[entry.number]?.lowercase(Locale.getDefault()).orEmpty()
            val matchesQuery = query.isEmpty() ||
                entry.number.lowercase(Locale.getDefault()).contains(query) || name.contains(query)
            val matchesFilter = when (activeFilter) {
                "Incoming" -> entry.type == CallLog.Calls.INCOMING_TYPE
                "Outgoing" -> entry.type == CallLog.Calls.OUTGOING_TYPE
                "Missed" -> entry.type == CallLog.Calls.MISSED_TYPE
                else -> true
            }
            matchesQuery && matchesFilter
        }

        if (filtered.isEmpty()) {
            currentItems = emptyList()
            selectedKeys.clear()
            selectMode = false
            updateSelectBar()
            val empty = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(34), dp(20), dp(34))
                background = rounded(cardBg, 18, line)
            }
            empty.addView(textView("No calls found", 17f, ink, true))
            empty.addView(textView("Try another filter.", 12f, muted).apply {
                setPadding(0, dp(7), 0, 0)
            })
            list.addView(empty)
            return
        }

        val items = buildItems(filtered)
        currentItems = items
        selectedKeys.retainAll(items.map { it.key }.toSet())
        if (selectMode && selectedKeys.isEmpty()) selectMode = false
        items.forEach { item ->
            list.addView(buildRow(item), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) })
        }
        updateSelectBar()
        scrollView.post { scrollView.scrollTo(0, keepScroll) }
    }

    private fun actionButton(label: String, textColor: Int, bgColor: Int, onClick: () -> Unit) =
        TextView(this).apply {
            text = label
            textSize = 10f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(textColor)
            background = rounded(bgColor, 10)
            setOnClickListener { onClick() }
        }

    private fun buildRow(item: DisplayItem): View {
        val entry = item.rep
        val selected = selectMode && item.key in selectedKeys
        val name = names[entry.number]
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(13), dp(16), dp(13))
            background = rounded(
                if (selected) cardSel else cardBg, 18,
                if (selected) ink else line
            )
            // Tap = open the edit screen (or toggle when selecting). Long press = select.
            setOnClickListener { if (selectMode) toggleSelect(item) else editEntry(item) }
            setOnLongClickListener {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                if (selectMode) toggleSelect(item) else startSelect(item)
                true
            }
        }
        val avatar = TextView(this).apply {
            text = if (selected) "✓" else ((name ?: entry.number).firstOrNull()?.toString() ?: "?")
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(if (selected) onInk else ink)
            background = rounded(if (selected) ink else soft, 15)
        }
        card.addView(avatar, LinearLayout.LayoutParams(dp(44), dp(44)))

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        info.addView(textView(name ?: entry.number, 16f, ink, true))
        if (name != null) {
            info.addView(textView(entry.number, 12f, muted).apply { setPadding(0, dp(3), 0, 0) })
        }
        val time = SimpleDateFormat("dd MMM • hh:mm a", Locale.getDefault()).format(Date(entry.date))
        val typeLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, 0)
        }
        typeLine.addView(CallTypeIcon(this, entry.type, muted), LinearLayout.LayoutParams(dp(13), dp(13)))
        typeLine.addView(textView("${typeName(entry.type)}  ·  $time", 11f, muted).apply {
            setPadding(dp(5), 0, 0, 0)
        })
        info.addView(typeLine)
        card.addView(info, LinearLayout.LayoutParams(0, WC, 1f))

        val meta = if (item.grouped) "×  ${item.count}" else fmtDuration(entry.duration)
        card.addView(textView(meta, if (item.grouped) 17f else 12f, if (item.grouped) ink else muted, item.grouped))
        return card
    }

    private fun startSelect(item: DisplayItem) {
        selectMode = true
        selectedKeys.clear()
        selectedKeys.add(item.key)
        renderEntries()
    }

    private fun toggleSelect(item: DisplayItem) {
        if (!selectedKeys.remove(item.key)) selectedKeys.add(item.key)
        if (selectedKeys.isEmpty()) selectMode = false
        renderEntries()
    }

    private fun setSelectMode(on: Boolean) {
        selectMode = on
        selectedKeys.clear()
        renderEntries()
    }

    private fun updateSelectBar() {
        if (!::selectBar.isInitialized) return
        selectBar.visibility = if (selectMode) View.VISIBLE else View.GONE
        bottomBar.visibility = if (selectMode) View.GONE else View.VISIBLE
        selectCount.text = "${selectedKeys.size} selected"
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        when {
            formOpen -> closeForm()
            selectMode -> setSelectMode(false)
            pager.page == 1 -> pager.goTo(0)
            else -> super.onBackPressed()
        }
    }

    // ---------------- Search tab: per-number stats (counts only, no call list) ----------------
    private fun last10(n: String): String {
        val d = n.filter { it.isDigit() }
        return if (d.length > 10) d.substring(d.length - 10) else d
    }

    private fun fmtDuration(sec: Long): String {
        if (sec <= 0L) return "0 sec"
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val r = sec % 60
        val parts = ArrayList<String>()
        if (h > 0) parts.add("$h hr")
        if (m > 0) parts.add("$m min")
        if (r > 0 && h == 0L) parts.add("$r sec")
        return parts.joinToString(" ")
    }

    // Contacts by name or number (needs READ_CONTACTS), plus names already seen in the call log.
    private fun searchContacts(q: String): List<Pair<String, String>> {
        val out = LinkedHashMap<String, Pair<String, String>>()
        if (has(Manifest.permission.READ_CONTACTS)) {
            try {
                val uri = Uri.withAppendedPath(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(q)
                )
                contentResolver.query(
                    uri,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER
                    ),
                    null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
                )?.use { c ->
                    while (c.moveToNext() && out.size < 6) {
                        val num = c.getString(1) ?: continue
                        val nm = c.getString(0) ?: num
                        val k = last10(num)
                        if (k !in out) out[k] = Pair(nm, num)
                    }
                }
            } catch (e: Exception) {
            }
        }
        val ql = q.lowercase(Locale.getDefault())
        names.forEach { (num, nm) ->
            val k = last10(num)
            if (out.size < 6 && k !in out && nm.lowercase(Locale.getDefault()).contains(ql)) out[k] = Pair(nm, num)
        }
        return out.values.toList()
    }

    private fun contactRow(name: String, number: String, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(cardBg, 14, line)
            setOnClickListener { onClick() }
        }
        row.addView(TextView(this).apply {
            text = name.firstOrNull()?.toString() ?: "?"
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(ink)
            background = rounded(soft, 12)
        }, LinearLayout.LayoutParams(dp(36), dp(36)))
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        info.addView(textView(name, 14f, ink, true))
        if (name != number) info.addView(textView(number, 12f, muted).apply { setPadding(0, dp(2), 0, 0) })
        row.addView(info, LinearLayout.LayoutParams(0, WC, 1f))
        return row
    }

    private fun buildSearchPage(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        statsBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(16), 0, dp(24))
        }
        val wrap = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), 0, dp(12), 0)
            background = rounded(cardBg, 15, line)
        }
        wrap.addView(textView("⌕", 25f, muted, true))
        val input = EditText(this).apply {
            hint = "Name or number"
            textSize = 15f
            setTextColor(ink)
            setHintTextColor(muted)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            background = null
            setPadding(dp(10), dp(14), dp(4), dp(14))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { refreshStats() }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        statsInput = input
        wrap.addView(input, LinearLayout.LayoutParams(0, WC, 1f))
        col.addView(wrap, LinearLayout.LayoutParams(MP, dp(52)))
        val sv = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        sv.addView(statsBody)
        col.addView(sv, LinearLayout.LayoutParams(MP, 0, 1f))
        refreshStats()
        return col
    }

    private fun countText(count: Int, color: Int, subColor: Int): TextView {
        val big = count.toString()
        val sp = android.text.SpannableString("$big times")
        sp.setSpan(android.text.style.RelativeSizeSpan(0.42f), big.length, sp.length, 0)
        sp.setSpan(android.text.style.ForegroundColorSpan(subColor), big.length, sp.length, 0)
        return TextView(this).apply {
            text = sp
            textSize = 30f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(color)
            includeFontPadding = false
        }
    }

    private fun statTile(count: Int, label: String, sub: String?): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(15), dp(16), dp(15))
        background = rounded(cardBg, 18, line)
        addView(countText(count, ink, muted))
        addView(textView(label, 13f, ink, true).apply { setPadding(0, dp(8), 0, 0) })
        if (sub != null) addView(textView(sub, 11f, muted).apply { setPadding(0, dp(3), 0, 0) })
    }

    private fun tileRow(a: View, b: View?): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(a, LinearLayout.LayoutParams(0, MP, 1f))
        addView(b ?: View(context), LinearLayout.LayoutParams(0, MP, 1f).apply { marginStart = dp(10) })
    }

    private fun refreshStats() {
        if (!::statsBody.isInitialized) return
        val body = statsBody
        body.removeAllViews()
        val q = statsInput?.text?.toString()?.trim().orEmpty()
        if (q.isEmpty()) {
            body.addView(textView("Kisi bhi naam ya number se search karo.\nYaha uske calls ka summary milega.", 13f, muted))
            return
        }
        val digits = q.filter { it.isDigit() }
        if (q.any { it.isLetter() } || digits.length < 5) {
            val found = searchContacts(q)
            if (found.isEmpty()) {
                val msg = if (q.any { it.isLetter() } && !has(Manifest.permission.READ_CONTACTS))
                    "Naam se search ke liye Contacts permission do (tap)" else
                    if (q.any { it.isLetter() }) "Is naam ka koi contact nahi mila" else "Kam se kam 5 digit daalo"
                body.addView(textView(msg, 13f, muted).apply {
                    if (!has(Manifest.permission.READ_CONTACTS)) setOnClickListener {
                        requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), contactsCode)
                    }
                })
                return
            }
            found.forEach { (nm, num) ->
                body.addView(contactRow(nm, num) { statsInput?.setText(num); hideKeyboard() },
                    LinearLayout.LayoutParams(MP, WC).apply { bottomMargin = dp(8) })
            }
            return
        }
        renderStats(q)
    }

    private fun renderStats(number: String) {
        val body = statsBody
        val key = last10(number)
        val rows = entries.filter { last10(it.number) == key }
        if (rows.isEmpty()) {
            body.addView(textView("Is number ka koi record nahi mila", 13f, muted))
            return
        }
        val inc = rows.filter { it.type == CallLog.Calls.INCOMING_TYPE }
        val outAll = rows.filter { it.type == CallLog.Calls.OUTGOING_TYPE }
        val outOk = outAll.filter { it.duration > 0 }
        val outNo = outAll.filter { it.duration <= 0 }
        val missed = rows.count { it.type == CallLog.Calls.MISSED_TYPE }
        val rejected = rows.count { it.type == CallLog.Calls.REJECTED_TYPE }
        val blocked = rows.count { it.type == CallLog.Calls.BLOCKED_TYPE }
        val incSec = inc.sumOf { it.duration }
        val outSec = outAll.sumOf { it.duration }

        val nm = names[rows.first().number]
        body.addView(textView(nm ?: rows.first().number, 18f, ink, true))
        if (nm != null) body.addView(textView(rows.first().number, 12f, muted).apply { setPadding(0, dp(3), 0, 0) })

        val wide = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = rounded(panelBg, 20)
        }
        wide.addView(textView("TOTAL CALLS", 10f, 0xFFBDBDBD.toInt(), true))
        wide.addView(countText(rows.size, 0xFFFFFFFF.toInt(), 0xFFBDBDBD.toInt()).apply { setPadding(0, dp(8), 0, 0) })
        wide.addView(textView("Total call time  ·  ${fmtDuration(incSec + outSec)}", 12f, 0xFFDDDDDD.toInt())
            .apply { setPadding(0, dp(8), 0, 0) })
        body.addView(wide, LinearLayout.LayoutParams(MP, WC).apply { topMargin = dp(14); bottomMargin = dp(10) })

        val gap = LinearLayout.LayoutParams(MP, WC).apply { bottomMargin = dp(10) }
        body.addView(tileRow(
            statTile(inc.size, "Incoming", "Total time  ${fmtDuration(incSec)}"),
            statTile(outOk.size, "Outgoing connected", "Total time  ${fmtDuration(outSec)}")
        ), gap)
        body.addView(tileRow(
            statTile(outNo.size, "Outgoing not connected", null),
            statTile(missed, "Missed", null)
        ), gap)
        body.addView(tileRow(
            statTile(rejected, "Rejected", null),
            if (blocked > 0) statTile(blocked, "Blocked", null) else null
        ), gap)
    }

    private fun typeName(type: Int) = when (type) {
        CallLog.Calls.INCOMING_TYPE -> "Incoming"
        CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
        CallLog.Calls.MISSED_TYPE -> "Missed"
        CallLog.Calls.REJECTED_TYPE -> "Rejected"
        CallLog.Calls.BLOCKED_TYPE -> "Blocked"
        else -> "Other"
    }

    private fun createCallTypeChips(initialType: Int, onSelected: (Int) -> Unit): HorizontalScrollView {
        val options = listOf(
            "Incoming" to CallLog.Calls.INCOMING_TYPE,
            "Outgoing" to CallLog.Calls.OUTGOING_TYPE,
            "Missed" to CallLog.Calls.MISSED_TYPE,
            "Rejected" to CallLog.Calls.REJECTED_TYPE,
            "Blocked" to CallLog.Calls.BLOCKED_TYPE
        )
        var selected = initialType
        val chips = mutableMapOf<Int, TextView>()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun refresh() {
            chips.forEach { (type, chip) ->
                val active = type == selected
                chip.setTextColor(if (active) onInk else ink)
                chip.background = rounded(if (active) ink else cardBg, 22,
                    if (active) ink else line)
            }
        }
        options.forEach { (label, type) ->
            val chip = TextView(this).apply {
                text = label
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(10), dp(14), dp(10))
                setOnClickListener {
                    selected = type
                    refresh()
                    onSelected(type)
                }
            }
            chips[type] = chip
            row.addView(chip, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(7) })
        }
        refresh()
        onSelected(selected)
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    private fun deleteById(id: Long): Int = contentResolver.delete(
        CallLog.Calls.CONTENT_URI, "${CallLog.Calls._ID} = ?", arrayOf(id.toString())
    )

    // Makes the number of rows for (number, type) equal to `wanted`. Keeps keepId,
    // removes the oldest extras or inserts new rows with staggered timestamps.
    private fun reconcileCount(number: String, type: Int, keepId: Long, wanted: Int, date: Long) {
        val ids = mutableListOf<Long>()
        contentResolver.query(
            CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls._ID),
            "${CallLog.Calls.NUMBER} = ? AND ${CallLog.Calls.TYPE} = ?",
            arrayOf(number, type.toString()), "${CallLog.Calls.DATE} DESC"
        )?.use { c -> while (c.moveToNext()) ids.add(c.getLong(0)) }
        val keep = mutableListOf<Long>()
        if (keepId in ids) keep.add(keepId)
        ids.filter { it != keepId }.forEach { if (keep.size < wanted) keep.add(it) }
        ids.filter { it !in keep }.forEach { id -> deleteById(id) }
        repeat((wanted - keep.size).coerceAtLeast(0)) { index ->
            val values = ContentValues().apply {
                put(CallLog.Calls.NUMBER, number)
                put(CallLog.Calls.TYPE, type)
                put(CallLog.Calls.DATE, date - (index + 1) * 1000L)
                put(CallLog.Calls.DURATION, 0L)
                put(CallLog.Calls.NEW, 1)
            }
            contentResolver.insert(CallLog.Calls.CONTENT_URI, values)
        }
    }

    // ---------------- Full-screen form (Edit + New entry) ----------------
    private fun formLabel(t: String) = textView(t, 10f, muted, true).apply {
        setPadding(dp(2), dp(20), 0, dp(8))
        letterSpacing = 0.12f
    }

    private fun styledEdit(hintText: String, type: Int) = EditText(this).apply {
        hint = hintText
        textSize = 15f
        setTextColor(ink)
        setHintTextColor(muted)
        inputType = type
        setSingleLine(true)
        gravity = Gravity.CENTER_VERTICAL
        background = null
    }

    private fun fieldBox(edit: EditText): LinearLayout = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(cardBg, 15, line)
        setPadding(dp(14), 0, dp(14), 0)
        addView(edit, LinearLayout.LayoutParams(MP, dp(52)))
    }

    private fun valueTile(value: String) = TextView(this).apply {
        text = value
        textSize = 15f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        gravity = Gravity.CENTER
        setTextColor(ink)
        background = rounded(cardBg, 15, line)
    }

    private fun formFail(msg: String) {
        formError.text = msg
        formError.visibility = View.VISIBLE
    }

    private fun openForm(title: String, content: View, saveLabel: String, onSave: () -> Unit) {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(paper)
            isClickable = true
            setPadding(dp(20), dp(14), dp(20), dp(10))
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(squareButton("←", "Back") { closeForm() },
            LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(14) })
        head.addView(textView(title, 22f, ink, true), LinearLayout.LayoutParams(0, WC, 1f))
        form.addView(head)
        val sv = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            addView(content)
        }
        form.addView(sv, LinearLayout.LayoutParams(MP, 0, 1f))
        formError = textView("", 12f, danger, true).apply {
            visibility = View.GONE
            setPadding(dp(2), dp(8), 0, dp(6))
        }
        form.addView(formError)
        form.addView(TextView(this).apply {
            text = saveLabel
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(onInk)
            background = rounded(ink, 15)
            setOnClickListener { onSave() }
        }, LinearLayout.LayoutParams(MP, dp(52)))

        val wasOpen = formOpen
        formToken++
        formHost.animate().cancel()
        formHost.removeAllViews()
        formHost.addView(form, FrameLayout.LayoutParams(MP, MP))
        formHost.visibility = View.VISIBLE
        formOpen = true
        if (!wasOpen) {
            formHost.translationX = rootLayout.width.toFloat()
            formHost.animate().translationX(0f).setDuration(220).start()
        } else {
            formHost.translationX = 0f
        }
    }

    private fun closeForm() {
        if (!formOpen) return
        hideKeyboard()
        formOpen = false
        val token = ++formToken
        formHost.animate().translationX(rootLayout.width.toFloat()).setDuration(190).withEndAction {
            if (token == formToken) {
                formHost.visibility = View.GONE
                formHost.removeAllViews()
            }
        }.start()
    }

    private fun editEntry(item: DisplayItem) = showEntryForm(item, null)

    // prefill != null → "Copy": opens the form with that record's values filled in.
    private fun addNewEntry(prefill: DisplayItem? = null) = showEntryForm(null, prefill)

    private fun showEntryForm(editItem: DisplayItem?, prefill: DisplayItem?) {
        if (!has(Manifest.permission.WRITE_CALL_LOG)) {
            AlertDialog.Builder(this)
                .setTitle("Write permission unavailable")
                .setMessage("Android has not granted WRITE_CALL_LOG. Request access first; Android may still restrict changing system call records.")
                .setPositiveButton("Request permission") { _, _ -> requestPermissions() }
                .setNegativeButton("Cancel", null).show()
            return
        }
        val editing = editItem != null
        val src = (editItem ?: prefill)?.rep
        val dateFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
        val timeFmt = SimpleDateFormat("hh:mm a", Locale.getDefault())
        val selectedDate = java.util.Calendar.getInstance().apply { if (src != null) timeInMillis = src.date }
        var selectedType = src?.type ?: CallLog.Calls.INCOMING_TYPE
        val originalNoAnswer = editing && src != null && isNoAnswerType(src.type)

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(16))
        }

        // NAME OR NUMBER (+ contact suggestions)
        panel.addView(formLabel("NAME OR NUMBER").apply { setPadding(dp(2), dp(18), 0, dp(8)) })
        val numberInput = styledEdit("Name or number", android.text.InputType.TYPE_CLASS_TEXT).apply {
            if (src != null) setText(src.number)
        }
        panel.addView(fieldBox(numberInput))
        val sugg = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(sugg)
        var picking = false
        fun showSuggestions() {
            sugg.removeAllViews()
            val q = numberInput.text.toString().trim()
            if (picking || q.length < 2) return
            val found = searchContacts(q)
            if (found.isEmpty()) {
                if (q.any { it.isLetter() } && !has(Manifest.permission.READ_CONTACTS)) {
                    sugg.addView(textView("Naam se search ke liye Contacts permission do (tap)", 12f, muted).apply {
                        setPadding(dp(2), dp(10), 0, 0)
                        setOnClickListener {
                            requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), contactsCode)
                        }
                    })
                }
                return
            }
            found.forEach { (nm, num) ->
                sugg.addView(contactRow(nm, num) {
                    picking = true
                    numberInput.setText(num)
                    numberInput.setSelection(numberInput.text.length)
                    picking = false
                    sugg.removeAllViews()
                    hideKeyboard()
                }, LinearLayout.LayoutParams(MP, WC).apply { topMargin = dp(8) })
            }
        }
        numberInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { showSuggestions() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        // Duration / count fields start EMPTY. In Edit a blank field keeps the current value.
        val durationLabel = formLabel("DURATION (SECONDS)")
        val durationInput = styledEdit("Seconds", android.text.InputType.TYPE_CLASS_NUMBER).apply {
            if (!editing && src != null && !isNoAnswerType(src.type)) setText(src.duration.toString())
        }
        val durationBox = fieldBox(durationInput)
        val countLabel = formLabel("")
        val countInput = styledEdit("How many times", android.text.InputType.TYPE_CLASS_NUMBER).apply {
            if (!editing && prefill != null && prefill.grouped) setText(prefill.count.toString())
        }
        val countBox = fieldBox(countInput)
        fun updateTypeFields() {
            val noAnswer = isNoAnswerType(selectedType)
            val showDur = if (noAnswer) View.GONE else View.VISIBLE
            val showCnt = if (noAnswer) View.VISIBLE else View.GONE
            durationLabel.visibility = showDur
            durationBox.visibility = showDur
            countLabel.visibility = showCnt
            countBox.visibility = showCnt
            countLabel.text = "X TIMES GET ${typeName(selectedType).uppercase(Locale.getDefault())}"
            durationInput.hint = "Seconds" +
                if (editing && !originalNoAnswer && src != null) "   (now ${src.duration})" else ""
            countInput.hint = "How many times" +
                if (editing && originalNoAnswer && editItem != null) "   (now ${editItem.count})" else ""
        }

        panel.addView(formLabel("TYPE"))
        panel.addView(createCallTypeChips(selectedType) {
            selectedType = it
            updateTypeFields()
        })

        // DATE & TIME (date picker + analog clock kept as before)
        panel.addView(formLabel("DATE & TIME"))
        val dateTile = valueTile(dateFmt.format(selectedDate.time))
        val timeTile = valueTile(timeFmt.format(selectedDate.time))
        dateTile.setOnClickListener {
            DatePickerDialog(this, { _, y, m, d ->
                selectedDate.set(y, m, d)
                dateTile.text = dateFmt.format(selectedDate.time)
            }, selectedDate.get(java.util.Calendar.YEAR), selectedDate.get(java.util.Calendar.MONTH),
                selectedDate.get(java.util.Calendar.DAY_OF_MONTH)).show()
        }
        timeTile.setOnClickListener {
            showAnalogTimePicker(selectedDate) { timeTile.text = timeFmt.format(selectedDate.time) }
        }
        val dtRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        dtRow.addView(dateTile, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(8) })
        dtRow.addView(timeTile, LinearLayout.LayoutParams(0, dp(52), 1f))
        panel.addView(dtRow)

        panel.addView(durationLabel)
        panel.addView(durationBox)
        panel.addView(countLabel)
        panel.addView(countBox)
        updateTypeFields()

        if (!editing) {
            panel.addView(textView(
                "Ye manually banaya hua record hai, asli call ka saboot nahi. Android insert reject bhi kar sakta hai.",
                11f, muted
            ).apply { setPadding(dp(2), dp(22), 0, 0) })
        }

        if (editing && editItem != null) {
            val extra = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(28), 0, 0)
            }
            extra.addView(actionButton("COPY AS NEW", ink, soft) { addNewEntry(editItem) }.apply { textSize = 11f },
                LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(8) })
            var armed = false
            val del = actionButton("DELETE", danger, dangerBg) { }.apply { textSize = 11f }
            del.setOnClickListener {
                if (!armed) {
                    armed = true
                    del.text = "TAP AGAIN TO DELETE"
                    del.postDelayed({ armed = false; del.text = "DELETE" }, 3000L)
                } else {
                    closeForm()
                    deleteItems(listOf(editItem))
                }
            }
            extra.addView(del, LinearLayout.LayoutParams(0, dp(46), 1f))
            panel.addView(extra)
        }

        fun save() {
            val number = numberInput.text.toString().trim()
            if (number.isBlank() || (number.none { it.isDigit() } && !(editing && number == src?.number))) {
                formFail("Number daalo ya list se contact chuno")
                return
            }
            val noAnswer = isNoAnswerType(selectedType)
            if (editing && editItem != null && src != null) {
                val entry = src
                val item = editItem
                // Blank field = keep what the record already has.
                val countText = countInput.text.toString().trim()
                val wanted = if (countText.isEmpty()) (if (originalNoAnswer) item.count else 1)
                else countText.toIntOrNull()
                if (noAnswer && (wanted == null || wanted !in 1..500)) {
                    formFail("Times: 1 se 500 ke beech daalo")
                    return
                }
                val durationText = durationInput.text.toString().trim()
                val duration = if (durationText.isEmpty()) (if (originalNoAnswer) 0L else entry.duration)
                else durationText.toLongOrNull()
                if (!noAnswer && (duration == null || duration < 0)) {
                    formFail("Duration 0 ya usse zyada daalo")
                    return
                }
                try {
                    val values = ContentValues().apply {
                        put(CallLog.Calls.NUMBER, number)
                        put(CallLog.Calls.TYPE, selectedType)
                        put(CallLog.Calls.DURATION, if (noAnswer) 0L else duration!!)
                        put(CallLog.Calls.DATE, selectedDate.timeInMillis)
                    }
                    val uri = android.content.ContentUris.withAppendedId(CallLog.Calls.CONTENT_URI, entry.id)
                    val updated = contentResolver.update(uri, values, null, null)
                    if (updated > 0) {
                        val sameGroup = number == entry.number && selectedType == entry.type
                        // Group moved to another number/type (or turned into a normal call):
                        // remove the old siblings so nothing is left behind.
                        if (!(sameGroup && noAnswer)) {
                            item.rows.filter { it.id != entry.id }.forEach { deleteById(it.id) }
                        }
                        if (noAnswer) {
                            reconcileCount(number, selectedType, entry.id, wanted!!, selectedDate.timeInMillis)
                        }
                    }
                    Toast.makeText(this, if (updated > 0) "Record updated" else "Android did not update this record", Toast.LENGTH_LONG).show()
                    closeForm()
                    loadCalls()
                } catch (e: Exception) {
                    Toast.makeText(this, "Update failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } else {
                val duration = durationInput.text.toString().trim().toLongOrNull()
                val times = countInput.text.toString().trim().toIntOrNull()
                if (noAnswer && (times == null || times !in 1..500)) {
                    formFail("Times: 1 se 500 ke beech daalo")
                    return
                }
                if (!noAnswer && (duration == null || duration < 0)) {
                    formFail("Duration 0 ya usse zyada daalo")
                    return
                }
                try {
                    val total = if (noAnswer) times!! else 1
                    var created = 0
                    repeat(total) { index ->
                        val values = ContentValues().apply {
                            put(CallLog.Calls.NUMBER, number)
                            put(CallLog.Calls.TYPE, selectedType)
                            put(CallLog.Calls.DATE, selectedDate.timeInMillis - index * 1000L)
                            put(CallLog.Calls.DURATION, if (noAnswer) 0L else duration!!)
                            put(CallLog.Calls.NEW, 1)
                        }
                        if (contentResolver.insert(CallLog.Calls.CONTENT_URI, values) != null) created++
                    }
                    if (created > 0) {
                        Toast.makeText(this, if (noAnswer) "$created ${typeName(selectedType).lowercase(Locale.getDefault())} calls added" else "Manual entry created", Toast.LENGTH_LONG).show()
                        closeForm()
                        loadCalls()
                    } else {
                        Toast.makeText(this, "Android did not create the entry", Toast.LENGTH_LONG).show()
                    }
                } catch (e: SecurityException) {
                    Toast.makeText(this, "Android blocked insertion: ${e.message}", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Could not create entry: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        val title = if (editing) "Edit record" else if (prefill != null) "Copy entry" else "New entry"
        openForm(title, panel, if (editing) "SAVE CHANGES" else "CREATE ENTRY") { save() }
    }

    private fun showAnalogTimePicker(
        calendar: java.util.Calendar,
        onSaved: () -> Unit
    ) {
        val working = calendar.clone() as java.util.Calendar
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }
        val digital = textView(
            SimpleDateFormat("hh:mm a", Locale.getDefault()).format(working.time),
            25f, ink, true
        )
        panel.addView(digital)
        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val clock = AnalogClockView(this, darkTheme, working) { updated ->
            working.set(java.util.Calendar.HOUR_OF_DAY, updated.get(java.util.Calendar.HOUR_OF_DAY))
            working.set(java.util.Calendar.MINUTE, updated.get(java.util.Calendar.MINUTE))
            digital.text = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(working.time)
        }
        val hourMode = Button(this).apply { text = "HOUR" }
        val minuteMode = Button(this).apply { text = "MINUTE" }
        hourMode.setOnClickListener { clock.mode = AnalogClockView.Mode.HOUR; clock.invalidate() }
        minuteMode.setOnClickListener { clock.mode = AnalogClockView.Mode.MINUTE; clock.invalidate() }
        modeRow.addView(hourMode)
        modeRow.addView(minuteMode)
        panel.addView(modeRow)
        panel.addView(clock, LinearLayout.LayoutParams(dp(280), dp(280)))
        AlertDialog.Builder(this)
            .setTitle("Set time")
            .setView(panel)
            .setPositiveButton("Save") { _, _ ->
                calendar.set(java.util.Calendar.HOUR_OF_DAY, working.get(java.util.Calendar.HOUR_OF_DAY))
                calendar.set(java.util.Calendar.MINUTE, working.get(java.util.Calendar.MINUTE))
                calendar.set(java.util.Calendar.SECOND, 0)
                calendar.set(java.util.Calendar.MILLISECOND, 0)
                onSaved()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(item: DisplayItem) {
        val n = item.count
        AlertDialog.Builder(this)
            .setTitle(if (n > 1) "Delete $n call records?" else "Delete call record?")
            .setMessage(
                if (n > 1) "This removes all $n records of this group from this device's call history if Android permits it."
                else "This removes the selected record from this device's call history if Android permits it."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ -> deleteItems(listOf(item)) }
            .show()
    }

    private fun confirmDeleteSelected() {
        val items = currentItems.filter { it.key in selectedKeys }
        if (items.isEmpty()) return
        val rows = items.sumOf { it.count }
        AlertDialog.Builder(this)
            .setTitle("Delete $rows call records?")
            .setMessage("This removes the selected records from this device's call history if Android permits it.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                selectMode = false
                selectedKeys.clear()
                deleteItems(items)
                updateSelectBar()
            }
            .show()
    }

    private fun deleteItems(items: List<DisplayItem>) {
        if (!has(Manifest.permission.WRITE_CALL_LOG)) {
            Toast.makeText(this, "WRITE_CALL_LOG permission not granted", Toast.LENGTH_LONG).show()
            return
        }
        val removed = mutableListOf<CallEntry>()
        var failed = 0
        try {
            // Collection URI with an _ID selection: some OEM CallLog providers
            // reject deletion through an item URI.
            items.flatMap { it.rows }.forEach { row ->
                if (deleteById(row.id) > 0) removed.add(row) else failed++
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Delete blocked: ${e.message}", Toast.LENGTH_LONG).show()
        }
        if (removed.isNotEmpty()) {
            lastDeleted = removed
            showUndo(if (removed.size == 1) "Record deleted" else "${removed.size} records deleted")
        }
        if (failed > 0) {
            Toast.makeText(this, "Android did not delete $failed record(s)", Toast.LENGTH_LONG).show()
        }
        loadCalls()
    }

    private fun showUndo(message: String) {
        undoText.text = message
        undoBar.visibility = View.VISIBLE
        undoBar.removeCallbacks(hideUndo)
        undoBar.postDelayed(hideUndo, 5000L)
    }

    private fun restoreDeleted() {
        val rows = lastDeleted
        lastDeleted = emptyList()
        undoBar.removeCallbacks(hideUndo)
        undoBar.visibility = View.GONE
        if (rows.isEmpty()) return
        var ok = 0
        try {
            rows.forEach { r ->
                val values = ContentValues().apply {
                    put(CallLog.Calls.NUMBER, r.number)
                    put(CallLog.Calls.TYPE, r.type)
                    put(CallLog.Calls.DATE, r.date)
                    put(CallLog.Calls.DURATION, r.duration)
                    put(CallLog.Calls.NEW, 0)
                }
                if (contentResolver.insert(CallLog.Calls.CONTENT_URI, values) != null) ok++
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Restore failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
        Toast.makeText(this, "Restored $ok of ${rows.size}", Toast.LENGTH_LONG).show()
        loadCalls()
    }
}

// Two-page horizontal pager (Editor / Search). Swipe anywhere; cards use tap / long-press only.
private class SwipePager(context: Context, private val onChange: (Int) -> Unit) : FrameLayout(context) {
    var page = 0
        private set
    private var drag = 0f
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var animator: android.animation.ValueAnimator? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        applyOffsets()
    }

    private fun applyOffsets() {
        for (i in 0 until childCount) getChildAt(i).translationX = (i - page) * width + drag
    }

    fun goTo(target: Int) {
        if (target != page) settle(target)
    }

    private fun settle(target: Int) {
        animator?.end()
        val end = if (target == page) 0f else if (page == 0) -width.toFloat() else width.toFloat()
        val a = android.animation.ValueAnimator.ofFloat(drag, end).setDuration(200)
        a.addUpdateListener {
            drag = it.animatedValue as Float
            applyOffsets()
        }
        a.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(an: android.animation.Animator) {
                val changed = target != page
                page = target
                drag = 0f
                applyOffsets()
                if (changed) onChange(page)
            }
        })
        animator = a
        a.start()
    }

    private fun shouldStart(ev: MotionEvent): Boolean {
        val dx = ev.x - downX
        val dy = ev.y - downY
        if (abs(dx) < slop * 2 || abs(dx) < abs(dy) * 1.5f) return false
        return (page == 0 && dx < 0) || (page == 1 && dx > 0)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animator?.end()
                downX = ev.x
                downY = ev.y
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> if (!dragging && shouldStart(ev)) {
                dragging = true
                downX = ev.x
                parent?.requestDisallowInterceptTouchEvent(true)
            }
        }
        return dragging
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && shouldStart(ev)) {
                    dragging = true
                    downX = ev.x
                }
                if (dragging) {
                    val dx = ev.x - downX
                    drag = if (page == 0) dx.coerceIn(-width.toFloat(), 0f) else dx.coerceIn(0f, width.toFloat())
                    applyOffsets()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                val thr = width * 0.2f
                val target = if (page == 0 && drag < -thr) 1 else if (page == 1 && drag > thr) 0 else page
                settle(target)
            }
        }
        return true
    }
}

// Small monochrome call-type icons drawn on a 24x24 grid.
private class CallTypeIcon(context: Context, private val type: Int, tint: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = tint
    }

    override fun onDraw(c: Canvas) {
        val s = width / 24f
        paint.strokeWidth = 2.2f * s
        fun ln(x1: Float, y1: Float, x2: Float, y2: Float) =
            c.drawLine(x1 * s, y1 * s, x2 * s, y2 * s, paint)
        when (type) {
            android.provider.CallLog.Calls.INCOMING_TYPE -> {
                ln(18f, 6f, 6f, 18f); ln(6f, 18f, 6f, 10f); ln(6f, 18f, 14f, 18f)
            }
            android.provider.CallLog.Calls.OUTGOING_TYPE -> {
                ln(6f, 18f, 18f, 6f); ln(18f, 6f, 18f, 14f); ln(18f, 6f, 10f, 6f)
            }
            android.provider.CallLog.Calls.MISSED_TYPE -> {
                ln(14f, 10f, 5f, 19f); ln(5f, 19f, 5f, 12f); ln(5f, 19f, 12f, 19f)
                ln(15f, 3f, 21f, 9f); ln(21f, 3f, 15f, 9f)
            }
            android.provider.CallLog.Calls.REJECTED_TYPE -> {
                c.drawCircle(12f * s, 12f * s, 9f * s, paint); ln(7f, 12f, 17f, 12f)
            }
            android.provider.CallLog.Calls.BLOCKED_TYPE -> {
                c.drawCircle(12f * s, 12f * s, 9f * s, paint); ln(5.6f, 5.6f, 18.4f, 18.4f)
            }
            else -> c.drawCircle(12f * s, 12f * s, 9f * s, paint)
        }
    }
}


private class AnalogClockView(
    context: android.content.Context,
    private val dark: Boolean,
    private val selected: java.util.Calendar,
    private val onTimeChanged: (java.util.Calendar) -> Unit
) : View(context) {
    enum class Mode { HOUR, MINUTE }
    var mode: Mode = Mode.HOUR
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val face = if (dark) 0xFF2A2A2A.toInt() else 0xFFF2F2EF.toInt()
    private val ink = if (dark) 0xFFF2F2F2.toInt() else 0xFF202020.toInt()
    private val muted = if (dark) 0xFFA0A0A0.toInt() else 0xFF777777.toInt()
    private val ring = if (dark) 0xFF444444.toInt() else 0xFFD8D8D2.toInt()
    private val tickMinor = if (dark) 0xFF666666.toInt() else 0xFFB8B8B0.toInt()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(width, height) * 0.43f
        paint.color = face
        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, radius + 12f * density, paint)
        paint.color = ring
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density
        canvas.drawCircle(cx, cy, radius + 12f * density, paint)
        paint.style = Paint.Style.FILL
        for (i in 0 until 60) {
            val angle = Math.PI * i / 30.0 - Math.PI / 2
            val major = i % 5 == 0
            val outer = radius - if (major) 3f * density else 0f
            val inner = outer - if (major) 12f * density else 5f * density
            paint.color = if (major) ink else tickMinor
            paint.strokeWidth = if (major) 2f * density else density
            canvas.drawLine(
                cx + (inner * kotlin.math.cos(angle)).toFloat(),
                cy + (inner * kotlin.math.sin(angle)).toFloat(),
                cx + (outer * kotlin.math.cos(angle)).toFloat(),
                cy + (outer * kotlin.math.sin(angle)).toFloat(), paint
            )
        }
        val hour = selected.get(java.util.Calendar.HOUR)
        val minute = selected.get(java.util.Calendar.MINUTE)
        val handAngle = if (mode == Mode.HOUR) hour % 12 * 30.0 + minute / 2.0 else minute * 6.0
        val handLength = if (mode == Mode.HOUR) radius * 0.58f else radius * 0.78f
        val radians = Math.toRadians(handAngle - 90.0)
        val hx = cx + (handLength * kotlin.math.cos(radians)).toFloat()
        val hy = cy + (handLength * kotlin.math.sin(radians)).toFloat()
        paint.color = ink
        paint.strokeWidth = 4f * density
        canvas.drawLine(cx, cy, hx, hy, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(hx, hy, 13f * density, paint)
        canvas.drawCircle(cx, cy, 5f * density, paint)

        paint.color = ink
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 15f * density
        for (i in 0 until 12) {
            val angle = Math.PI * i / 6.0 - Math.PI / 2
            val labelRadius = radius * 0.82f
            val label = if (mode == Mode.HOUR) {
                val value = if (i == 0) 12 else i
                value.toString()
            } else {
                String.format(Locale.getDefault(), "%02d", i * 5)
            }
            canvas.drawText(
                label,
                cx + (labelRadius * kotlin.math.cos(angle)).toFloat(),
                cy + (labelRadius * kotlin.math.sin(angle)).toFloat() + 5f * density,
                paint
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN && event.action != MotionEvent.ACTION_MOVE) return true
        val dx = event.x - width / 2f
        val dy = event.y - height / 2f
        var degrees = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())) + 90.0
        if (degrees < 0) degrees += 360.0
        if (mode == Mode.HOUR) {
            val hour = ((degrees / 30.0).toInt() % 12)
            val isPm = selected.get(java.util.Calendar.AM_PM) == java.util.Calendar.PM
            selected.set(java.util.Calendar.HOUR_OF_DAY, (hour % 12) + if (isPm) 12 else 0)
        } else {
            val minute = (((degrees / 6.0).toInt() + 0) % 60)
            selected.set(java.util.Calendar.MINUTE, minute)
        }
        onTimeChanged(selected)
        invalidate()
        return true
    }
}
