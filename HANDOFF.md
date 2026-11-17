# CHAMAN Call Log Editor — Project Handoff

**Document purpose:** This file is the technical handoff for the next developer/AI session. Read it before changing code. It records the supplied project archive's actual contents, the intended product behaviour, the Android/CI setup, known risks, and the next debugging steps.

> **Source-of-truth note:** This handoff was written after inspecting `CallLogEditor_TypeChips_MissedCount_v06.zip`. Where this document describes code that exists, it refers to that archive. Where it describes a desired future change or an issue reported during development, it is labelled accordingly. Do not assume an item is implemented merely because it appears in the product requirements.

---

## 1. Project identity

- **App name / branding:** CHAMAN Call Log / Call Log Editor
- **Repository:** `https://github.com/CHAMANRK/CallLogEditor`
- **GitHub owner:** `CHAMANRK`
- **Local Termux project path:** `~/test/CallLogEditor`
- **Android application ID:** `com.chaman.calllogeditor`
- **Kotlin package / namespace:** `com.chaman.calllogeditor`
- **Main activity:** `com.chaman.calllogeditor.MainActivity`
- **Project type:** Native Android application using Kotlin and Android Views.
- **UI toolkit:** Android framework Views (`LinearLayout`, `TextView`, `EditText`, `Button`, `AlertDialog`, custom `View`). This is **not** a Jetpack Compose app.
- **User development environment:** Android phone + Termux + GitHub Actions; assume no laptop is available.

The application reads and manages the device's Android call history through `CallLog.Calls.CONTENT_URI`. It does not use a private database as the source of call records.

## 2. Technology and build configuration

From `app/build.gradle.kts` in the supplied archive:

- Android Gradle application plugin: `com.android.application`
- Kotlin Android plugin: `org.jetbrains.kotlin.android`
- `compileSdk = 35`
- `minSdk = 26`
- `targetSdk = 35`
- Java source/target compatibility: 17
- Kotlin JVM target: 17
- Release minification: disabled (`isMinifyEnabled = false`)
- `versionCode`: environment variable `VERSION_CODE`, default `1`
- `versionName`: environment variable `VERSION_NAME`, default `0.4.0`
- No external UI/dependency libraries are declared in the app module build file shown in this archive.

Root files include `build.gradle.kts`, `settings.gradle.kts`, and `gradle.properties`. The project currently builds using the `gradle` command in GitHub Actions, rather than a checked-in Gradle wrapper being visible in this archive.

## 3. Android permissions and platform restrictions

`app/src/main/AndroidManifest.xml` declares:

- `android.permission.READ_CALL_LOG`
- `android.permission.WRITE_CALL_LOG`

The app requests runtime access and checks permission state before reading/writing. These permissions are restricted/sensitive Android permissions. A runtime permission result does **not** guarantee that the operating system, Play policy state, or OEM call-log provider will permit every write, insert, or delete operation.

Important engineering rule:

- Do not attempt to bypass Android permission enforcement or OEM provider restrictions.
- Handle `SecurityException` and provider failures visibly.
- Never claim a record was changed unless the ContentResolver operation reports success.
- Test on the actual target devices/OEMs because CallLog provider behaviour can differ.

The manifest sets `allowBackup=false`, app label `CHAMAN Call Log`, a Material Light no-action-bar theme, and a launcher `MainActivity`.

## 4. Current application architecture

The inspected archive is deliberately small and most application logic is in one file:

`app/src/main/java/com/chaman/calllogeditor/MainActivity.kt`

### Main data model

`CallEntry` contains:

- `id: Long` — Android call-log row `_ID`
- `number: String` — phone number
- `type: Int` — Android call type constant
- `date: Long` — timestamp in milliseconds
- `duration: Long` — duration in seconds

### MainActivity responsibilities

`MainActivity` currently owns:

- Main screen construction and styling
- Runtime permission checks and permission request handling
- Reading call records from Android's CallLog provider
- Search and call-type filters
- Missed-call grouping for display
- Creating and editing call-log records
- Deleting an individual record
- Date selection and analog time selection
- Error and success messages

The app uses a monochrome, restrained visual style. Key colors in the source include near-black ink (`#171717`), muted grey (`#777777`), warm off-white page background (`#F5F5F2`), and pale divider (`#E7E7E2`). Preserve this visual direction: simple, premium, no neon, no unnecessary gradients or mixed colours.

