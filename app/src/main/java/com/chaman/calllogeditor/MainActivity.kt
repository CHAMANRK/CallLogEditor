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

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private val entries = mutableListOf<CallEntry>()
    private val requestCode = 410
    private var searchBox: EditText? = null
    private var activeFilter: String = "All"
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
            singleLine = true
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
                    activeFilter = filter
                    buildUi()
                    renderEntries()
                }
            }
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

        val scroll = ScrollView(this).apply { clipToPadding = false }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(20))
        }
        scroll.addView(list)
        page.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val bottom = LinearLayout(this).apply {
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
        bottom.addView(permission, LinearLayout.LayoutParams(0, dp(48), 1f))
        val add = TextView(this).apply {
            text = "+  NEW ENTRY"
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            background = rounded(ink, 15)
            setOnClickListener { addNewEntry() }
        }
        bottom.addView(add, LinearLayout.LayoutParams(dp(158), dp(48)))
        page.addView(bottom)

        setContentView(page)
    }

    private fun has(permission: String) =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun checkPermissionsAndLoad() {
        if (has(Manifest.permission.READ_CALL_LOG)) loadCalls()
        else {
            status.text = "Call log permission is required."
            requestPermissions()
        }
    }

    private fun requestPermissions() {
        requestPermissions(
            arrayOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG),
            requestCode
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
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
        } catch (e: SecurityException) {
            status.text = "Access denied by Android: ${e.message ?: "permission restricted"}"
        } catch (e: Exception) {
            status.text = "Could not read call history: ${e.message}"
        }
    }


    private fun renderEntries() {
        if (!::list.isInitialized) return
        list.removeAllViews()
        val query = searchBox?.text?.toString()?.trim()?.lowercase(Locale.getDefault()).orEmpty()
        val filtered = entries.filter { entry ->
            val matchesQuery = query.isEmpty() || entry.number.lowercase(Locale.getDefault()).contains(query)
            val matchesFilter = when (activeFilter) {
                "Incoming" -> entry.type == CallLog.Calls.INCOMING_TYPE
                "Outgoing" -> entry.type == CallLog.Calls.OUTGOING_TYPE
                "Missed" -> entry.type == CallLog.Calls.MISSED_TYPE
                else -> true
            }
            matchesQuery && matchesFilter
        }

        if (filtered.isEmpty()) {
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

        val displayEntries = mutableListOf<Pair<CallEntry, Int>>()
        val missedGroups = filtered.filter { it.type == CallLog.Calls.MISSED_TYPE }
            .groupBy { it.number }
        val emittedMissed = mutableSetOf<String>()
        filtered.forEach { entry ->
            if (entry.type == CallLog.Calls.MISSED_TYPE) {
                if (emittedMissed.add(entry.number)) {
                    val group = missedGroups[entry.number].orEmpty()
                    displayEntries.add(group.maxByOrNull { it.date }!! to group.size)
                }
            } else {
                displayEntries.add(entry to 0)
            }
        }

        displayEntries.forEach { (entry, missedCount) ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(15), dp(14), dp(15), dp(12))
                background = rounded(0xFFFFFFFF.toInt(), 18, line)
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val avatar = TextView(this).apply {
                val initial = entry.number.firstOrNull()?.toString() ?: "?"
                text = initial
                textSize = 17f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(ink)
                background = rounded(0xFFF0F0EC.toInt(), 15)
            }
            row.addView(avatar, LinearLayout.LayoutParams(dp(44), dp(44)))

            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
            }
            info.addView(textView(entry.number, 16f, ink, true))
            val time = SimpleDateFormat("dd MMM • hh:mm a", Locale.getDefault()).format(Date(entry.date))
            info.addView(textView("${typeName(entry.type)}  ·  $time", 11f, muted).apply {
                setPadding(0, dp(5), 0, 0)
            })
            row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(row)

            val divider = View(this).apply { setBackgroundColor(line) }
            val dividerParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
            ).apply { topMargin = dp(12); bottomMargin = dp(8) }
            card.addView(divider, dividerParams)

            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val meta = if (missedCount > 0) {
                "MISSED  ·  $missedCount ${if (missedCount == 1) "time" else "times"}"
            } else {
                "${entry.duration}s duration"
            }
            actions.addView(textView(meta, 11f, muted, missedCount > 0),
                LinearLayout.LayoutParams(0, dp(36), 1f))
            val edit = TextView(this).apply {
                text = "EDIT"
                textSize = 10f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(ink)
                background = rounded(0xFFF2F2EF.toInt(), 10)
                setOnClickListener { editEntry(entry) }
            }
            actions.addView(edit, LinearLayout.LayoutParams(dp(64), dp(34)))
            val delete = TextView(this).apply {
                text = "DELETE"
                textSize = 10f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(0xFF8A2929.toInt())
                background = rounded(0xFFF8EEEE.toInt(), 10)
                setOnClickListener { confirmDelete(entry) }
            }
            val deleteParams = LinearLayout.LayoutParams(dp(72), dp(34)).apply {
                marginStart = dp(7)
            }
            actions.addView(delete, deleteParams)
            card.addView(actions)

            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
            list.addView(card, params)
        }
    }

    private fun typeName(type: Int) = when (type) {
        CallLog.Calls.INCOMING_TYPE -> "Incoming"
        CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
        CallLog.Calls.MISSED_TYPE -> "Missed"
        CallLog.Calls.REJECTED_TYPE -> "Rejected"
        CallLog.Calls.BLOCKED_TYPE -> "Blocked"
        else -> "Other"
    }

    private fun editEntry(entry: CallEntry) {
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
        val numberInput = EditText(this).apply {
            hint = "Phone number"
            setText(entry.number)
        }
        val durationInput = EditText(this).apply {
            hint = "Duration in seconds"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(entry.duration.toString())
        }
        val selectedDate = java.util.Calendar.getInstance().apply { timeInMillis = entry.date }
        val dateButton = Button(this).apply { text = "DATE  ·  " + SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(entry.date)) }
        dateButton.setOnClickListener {
            DatePickerDialog(this, { _, y, m, d ->
                selectedDate.set(y, m, d)
                dateButton.text = "DATE  ·  " + SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(selectedDate.time)
            }, selectedDate.get(java.util.Calendar.YEAR), selectedDate.get(java.util.Calendar.MONTH), selectedDate.get(java.util.Calendar.DAY_OF_MONTH)).show()
        }
        val timeButton = Button(this).apply { text = "TIME  ·  " + SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(entry.date)) }
        timeButton.setOnClickListener { showAnalogTimePicker(selectedDate) { timeButton.text = "TIME  ·  " + SimpleDateFormat("hh:mm a", Locale.getDefault()).format(selectedDate.time) } }

        val typeSpinner = Spinner(this)
        val types = listOf("Incoming", "Outgoing", "Missed", "Rejected", "Blocked")
        typeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, types)
        typeSpinner.setSelection(
            when (entry.type) {
                CallLog.Calls.OUTGOING_TYPE -> 1
                CallLog.Calls.MISSED_TYPE -> 2
                CallLog.Calls.REJECTED_TYPE -> 3
                CallLog.Calls.BLOCKED_TYPE -> 4
                else -> 0
            }
        )
        panel.addView(numberInput)
        panel.addView(typeSpinner)
        panel.addView(dateButton)
        panel.addView(timeButton)
        panel.addView(durationInput)

        AlertDialog.Builder(this)
            .setTitle("Edit call record")
            .setView(panel)
            .setPositiveButton("Save") { _, _ ->
                val values = ContentValues().apply {
                    put(CallLog.Calls.NUMBER, numberInput.text.toString())
                    val selectedType = when (typeSpinner.selectedItemPosition) {
                        1 -> CallLog.Calls.OUTGOING_TYPE
                        2 -> CallLog.Calls.MISSED_TYPE
                        3 -> CallLog.Calls.REJECTED_TYPE
                        4 -> CallLog.Calls.BLOCKED_TYPE
                        else -> CallLog.Calls.INCOMING_TYPE
                    }
                    put(CallLog.Calls.TYPE, selectedType)
                    put(CallLog.Calls.DURATION, if (selectedType == CallLog.Calls.MISSED_TYPE) 0L else (durationInput.text.toString().toLongOrNull() ?: entry.duration))
                    put(CallLog.Calls.DATE, selectedDate.timeInMillis)
                }
                try {
                    val uri = android.content.ContentUris.withAppendedId(CallLog.Calls.CONTENT_URI, entry.id)
                    val count = contentResolver.update(uri, values, null, null)
                    Toast.makeText(this, if (count > 0) "Record updated" else "Android did not update this record", Toast.LENGTH_LONG).show()
                    loadCalls()
                } catch (e: SecurityException) {
                    Toast.makeText(this, "Android blocked editing: ${e.message}", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Update failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }


    private fun addNewEntry() {
        if (!has(Manifest.permission.WRITE_CALL_LOG)) {
            AlertDialog.Builder(this)
                .setTitle("Write permission unavailable")
                .setMessage("Android has not granted WRITE_CALL_LOG. Request access first; Android may still restrict inserting system call records.")
                .setPositiveButton("Request permission") { _, _ -> requestPermissions() }
                .setNegativeButton("Cancel", null).show()
            return
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 0)
        }
        val numberInput = EditText(this).apply {
            hint = "Phone number (required)"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
        }
        val typeSpinner = Spinner(this)
        val types = listOf("Incoming", "Outgoing", "Missed", "Rejected", "Blocked")
        typeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, types)
        val selectedDate = java.util.Calendar.getInstance()
        val dateButton = Button(this).apply { text = "DATE  ·  " + SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(selectedDate.time) }
        dateButton.setOnClickListener {
            DatePickerDialog(this, { _, y, m, d ->
                selectedDate.set(y, m, d)
                dateButton.text = "DATE  ·  " + SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(selectedDate.time)
            }, selectedDate.get(java.util.Calendar.YEAR), selectedDate.get(java.util.Calendar.MONTH), selectedDate.get(java.util.Calendar.DAY_OF_MONTH)).show()
        }
        val timeButton = Button(this).apply { text = "TIME  ·  " + SimpleDateFormat("hh:mm a", Locale.getDefault()).format(selectedDate.time) }
        timeButton.setOnClickListener { showAnalogTimePicker(selectedDate) { timeButton.text = "TIME  ·  " + SimpleDateFormat("hh:mm a", Locale.getDefault()).format(selectedDate.time) } }
        val durationInput = EditText(this).apply {
            hint = "Duration in seconds"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("0")
        }
        panel.addView(numberInput)
        panel.addView(typeSpinner)
        panel.addView(dateButton)
        panel.addView(timeButton)
        panel.addView(durationInput)

        AlertDialog.Builder(this)
            .setTitle("New manual call-log entry")
            .setMessage("This creates a manually entered record, not proof that a call actually occurred. Android may reject the insert.")
            .setView(panel)
            .setPositiveButton("Create entry", null)
            .setNegativeButton("Cancel", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val number = numberInput.text.toString().trim()
                        val duration = durationInput.text.toString().toLongOrNull()
                        if (number.isBlank()) {
                            numberInput.error = "Enter a phone number"
                            return@setOnClickListener
                        }
                        if (duration == null || duration < 0) {
                            durationInput.error = "Enter duration 0 or greater"
                            return@setOnClickListener
                        }
                        val callType = when (typeSpinner.selectedItemPosition) {
                            1 -> CallLog.Calls.OUTGOING_TYPE
                            2 -> CallLog.Calls.MISSED_TYPE
                            3 -> CallLog.Calls.REJECTED_TYPE
                            4 -> CallLog.Calls.BLOCKED_TYPE
                            else -> CallLog.Calls.INCOMING_TYPE
                        }
                        val values = ContentValues().apply {
                            put(CallLog.Calls.NUMBER, number)
                            put(CallLog.Calls.TYPE, callType)
                            put(CallLog.Calls.DATE, selectedDate.timeInMillis)
                            put(CallLog.Calls.DURATION, if (callType == CallLog.Calls.MISSED_TYPE) 0L else duration)
                            put(CallLog.Calls.NEW, 1)
                        }
                        try {
                            val uri = contentResolver.insert(CallLog.Calls.CONTENT_URI, values)
                            if (uri != null) {
                                Toast.makeText(this, "Manual entry created", Toast.LENGTH_LONG).show()
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

    private fun confirmDelete(entry: CallEntry) {
        AlertDialog.Builder(this)
            .setTitle("Delete call record?")
            .setMessage("This removes the selected record from this device's call history if Android permits it.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                if (!has(Manifest.permission.WRITE_CALL_LOG)) {
                    Toast.makeText(this, "WRITE_CALL_LOG permission not granted", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                try {
                    // Use the collection URI with an _ID selection. Some OEM
                    // CallLog providers reject deletion through an item URI.
                    val count = contentResolver.delete(
                        CallLog.Calls.CONTENT_URI,
                        "${CallLog.Calls._ID} = ?",
                        arrayOf(entry.id.toString())
                    )
                    Toast.makeText(this, if (count > 0) "Record deleted" else "Android did not delete this record", Toast.LENGTH_LONG).show()
                    loadCalls()
                } catch (e: Exception) {
                    Toast.makeText(this, "Delete blocked: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }.show()
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
