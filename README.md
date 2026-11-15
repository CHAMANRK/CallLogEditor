# CHAMAN Call Log Editor

Native Kotlin Android project for viewing and managing the device owner's call history where Android grants the required access.

## Project location in Termux

Use the existing project folder: `~/test/CallLogEditor`.

## One-time permanent signing setup (important for in-place updates)

Android only accepts an APK update when its application ID and signing certificate match the installed app. Do not lose the keystore or passwords: future updates must use this exact key.

1. Create a private keystore on your own device (do not commit it to Git):

```bash
mkdir -p ~/.android
keytool -genkeypair -v -keystore ~/.android/calllogeditor-release.jks \
  -alias calllogeditor -keyalg RSA -keysize 2048 -validity 10000
```

2. Add these GitHub repository Actions secrets (replace `OWNER/REPO` with your repository):

- `ANDROID_KEYSTORE_BASE64` — base64 contents of the `.jks` file
- `ANDROID_KEYSTORE_PASSWORD` — keystore password
- `ANDROID_KEY_ALIAS` — `calllogeditor`
- `ANDROID_KEY_PASSWORD` — key password

Example Termux commands (the password prompts are entered when you create the key):

```bash
base64 -w 0 ~/.android/calllogeditor-release.jks | gh secret set ANDROID_KEYSTORE_BASE64 -R CHAMANRK/CallLogEditor
gh secret set ANDROID_KEYSTORE_PASSWORD -R CHAMANRK/CallLogEditor
gh secret set ANDROID_KEY_ALIAS -b 'calllogeditor' -R CHAMANRK/CallLogEditor
gh secret set ANDROID_KEY_PASSWORD -R CHAMANRK/CallLogEditor
```

For the two password commands, `gh secret set` reads the value securely from standard input; type/paste the password and press Ctrl-D. Never put passwords in source code or commit the keystore.

3. Push changes. GitHub Actions builds a signed release APK. Every workflow run increments `versionCode`, allowing Android to recognize later APKs as upgrades.

## Updating the existing project in Termux

Extract the supplied update ZIP into `~/test` so its `CallLogEditor/` folder merges into the existing project, then:

```bash
cd ~/test/CallLogEditor
git add .
git commit -m "Add branded icon and permanent release signing"
git push origin main
```

## Important one-time migration note

The earlier CI workflow built debug APKs using GitHub runner-generated debug signing keys. A newly permanent-signed APK may not install over one of those earlier builds because the signatures differ. If Android reports `App not installed` or `signatures do not match`, uninstall the old test APK **once**, install the first permanently signed APK, and keep the keystore safe. After that, future builds signed with this same key can update in place without uninstalling.

## Build details

- Application ID: `com.chaman.calllogeditor` (keep unchanged)
- Kotlin / Android Gradle Plugin / JDK 17
- `versionCode` comes from GitHub Actions run number
- `versionName` is `0.2.<run number>`
- The workflow requires the four signing secrets above.

## Android call-log restrictions

`READ_CALL_LOG` and `WRITE_CALL_LOG` are restricted Android permissions. Runtime prompts do not guarantee that Android or the device manufacturer will grant write/insert/delete access. The app must report provider/permission failures rather than bypass platform security.