## 5. Main screen and reading call history

The main screen includes:

- CHAMAN branding and a “Call history” heading
- Refresh control that reloads call records
- Summary/status panel showing record count and whether write permission appears available
- Search input for phone-number matching
- Filter chips: `All`, `Incoming`, `Outgoing`, `Missed`
- Call record cards
- Manual new-entry flow (subject to write permission)

`loadCalls()` queries the following columns from `CallLog.Calls.CONTENT_URI`:

- `_ID`
- `NUMBER`
- `TYPE`
- `DATE`
- `DURATION`

Results are ordered by date descending. A `SecurityException` or other exception is caught and reported in the status area.

Search currently matches the phone number (case-insensitive). The main list filters incoming, outgoing, or missed call types. The filter chips on the main list are separate from the five call-type selection chips used in the edit/new-entry dialogs.

## 6. Missed-call grouping — implemented in inspected source

The list renderer groups missed-call rows by their phone-number string. It displays one representative card per number, using the latest-dated missed record as the representative, and displays the group size as a count.

The current visible count label in the source is:

`MISSED  ×  <count>`

Important details:

- Grouping is for display; it does not merge database rows into one physical row.
- Each missed call remains an individual Android CallLog record.
- Grouping currently keys on the exact `number` string. Number formatting differences (country code, spaces, punctuation) can therefore result in separate groups.
- The representative is selected by latest `date`.
- Non-missed calls are displayed individually.

Do not change the underlying storage model to a synthetic single missed-call row. The requested behaviour is multiple real provider rows, grouped visually in this app.

## 7. Call type chips — implemented in inspected source

The edit and new-entry forms use a horizontal set of selectable call-type chips:

1. Incoming
2. Outgoing
3. Missed
4. Rejected
5. Blocked

The selected chip uses a dark/black fill with light text; unselected chips use a light background and dark text. Keep one type selected at a time and preserve the monochrome styling.

The main screen filter row is not the same component: it currently has only All/Incoming/Outgoing/Missed filters.

## 8. New manual call entry — implemented behaviour

The New Entry flow checks `WRITE_CALL_LOG` before opening the form. If permission is unavailable, it explains that Android may still restrict inserting system call records and offers to request permission.

Form fields and behaviour:

- Phone number (required)
- Call type chips
- Date button using Android `DatePickerDialog`
- Time button using the custom analog time picker
- Duration in seconds for non-missed call types
- `X Times Get Missed` count field for missed calls

When the selected type changes:

- Missed: hide duration input and show missed-count input.
- Any other supported type: show duration input and hide missed-count input.

Validation in the inspected source:

- Phone number must not be blank.
- Missed count must be from 1 through 500.
- Non-missed duration must parse as a non-negative long.

On a missed-call insertion, the app attempts to insert the requested number of separate missed-call rows. Each row has the same phone number, missed type, zero duration, `NEW=1`, and timestamps offset by one second (`selectedDate.timeInMillis - index * 1000L`). For non-missed types, one row is inserted with the supplied duration.

The app counts successful insert return values and reports the number created. A provider may reject some or all inserts; errors are shown rather than bypassed.

The form itself warns that this is a manual record and not proof that a real call occurred.

## 9. Editing an existing entry — implemented behaviour / important details

The edit flow provides the same five type chips and conditionally shows either duration or missed-count input.

For missed calls, it validates a count in the range 1–500. For non-missed calls, it validates non-negative duration.

The selected existing row is updated through `ContentResolver.update()` using an item URI built from the row ID. When the selected type is missed and the update succeeds, the app calls `reconcileMissedCount(...)` to adjust the number of missed-call rows for that number.

### Missed-count reconciliation algorithm in the inspected source

`missedCountForNumber(number)` counts missed rows for the exact phone number.

`reconcileMissedCount(number, keepId, wanted, date)`:

1. Queries missed rows for that exact number, ordered newest first.
2. Keeps the selected row ID and removes excess rows if the current count is greater than requested.
3. If the requested count is greater than current count, inserts the difference as new missed rows.
4. Inserted missed rows use missed type, zero duration, `NEW=1`, and staggered timestamps.

