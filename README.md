# Call Log Editor (starter)

A native Kotlin Android starter project for viewing call history and attempting permitted edits/deletions on the device owner's call log.

## Build with GitHub Actions
1. Create a GitHub repository.
2. Upload the contents of this project to the repository root.
3. Push to `main` (or run **Actions → Android APK Build → Run workflow**).
4. Download the `CallLogEditor-debug` artifact from the workflow run.

## Important Android limitation
This project requests `READ_CALL_LOG` and `WRITE_CALL_LOG`, but Android treats call-log permissions as restricted. A normal runtime permission dialog does not guarantee that an installer/device will allow them. Android/OEM policy and distribution rules may prevent access. The app reports access failures instead of bypassing platform security.

This starter is for managing records on the device owner's own phone. It is not a complete default dialer and is not guaranteed to be able to update existing system call-log rows on every device. Verify behavior on the target phone before relying on it.

## Build requirements
- Android Gradle Plugin 8.7.3
- Kotlin 2.0.21
- JDK 17
- Android SDK 35
