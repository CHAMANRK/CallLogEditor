package com.chaman.calllogeditor

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
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
    private var searchBox: EditText? = null
    private var activeFilter: String = "All"
    private val filterChips = linkedMapOf<String, TextView>()
    private val ink = 0xFF171717.toInt()
    private val muted = 0xFF777777.toInt()
    private val paper = 0xFFF5F5F2.toInt()
    private val line = 0xFFE7E7E2.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        checkPermissionsAndLoad()
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

    private fun buildUi() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(12))
            setBackgroundColor(paper)
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(textView("CHAMAN", 11f, muted, true))
        brand.addView(textView("Call history", 29f, ink, true).apply {
            setPadding(0, dp(3), 0, 0)
        })
        top.addView(brand, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val refresh = TextView(this).apply {
            text = "↻"
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(ink)
            background = rounded(0xFFFFFFFF.toInt(), 16, line)
            setOnClickListener { loadCalls() }
        }
        top.addView(refresh, LinearLayout.LayoutParams(dp(48), dp(48)))
        page.addView(top)

        val summary = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = rounded(0xFF202020.toInt(), 20)
        }
        summary.addView(textView("YOUR CALL RECORDS", 10f, 0xFFBDBDBD.toInt(), true))
        status = textView("Checking permissions…", 17f, 0xFFFFFFFF.toInt(), true).apply {
            setPadding(0, dp(7), 0, 0)
        }
        summary.addView(status)
        val summaryParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(20); bottomMargin = dp(16) }
        page.addView(summary, summaryParams)

        val searchWrap = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), 0, dp(12), 0)
            background = rounded(0xFFFFFFFF.toInt(), 15, line)
        }
        searchWrap.addView(textView("⌕", 25f, muted, true))
        searchBox = EditText(this).apply {
            hint = "Search number"
            textSize = 14f
            setTextColor(ink)
            setHintTextColor(muted)
            setSingleLine(true)
            background = null
            setPadding(dp(10), dp(13), dp(4), dp(13))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { renderEntries() }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        searchWrap.addView(searchBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val searchParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50))
        page.addView(searchWrap, searchParams)

        val filters = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(14))
        }
        filterChips.clear()
        listOf("All", "Incoming", "Outgoing", "Missed").forEach { filter ->
            val chip = TextView(this).apply {
                text = filter
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(dp(13), dp(9), dp(13), dp(9))
                setTextColor(if (activeFilter == filter) 0xFFFFFFFF.toInt() else ink)
                background = rounded(if (activeFilter == filter) ink else 0xFFFFFFFF.toInt(), 30,
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
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36))
            lp.marginEnd = dp(7)
            filters.addView(chip, lp)
        }
        page.addView(filters)

        val section = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        section.addView(textView("Recent activity", 18f, ink, true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        section.addView(textView("EDIT YOUR HISTORY", 9f, muted, true))
        page.addView(section)

        scrollView = ScrollView(this).apply { clipToPadding = false }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(20))
        }
        scrollView.addView(list)
        page.addView(scrollView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(4))
        }
        val permission = TextView(this).apply {
            text = "PERMISSIONS"
            textSize = 10f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(muted)
            setPadding(dp(8), dp(12), dp(8), dp(12))
            setOnClickListener { requestPermissions() }
        }
        bottomBar.addView(permission, LinearLayout.LayoutParams(0, dp(48), 1f))
        val add = TextView(this).apply {
            text = "+  NEW ENTRY"
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            background = rounded(ink, 15)
            setOnClickListener { addNewEntry() }
        }
        bottomBar.addView(add, LinearLayout.LayoutParams(dp(158), dp(48)))
        selectBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(8), 0)
            background = rounded(0xFF202020.toInt(), 15)
            visibility = View.GONE
        }
        selectCount = textView("0 selected", 13f, 0xFFFFFFFF.toInt(), true)
        selectBar.addView(selectCount, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        selectBar.addView(actionButton("CANCEL", 0xFFFFFFFF.toInt(), 0xFF3A3A3A.toInt()) { setSelectMode(false) },
            LinearLayout.LayoutParams(dp(72), dp(34)))
        selectBar.addView(actionButton("DELETE", 0xFFFFFFFF.toInt(), 0xFF8A2929.toInt()) { confirmDeleteSelected() },
            LinearLayout.LayoutParams(dp(72), dp(34)).apply { marginStart = dp(7) })
        page.addView(selectBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(48)
        ).apply { topMargin = dp(10); bottomMargin = dp(4) })
        page.addView(bottomBar)

        undoText = textView("", 13f, 0xFFFFFFFF.toInt(), true)
        undoBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(8), dp(8))
            background = rounded(0xFF202020.toInt(), 15)
            elevation = dp(6).toFloat()
            visibility = View.GONE
        }
        undoBar.addView(undoText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        undoBar.addView(actionButton("UNDO", 0xFFFFFFFF.toInt(), 0xFF3A3A3A.toInt()) { restoreDeleted() },
            LinearLayout.LayoutParams(dp(68), dp(34)))
        val root = FrameLayout(this)
        root.addView(page, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        root.addView(undoBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
        ).apply { setMargins(dp(20), 0, dp(20), dp(76)) })
        setContentView(root)
    }

    private fun updateFilterChips() {
        filterChips.forEach { (filter, chip) ->
            val selected = activeFilter == filter
            chip.setTextColor(if (selected) 0xFFFFFFFF.toInt() else ink)
            chip.background = rounded(if (selected) ink else 0xFFFFFFFF.toInt(), 30,
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
            background = rounded(0xFFFFFFFF.toInt(), 18, line)
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
        val query = searchBox?.text?.toString()?.trim()?.lowercase(Locale.getDefault()).orEmpty()
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
                background = rounded(0xFFFFFFFF.toInt(), 18, line)
            }
            empty.addView(textView("No calls found", 17f, ink, true))
            empty.addView(textView("Try another search or filter.", 12f, muted).apply {
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
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(14), dp(15), dp(12))
            background = rounded(
                if (selected) 0xFFEFEFEA.toInt() else 0xFFFFFFFF.toInt(), 18,
                if (selected) ink else line
            )
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val avatar = TextView(this).apply {
            text = if (selected) "✓" else ((name ?: entry.number).firstOrNull()?.toString() ?: "?")
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(if (selected) 0xFFFFFFFF.toInt() else ink)
            background = rounded(if (selected) ink else 0xFFF0F0EC.toInt(), 15)
        }
        row.addView(avatar, LinearLayout.LayoutParams(dp(44), dp(44)))

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
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
        row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(row)

        val divider = View(this).apply { setBackgroundColor(line) }
        card.addView(divider, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
        ).apply { topMargin = dp(12); bottomMargin = dp(8) })

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val meta = if (item.grouped) "${typeName(entry.type).uppercase()}  ×  ${item.count}"
        else "${entry.duration}s duration"
        actions.addView(textView(meta, 11f, muted, item.grouped),
            LinearLayout.LayoutParams(0, dp(36), 1f))
        if (!selectMode) {
            actions.addView(
                actionButton("COPY", ink, 0xFFF2F2EF.toInt()) { addNewEntry(item) },
                LinearLayout.LayoutParams(dp(52), dp(34))
            )
            actions.addView(
                actionButton("EDIT", ink, 0xFFF2F2EF.toInt()) { editEntry(item) },
                LinearLayout.LayoutParams(dp(52), dp(34)).apply { marginStart = dp(6) }
            )
            actions.addView(
                actionButton("DELETE", 0xFF8A2929.toInt(), 0xFFF8EEEE.toInt()) { confirmDelete(item) },
                LinearLayout.LayoutParams(dp(64), dp(34)).apply { marginStart = dp(6) }
            )
        }
        card.addView(actions)

        val back = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), 0, dp(22), 0)
            background = rounded(0xFFE9E9E4.toInt(), 18)
        }
        back.addView(textView("EDIT", 11f, ink, true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        back.addView(textView("DELETE", 11f, 0xFF8A2929.toInt(), true).apply { gravity = Gravity.END },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        return SwipeRow(
            this, card, back,
            onSwipeRight = { editEntry(item) },
            onSwipeLeft = { confirmDelete(item) },
            onLongPress = { startSelect(item) },
            onTap = { if (selectMode) toggleSelect(item) }
        ).apply { swipeEnabled = !selectMode }
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
        if (selectMode) setSelectMode(false) else super.onBackPressed()
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
                chip.setTextColor(if (active) 0xFFFFFFFF.toInt() else ink)
                chip.background = rounded(if (active) ink else 0xFFFFFFFF.toInt(), 22,
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

    private fun editEntry(item: DisplayItem) {
        val entry = item.rep
        if (!has(Manifest.permission.WRITE_CALL_LOG)) {
            AlertDialog.Builder(this)
                .setTitle("Write permission unavailable")
                .setMessage("Android has not granted WRITE_CALL_LOG. The app cannot update system call records without it.")
                .setPositiveButton("Request permission") { _, _ -> requestPermissions() }
                .setNegativeButton("Cancel", null).show()
            return
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 0)
        }
        val numberInput = EditText(this).apply { hint = "Phone number"; setText(entry.number) }
        val durationInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val countInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val selectedDate = java.util.Calendar.getInstance().apply { timeInMillis = entry.date }
        val dateButton = Button(this).apply {
            text = "DATE  ·  " + SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(entry.date))
        }
        dateButton.setOnClickListener {
            DatePickerDialog(this, { _, y, m, d ->
                selectedDate.set(y, m, d)
                dateButton.text = "DATE  ·  " + SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(selectedDate.time)
            }, selectedDate.get(java.util.Calendar.YEAR), selectedDate.get(java.util.Calendar.MONTH), selectedDate.get(java.util.Calendar.DAY_OF_MONTH)).show()
        }
        val timeButton = Button(this).apply {
            text = "TIME  ·  " + SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(entry.date))
        }
        timeButton.setOnClickListener {
            showAnalogTimePicker(selectedDate) {
                timeButton.text = "TIME  ·  " + SimpleDateFormat("hh:mm a", Locale.getDefault()).format(selectedDate.time)
            }
        }
        var selectedType = entry.type
        val originalNoAnswer = isNoAnswerType(entry.type)
        val updateTypeFields = {
            val noAnswer = isNoAnswerType(selectedType)
            durationInput.visibility = if (noAnswer) View.GONE else View.VISIBLE
            countInput.visibility = if (noAnswer) View.VISIBLE else View.GONE
            durationInput.hint = "Duration in seconds" +
                if (!originalNoAnswer) " (now ${entry.duration})" else ""
            countInput.hint = "X Times Get ${typeName(selectedType)}" +
                if (originalNoAnswer) " (now ${item.count})" else ""
        }
        val typeChips = createCallTypeChips(entry.type) {
            selectedType = it
            updateTypeFields()
        }
        panel.addView(numberInput)
        panel.addView(typeChips)
        panel.addView(dateButton)
        panel.addView(timeButton)
        panel.addView(durationInput)
        panel.addView(countInput)
        updateTypeFields()

        val dialog = AlertDialog.Builder(this)
            .setTitle("Edit call record")
            .setView(panel)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val number = numberInput.text.toString().trim()
                if (number.isBlank()) {
                    numberInput.error = "Enter a phone number"
                    return@setOnClickListener
                }
                val noAnswer = isNoAnswerType(selectedType)
                // Blank field = keep what the record already has.
                val countText = countInput.text.toString().trim()
                val wanted = if (countText.isEmpty()) (if (originalNoAnswer) item.count else 1)
                else countText.toIntOrNull()
                if (noAnswer && (wanted == null || wanted !in 1..500)) {
                    countInput.error = "Enter 1 to 500"
                    return@setOnClickListener
                }
                val durationText = durationInput.text.toString().trim()
                val duration = if (durationText.isEmpty()) (if (originalNoAnswer) 0L else entry.duration)
                else durationText.toLongOrNull()
                if (!noAnswer && (duration == null || duration < 0)) {
                    durationInput.error = "Enter duration 0 or greater"
                    return@setOnClickListener
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
                        // Group was moved to another number/type (or turned into a normal
                        // call): remove the old siblings so nothing is left behind.
                        if (!(sameGroup && noAnswer)) {
                            item.rows.filter { it.id != entry.id }.forEach { deleteById(it.id) }
                        }
                        if (noAnswer) {
                            reconcileCount(number, selectedType, entry.id, wanted!!, selectedDate.timeInMillis)
                        }
                    }
                    Toast.makeText(this, if (updated > 0) "Record updated" else "Android did not update this record", Toast.LENGTH_LONG).show()
                    dialog.dismiss()
                    loadCalls()
                } catch (e: Exception) {
                    Toast.makeText(this, "Update failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
        dialog.show()
    }

    // prefill != null → "Copy": opens the form with that record's values filled in.
    private fun addNewEntry(prefill: DisplayItem? = null) {
        if (!has(Manifest.permission.WRITE_CALL_LOG)) {
            AlertDialog.Builder(this)
                .setTitle("Write permission unavailable")
                .setMessage("Android has not granted WRITE_CALL_LOG. Request access first; Android may still restrict inserting system call records.")
                .setPositiveButton("Request permission") { _, _ -> requestPermissions() }
                .setNegativeButton("Cancel", null).show()
            return
        }
        val src = prefill?.rep
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 0)
        }
        val numberInput = EditText(this).apply {
            hint = "Phone number (required)"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            if (src != null) setText(src.number)
        }
        var selectedType = src?.type ?: CallLog.Calls.INCOMING_TYPE
        val selectedDate = java.util.Calendar.getInstance().apply { if (src != null) timeInMillis = src.date }
        val dateButton = Button(this).apply { text = "DATE  ·  " + SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(selectedDate.time) }
        dateButton.setOnClickListener {
            DatePickerDialog(this, { _, y, m, d ->
                selectedDate.set(y, m, d)
                dateButton.text = "DATE  ·  " + SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(selectedDate.time)
            }, selectedDate.get(java.util.Calendar.YEAR), selectedDate.get(java.util.Calendar.MONTH), selectedDate.get(java.util.Calendar.DAY_OF_MONTH)).show()
        }
        val timeButton = Button(this).apply { text = "TIME  ·  " + SimpleDateFormat("hh:mm a", Locale.getDefault()).format(selectedDate.time) }
        timeButton.setOnClickListener { showAnalogTimePicker(selectedDate) { timeButton.text = "TIME  ·  " + SimpleDateFormat("hh:mm a", Locale.getDefault()).format(selectedDate.time) } }
        // Fields start empty: the hint only tells what the field is.
        val durationInput = EditText(this).apply {
            hint = "Duration in seconds"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            if (src != null && !isNoAnswerType(src.type)) setText(src.duration.toString())
        }
        val countInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            if (prefill != null && prefill.grouped) setText(prefill.count.toString())
        }
        val updateTypeFields = {
            val noAnswer = isNoAnswerType(selectedType)
            durationInput.visibility = if (noAnswer) View.GONE else View.VISIBLE
            countInput.visibility = if (noAnswer) View.VISIBLE else View.GONE
            countInput.hint = "X Times Get ${typeName(selectedType)}"
        }
        val typeChips = createCallTypeChips(selectedType) {
            selectedType = it
            updateTypeFields()
        }
        panel.addView(numberInput)
        panel.addView(typeChips)
        panel.addView(dateButton)
        panel.addView(timeButton)
        panel.addView(durationInput)
        panel.addView(countInput)
        updateTypeFields()

        AlertDialog.Builder(this)
            .setTitle(if (prefill != null) "Copy call-log entry" else "New manual call-log entry")
            .setMessage("This creates a manually entered record, not proof that a call actually occurred. Android may reject the insert.")
            .setView(panel)
            .setPositiveButton("Create entry", null)
            .setNegativeButton("Cancel", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val number = numberInput.text.toString().trim()
                        val duration = durationInput.text.toString().trim().toLongOrNull()
                        val times = countInput.text.toString().trim().toIntOrNull()
                        val noAnswer = isNoAnswerType(selectedType)
                        if (number.isBlank()) {
                            numberInput.error = "Enter a phone number"
                            return@setOnClickListener
                        }
                        if (noAnswer && (times == null || times !in 1..500)) {
                            countInput.error = "Enter 1 to 500"
                            return@setOnClickListener
                        }
                        if (!noAnswer && (duration == null || duration < 0)) {
                            durationInput.error = "Enter duration 0 or greater"
                            return@setOnClickListener
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
                                Toast.makeText(this, if (noAnswer) "$created ${typeName(selectedType).lowercase()} calls added" else "Manual entry created", Toast.LENGTH_LONG).show()
                                dialog.dismiss()
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
                dialog.show()
            }
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
        val clock = AnalogClockView(this, working) { updated ->
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

private class SwipeRow(
    context: Context,
    private val front: View,
    back: View,
    private val onSwipeRight: () -> Unit,
    private val onSwipeLeft: () -> Unit,
    private val onLongPress: () -> Unit,
    private val onTap: () -> Unit
) : FrameLayout(context) {
    var swipeEnabled = true
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var longFired = false
    private val longPressRunnable = Runnable {
        if (swipeEnabled && !dragging) {
            longFired = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onLongPress()
        }
    }

    init {
        addView(back, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        addView(front, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
    }

    private fun startPress(ev: MotionEvent) {
        downX = ev.x
        downY = ev.y
        dragging = false
        longFired = false
        removeCallbacks(longPressRunnable)
        postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
    }

    private fun beginDrag() {
        dragging = true
        removeCallbacks(longPressRunnable)
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> startPress(ev)
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (swipeEnabled && !dragging && abs(dx) > slop && abs(dx) > abs(dy)) {
                    beginDrag()
                    return true
                }
                if (abs(dx) > slop || abs(dy) > slop) removeCallbacks(longPressRunnable)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(longPressRunnable)
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (!dragging && swipeEnabled && abs(dx) > slop && abs(dx) > abs(dy)) beginDrag()
                if (dragging) front.translationX = dx
                else if (abs(dx) > slop || abs(dy) > slop) removeCallbacks(longPressRunnable)
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                val dx = ev.x - downX
                if (dragging) {
                    dragging = false
                    val threshold = width * 0.28f
                    front.animate().translationX(0f).setDuration(180).start()
                    if (dx <= -threshold) onSwipeLeft() else if (dx >= threshold) onSwipeRight()
                } else if (!longFired && abs(dx) < slop && abs(ev.y - downY) < slop) {
                    onTap()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                if (dragging) {
                    dragging = false
                    front.animate().translationX(0f).setDuration(180).start()
                }
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
    private val selected: java.util.Calendar,
    private val onTimeChanged: (java.util.Calendar) -> Unit
) : View(context) {
    enum class Mode { HOUR, MINUTE }
    var mode: Mode = Mode.HOUR
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val face = 0xFFF2F2EF.toInt()
    private val ink = 0xFF202020.toInt()
    private val muted = 0xFF777777.toInt()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(width, height) * 0.43f
        paint.color = face
        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, radius + 12f * density, paint)
        paint.color = 0xFFD8D8D2.toInt()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density
        canvas.drawCircle(cx, cy, radius + 12f * density, paint)
        paint.style = Paint.Style.FILL
        for (i in 0 until 60) {
            val angle = Math.PI * i / 30.0 - Math.PI / 2
            val major = i % 5 == 0
            val outer = radius - if (major) 3f * density else 0f
            val inner = outer - if (major) 12f * density else 5f * density
            paint.color = if (major) ink else 0xFFB8B8B0.toInt()
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