Potential edge cases to test carefully:

- Changing the phone number while editing a missed group: the selected row is updated to the new number before reconciliation. Verify that old-number sibling rows are not unintentionally left behind or counted incorrectly. The current implementation reconciles using the new number only.
- Changing a non-missed row into a missed row, or a missed row into a non-missed row.
- Partial provider success during add/remove operations.
- Whether the selected row ID is retained in all cases.
- Duplicate or empty phone-number values.

Do not silently rewrite this algorithm without testing its effect on the physical rows in the device call log.

## 10. Date and analog time picker — implemented in inspected source

Date selection uses Android `DatePickerDialog`.

Time selection uses a custom `AnalogClockView` in the same Kotlin source file:

- Custom drawing with Android Canvas/Paint
- Separate HOUR and MINUTE selection modes
- Touch interaction (`onTouchEvent`) to choose a position on the dial
- Digital time preview
- Save applies the selected hour/minute to the original calendar
- Seconds and milliseconds are reset to zero on save

Both New Entry and Edit use the shared `showAnalogTimePicker(...)` function. Preserve the analog time control in both forms. Date remains a calendar/date-picker control; do not replace it with the analog dial.

## 11. Deleting records — implemented in inspected source

Deletion uses the collection URI with an `_ID` selection rather than relying on an item URI:

```kotlin
contentResolver.delete(
    CallLog.Calls.CONTENT_URI,
    "${CallLog.Calls._ID} = ?",
    arrayOf(entry.id.toString())
)
```

The source comment explains that some OEM CallLog providers reject deletion through an item URI. Keep this collection-URI selection pattern unless device testing proves a better compatible approach.

The app checks write permission, asks for confirmation, and reports whether Android deleted the record or blocked the operation.

## 12. GitHub Actions build and signing

Workflow file:

`.github/workflows/android.yml`

Triggers:

- Push to `main` or `master`
- Pull request targeting `main` or `master`
- Manual `workflow_dispatch`

Workflow steps:

1. Checkout source (`actions/checkout@v4`)
2. Set up Temurin JDK 17 (`actions/setup-java@v4`)
3. Restore the permanent signing keystore from `ANDROID_KEYSTORE_BASE64`
4. Set up Gradle (`gradle/actions/setup-gradle@v4`)
5. Run `gradle :app:assembleRelease`
6. Upload `app/build/outputs/apk/release/app-release.apk` as an artifact named `CHAMAN-CallLog-v<run_number>`

The workflow supplies:

- `ANDROID_KEYSTORE_PATH = ${{ github.workspace }}/release-key.jks`
- `ANDROID_KEYSTORE_PASSWORD = secrets.ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS = secrets.ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD = secrets.ANDROID_KEY_PASSWORD`
- `VERSION_CODE = github.run_number`
- `VERSION_NAME = 0.4.<github.run_number>`

### Required GitHub Actions secrets

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Never place any of these values in source code, documentation, commits, issue reports, or chat messages. Never include the keystore file or Base64 contents in the project ZIP.

### Permanent signing identity recorded during setup

The developer has a permanent local keystore at:

`~/.android/calllogeditor-release.jks`

Known non-secret metadata:

- Alias: `calllogeditor`
- Keystore format: PKCS12
- Entry type: PrivateKeyEntry
- SHA-1 certificate fingerprint: `48:58:24:8F:83:BC:B0:69:51:D8:FB:B4:B6:75:61:BF:7B:DD:97:EA`
- SHA-256 certificate fingerprint: `37:63:49:91:68:03:3A:29:FE:AE:EB:DB:7C:8D:CF:C5:FF:B1:0E:19:F7:9A:60:4C:1C:04:8E:5E:72:6F:4B:FA`

The keystore was successfully opened locally with `keytool -list -v`, confirming the store password and alias were readable. This does **not** prove that the private key can be unlocked using the separate `ANDROID_KEY_PASSWORD` secret.

### Critical signing continuity rule

Do not generate a new keystore, change the alias, change the application ID, or change signing identity as a quick fix. Android in-place upgrades require the same application ID and signing certificate. Losing the private keystore/passwords can prevent future updates over already-installed copies.

