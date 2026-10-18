# Leakproof

A native Android app that finds your recurring payments in India (UPI Autopay mandates, e-NACH/ACH debits, standing instructions, card subscriptions) without manual entry, and helps you track and cancel them.

**Your data never leaves your phone.** Leakproof does not declare the `INTERNET` permission, and has no backend, analytics, crash-reporting or ad SDKs.

## How it finds subscriptions

1. **Autopay screenshots**: screenshots of the Autopay/Mandates list in Google Pay, PhonePe, Paytm or your bank app. Text is read on-device with ML Kit (bundled model).
2. **Bank statements**: PDF (including password-protected) or CSV. Recurring debits are detected on-device; the PDF password is used in memory only.
3. **Manual add**: as a fallback.

Picked files are copied to app-private cache, parsed, then deleted in every code path.

## Cancelling

Leakproof cannot revoke mandates itself. It deep-links to your UPI app or https://upihelp.npci.org.in and shows step-by-step guides from the bundled `merchants.json`.

## Security model

- Room database encrypted with SQLCipher; the passphrase is wrapped by an Android Keystore AES key.
- Only minimal fields are stored: merchant, amount, frequency, next date, rail, source, last 4 account digits, status. Never full account numbers, UPI IDs, or raw OCR/statement text.
- App lock via biometrics or device credential, re-locking after 30 seconds in the background.
- `FLAG_SECURE` in release builds, `allowBackup="false"`, DB excluded from data extraction.
- R8 enabled in release builds; `Log` calls are stripped.
- "Delete all my data" wipes the database and the Keystore key.

This project has not had an independent security audit.

## Tech stack

Kotlin, Jetpack Compose, MVVM (ViewModel + StateFlow), Room + SQLCipher, ML Kit Text Recognition, PdfBox-Android, androidx.biometric, WorkManager. minSdk 26, compileSdk 36.

The pure-Kotlin parsers live in `app/src/main/java/com/example/parser/` and have no Android dependencies.

## Building

Requirements: JDK 21 and an Android SDK with platform 36.

```bash
# Unit tests
./gradlew testDebugUnitTest

# Debug APK
./gradlew assembleDebug
```

Debug builds are signed with a local, git-ignored `debug.keystore` in the repo root. Create one with:

```bash
keytool -genkeypair -keystore debug.keystore -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
```

Debug builds also seed demo subscriptions and show "Test Sample" import buttons; release builds do not.

Release builds read the signing key from `KEYSTORE_PATH`, `STORE_PASSWORD` and `KEY_PASSWORD`.

## Contributing

Parser changes need unit tests with realistic but synthetic or redacted samples; never commit real user data. See `CLAUDE.md` for the project context and security requirements.
