package com.chaman.calllogeditor

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.Bundle
import android.provider.CallLog
import android.view.Gravity
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        checkPermissionsAndLoad()
    }

    private fun buildUi() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 24, 20, 16)
            setBackgroundColor(0xFFF7F7FA.toInt())
        }

        val title = TextView(this).apply {
            text = "Call Log Editor"
            textSize = 25f
            setTextColor(0xFF171717.toInt())
        }
        page.addView(title)

        status = TextView(this).apply {
            text = "Checking permissions…"
            textSize = 13f
            setPadding(0, 10, 0, 12)
        }
        page.addView(status)

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(makeButton("+ New Entry") { addNewEntry() })
        buttons.addView(makeButton("Grant access") { requestPermissions() })
        buttons.addView(makeButton("Refresh") { loadCalls() })
        page.addView(buttons)

        val note = TextView(this).apply {
            text = "Only manage call records on your own device. Android may block write access even after a permission request. Changes are not guaranteed on every Android version or device."
            textSize = 12f
            setTextColor(0xFF555555.toInt())
            setPadding(0, 12, 0, 16)
        }
        page.addView(note)

        val scroll = ScrollView(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        page.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(page)
    }

    private fun makeButton(label: String, action: () -> Unit): Button =
        Button(this).apply { text = label; setOnClickListener { action() } }

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
            status.text = "${entries.size} records loaded • Write access: ${has(Manifest.permission.WRITE_CALL_LOG)}"
            renderEntries()
        } catch (e: SecurityException) {
            status.text = "Access denied by Android: ${e.message ?: "permission restricted"}"
        } catch (e: Exception) {
            status.text = "Could not read call history: ${e.message}"
        }
    }

    private fun renderEntries() {
        list.removeAllViews()
        if (entries.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "No call records found."
                textSize = 16f
                setPadding(8, 24, 8, 24)
            })
            return
        }
        entries.forEach { entry ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(14, 12, 14, 12)
                setBackgroundColor(0xFFFFFFFF.toInt())
            }
            val number = TextView(this).apply {
                text = entry.number
                textSize = 17f
                setTextColor(0xFF161616.toInt())
            }
            val details = TextView(this).apply {
                text = "${typeName(entry.type)} • ${DateFormat.getDateTimeInstance().format(Date(entry.date))} • ${entry.duration}s"
                textSize = 12f
                setTextColor(0xFF666666.toInt())
            }
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(makeButton("Edit") { editEntry(entry) })
                addView(makeButton("Delete") { confirmDelete(entry) })
            }
            card.addView(number)
            card.addView(details)
            card.addView(actions)
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 10 }
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
        panel.addView(durationInput)

        AlertDialog.Builder(this)
            .setTitle("Edit call record")
            .setView(panel)
            .setPositiveButton("Save") { _, _ ->
                val values = ContentValues().apply {
                    put(CallLog.Calls.NUMBER, numberInput.text.toString())
                    put(CallLog.Calls.TYPE, when (typeSpinner.selectedItemPosition) {
                        1 -> CallLog.Calls.OUTGOING_TYPE
                        2 -> CallLog.Calls.MISSED_TYPE
                        3 -> CallLog.Calls.REJECTED_TYPE
                        4 -> CallLog.Calls.BLOCKED_TYPE
                        else -> CallLog.Calls.INCOMING_TYPE
                    })
                    put(CallLog.Calls.DURATION, durationInput.text.toString().toLongOrNull() ?: entry.duration)
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
        val dateInput = EditText(this).apply {
            hint = "Date and time: yyyy-MM-dd HH:mm"
            setText(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date()))
        }
        val durationInput = EditText(this).apply {
            hint = "Duration in seconds"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("0")
        }
        panel.addView(numberInput)
        panel.addView(typeSpinner)
        panel.addView(dateInput)
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
                        val parsedDate = try {
                            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).apply {
                                isLenient = false
                            }.parse(dateInput.text.toString().trim())
                        } catch (_: Exception) { null }
                        val duration = durationInput.text.toString().toLongOrNull()
                        if (number.isBlank()) {
                            numberInput.error = "Enter a phone number"
                            return@setOnClickListener
                        }
                        if (parsedDate == null) {
                            dateInput.error = "Use yyyy-MM-dd HH:mm"
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
                            put(CallLog.Calls.DATE, parsedDate.time)
                            put(CallLog.Calls.DURATION, duration)
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