An earlier debug-signed APK may not accept the first permanently signed APK as an in-place update. If Android reports a signature mismatch during the one-time migration from debug signing, uninstall the old test APK once, install the permanently signed release, and thereafter keep using the same permanent key.

## 13. Known signing/CI issue at handoff time

A previous GitHub Actions run reached `:app:packageRelease` and failed with a key-unlock error similar to:

`KeytoolException: Failed to read key ... Get key failed: Given final block not properly padded`

This points to a likely mismatch between the private-key password and keystore password, a malformed Base64 secret, or a wrong secret value. The local `keytool -list` check alone cannot validate the private-key password.

The developer successfully refreshed the Base64 secret using this Termux command:

```bash
base64 -w 0 ~/.android/calllogeditor-release.jks | gh secret set ANDROID_KEYSTORE_BASE64 -R CHAMANRK/CallLogEditor
```

The next suggested diagnostic was to set `ANDROID_KEY_PASSWORD` to the same password used to create/open this PKCS12 keystore, if that is indeed the password configured for its private key. The developer had been given the command below but had not yet confirmed running it at the time of this handoff:

```bash
read -rsp "Enter your existing keystore password: " P
echo
printf '%s' "$P" | gh secret set ANDROID_KEY_PASSWORD -R CHAMANRK/CallLogEditor
unset P
```

Do not ask the developer to paste the password into chat. If the error persists, add a temporary private-key access verification step in GitHub Actions before Gradle packaging, using `keytool -certreq` with the environment variables and a temporary CSR file; do not print secret values. Remove the temporary CSR afterward. Also verify that the Base64 secret decodes to a valid PKCS12 keystore and that the alias is exactly `calllogeditor`.

### Important archive-vs-discussion discrepancy

The `app/build.gradle.kts` inspected inside this v06 ZIP does **not** contain an explicit `storeType = "PKCS12"` line. Earlier development discussion said that line had been added locally with `sed`, but the supplied archive does not show it. If explicit PKCS12 configuration is required, add it to the `create("stable")` signing config and inspect the committed file after editing. Do not assume a previous local edit exists in this ZIP.

## 14. Termux / Git workflow

Project path:

```bash
cd ~/test/CallLogEditor
```

Review status and commit changes:

```bash
git status
git add .
git commit -m "Describe the change"
git push origin main
```

List recent CI runs:

```bash
gh run list --repo CHAMANRK/CallLogEditor
```

Watch a run:

```bash
gh run watch --repo CHAMANRK/CallLogEditor
```

Retry the workflow without changing source:

```bash
git commit --allow-empty -m "Retry Android release signing"
git push origin main
```

To inspect the signing certificate locally (this reads the keystore; do not share passwords):

```bash
keytool -list -v -keystore ~/.android/calllogeditor-release.jks
```

## 15. Testing checklist before merging changes

### Build / packaging

- [ ] `gradle :app:assembleDebug` succeeds locally where Android SDK/Gradle are available.
- [ ] GitHub Actions release build succeeds.
- [ ] Release APK is signed with the permanent certificate (verify certificate fingerprint locally with Android build tools/keytool as appropriate).
- [ ] `versionCode` increases for each workflow run.
- [ ] Existing permanently signed installation updates in place.

### Permissions / read

- [ ] First launch requests/handles READ_CALL_LOG appropriately.
- [ ] Denied permission shows a useful status, not a crash.
- [ ] Call list loads and refresh works.
- [ ] Search filters by number.
- [ ] All/Incoming/Outgoing/Missed filters work.
- [ ] Provider errors are displayed safely.

### Missed-call grouping

- [ ] Several missed rows for the same exact number appear as one card.
- [ ] The card displays `MISSED × N` accurately.
- [ ] Latest missed row supplies the displayed timestamp/edit target.
- [ ] Different number strings are understood to form separate groups.
- [ ] Other call types remain separate rows.

### New entry

- [ ] Phone number is required.
- [ ] Each of the five type chips selects correctly.
- [ ] Missed type shows `X Times Get Missed` and hides duration.
- [ ] Non-missed types show duration and hide missed count.
- [ ] Missed count limits are enforced (1–500).
- [ ] Creating N missed calls attempts N individual provider inserts with duration 0.
- [ ] Non-missed entry creates one record with entered duration.
- [ ] Android/provider rejection is reported without crashing.

### Edit / delete

- [ ] Editing ordinary call updates number/type/date/duration as expected.
- [ ] Editing missed group count upward inserts the difference.
- [ ] Editing missed group count downward deletes the difference.
- [ ] Changing a missed group's phone number is tested specifically; inspect for leftover old-number rows.
- [ ] Type transitions (missed ↔ non-missed) are tested.
- [ ] Delete confirmation works.
- [ ] Delete uses collection URI plus `_ID` selection and works on target OEMs.

### Date/time

- [ ] DatePicker works in New Entry and Edit.
- [ ] Analog clock opens in both forms.
- [ ] Hour and minute modes work by touch.
- [ ] Digital preview matches dial selection.
- [ ] Save applies time and resets seconds/milliseconds to zero.
- [ ] Cancel does not change the original selected date/time.

## 16. Development rules / do-not-break list

1. **Preserve package/application ID:** `com.chaman.calllogeditor`.
2. **Preserve permanent signing key and alias:** `calllogeditor`.
3. **Do not regenerate signing keystore.**
4. **Do not remove real provider operations** for read, update, insert, or delete.
5. **Do not convert the app to Compose** unless explicitly requested; the supplied app uses Android Views.
6. **Do not regress missed-call grouping or count reconciliation.**
7. **Do not replace the analog clock** with a standard time picker without approval.
8. **Do not change date selection away from DatePickerDialog** without approval.
9. **Keep type chips monochrome and single-select.**
10. **Keep errors honest:** provider permission does not guarantee writes will succeed.
11. Make changes incrementally and inspect the exact ZIP/source before claiming an implementation is complete.
12. Build and test after each meaningful change. Do not say a build passed unless the build output confirms it.

## 17. Suggested next steps for the next session

1. Confirm whether `ANDROID_KEY_PASSWORD` was updated to the correct private-key password.
2. Re-run the GitHub Actions workflow and inspect the first failing step, if any.
3. If signing still fails, validate private-key unlock in CI before running Gradle; do not rotate the key.
4. Compare the current GitHub repository files with this archive, especially `app/build.gradle.kts` and `.github/workflows/android.yml`.
5. Confirm whether the latest source is actually the v06 ZIP or whether there are newer local commits not included in it.
6. Once signing is stable, test the missed-call group edit edge cases on the user's real Android device(s).
7. Keep a clean, verified source ZIP for each release and include this handoff file in future project archives.

---

**End of handoff.** This document intentionally contains no keystore, password, API key, Base64 signing secret, or other credential.

---

## 18. v07 changes (written after v06; NOT yet compiled/tested on device)

Source was patched but no Kotlin compiler/Android SDK was available when written. The first GitHub Actions run is the first compile check. If it fails, read the compiler error and fix that line only; do not rewrite features.

- Missed / Rejected / Blocked all use the "no-answer" logic (`isNoAnswerType`): duration hidden, "X Times Get <Type>" count field, N separate rows (duration 0), grouped in the list by exact number + same type (`MISSED × N`, `REJECTED × N`, `BLOCKED × N`). `reconcileMissedCount` became `reconcileCount(number, type, ...)`.
- Edit of a group: if number or type changed, old sibling rows are deleted, then the count is reconciled for the new number/type. Turning a group into a normal call (Incoming/Outgoing) also deletes the old siblings.
- Duration and count fields start EMPTY with a hint. In Edit a blank field keeps the current value (hint shows "now N"); in New a blank required field shows an error. Copy pre-fills.
- Swipe on a card: right = edit, left = delete (delete still asks for confirmation). Long press = multi-select mode (bottom select bar: CANCEL / DELETE). Back button exits select mode.
- Contact names via `READ_CONTACTS` (new permission, asked once; app works if denied). Name shown as the title, number below; search matches name too.
- COPY button on each card opens New Entry pre-filled.
- Undo bar (5 s) after any delete; restores all deleted rows via insert (new `_ID`s).
- Small monochrome call-type icons (custom `CallTypeIcon` view).
- Files: `DisplayItem`, `SwipeRow`, `CallTypeIcon` added in `MainActivity.kt`; `READ_CONTACTS` added to the manifest.

---

## 19. v09 changes (NOT yet compiled/tested on device)

Added per-number **Number stats** (counts only, no call list). New `STATS` button in the bottom bar (left of `+ NEW ENTRY`). Opens a dialog with a phone-number field (pre-filled from the search box); results update live as digits change.

- Matching: last 10 digits of the number (ignores +91 / 0 / spaces). Needs at least 5 digits.
- Shows "X times" for: Incoming (connected) + total incoming time, Outgoing connected (duration > 0) + total outgoing time, Outgoing not connected (duration = 0), Missed, Rejected, Blocked (only if > 0), Total calls + total call time (incoming + outgoing).
- Code: `showNumberStats()`, `last10()`, `fmtDuration()` in `MainActivity.kt` (before `typeName`). Reads from the in-memory `entries` list (already loaded by `loadCalls()`); no new permission.
- Missed/Rejected counts are real provider rows (each row = 1 time).
- Test on device: pick a number with known history and compare each count with the call log; check dark and light theme.

---

## 20. v10 changes — UI redesign (NOT yet compiled/tested on device)

Replaces the v09 STATS dialog. Written without Android SDK; first GitHub Actions run is the compile check. If it fails, fix only the reported line.

- **Two swipeable tabs** (custom `SwipePager`, also tap on tab labels): **Editor** (filters All/Incoming/Outgoing/Missed, call list, `+ NEW ENTRY`, select bar) and **Search** (name or number → stats tiles).
- **Search tab:** typing letters (or <5 digits) lists matching contacts (`searchContacts`, needs READ_CONTACTS; also uses names already resolved from the call log); tap one → stats. 5+ digits → stats by last 10 digits. Tiles (counts shown as "N times"): dark TOTAL CALLS tile with total call time, then Incoming (+ total time), Outgoing connected (+ total time), Outgoing not connected, Missed, Rejected, Blocked (only if > 0). All-time over whatever the phone's call log still holds.
- **Edit / New / Copy are now a full-screen form** (`showEntryForm`, `openForm`, `closeForm`) instead of AlertDialog. Slides in from the right, back arrow, labelled sections, big SAVE button, inline red error line (`formFail`) instead of EditText.error. Same save/update/insert logic as v08 (reconcileCount, blank = keep current in Edit, 1–500 times, etc.).
- **Name search in the form:** the number field is "Name or number"; typing shows contact suggestions (`contactRow`), tap fills the number. A number needs at least one digit.
- Edit form has `COPY AS NEW` and `DELETE` (two-tap confirm: first tap arms, second within 3 s deletes; Undo bar still appears).
- **Removed:** card swipe-right/left gestures (`SwipeRow` deleted) because they conflict with page swipe; the dark "YOUR CALL RECORDS" panel; the search box on the Editor tab; PERMISSIONS and STATS buttons. Card tap = edit, long press = multi-select (unchanged). The small status line under the title is tappable and requests permissions.
- Still AlertDialogs (kept on purpose): permission-needed message, multi-delete confirm, Android DatePickerDialog, analog time picker.
- Window insets handled on `rootLayout` (status bar / nav bar / keyboard) and `SOFT_INPUT_ADJUST_RESIZE`, because targetSdk 35 draws edge to edge.
- Device test checklist: swipe between tabs (and tab tap); list tap opens form; long press select + delete; New entry with name suggestion; Edit missed group count; Copy as new; two-tap delete + Undo; stats counts vs real call log; dark + light theme; keyboard does not hide the SAVE button.

---

## 21. v11 changes (NOT yet compiled/tested on device)

Two small fixes on top of v10:
- **Time picker now has AM / PM chips** (under the digital time). Tapping flips the period of the working time (`setPeriod`); the clock still keeps the selected period when hour is tapped. Dialog content is wrapped in a ScrollView for small screens.
- **Duration is three blocks: Hours | Minutes | Seconds** (replaces "Duration in seconds"). Total = h*3600 + m*60 + s (`readDuration`). Edit: all three blank = keep current duration (label shows "now 1 hr 2 min"); Copy pre-fills the blocks; New: all blank = error, 0 is fine. Missed/Rejected/Blocked still use the "X times" field.
- Test: set a time, tap AM then PM and Save; enter 0h 2m 30s and check the card shows 2 min 30 sec (150 s); Edit with blank blocks keeps old duration.
