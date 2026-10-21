# Leakproof

**Find the payments quietly draining your account.** Leakproof is a native Android app for India that finds your recurring payments (UPI Autopay mandates, e-NACH/ACH debits, standing instructions, SIPs, EMIs and card subscriptions) without manual entry, then helps you track, question and cancel them.

**Your data never leaves your phone.** Leakproof does not declare the `INTERNET` permission, and has no backend, analytics, crash-reporting or ad SDKs. The build fails if that ever changes.

## What it does

- **Finds recurring payments** from Autopay screenshots (Google Pay, PhonePe, Paytm, bank apps), bank statements (PDF, including password-protected, or CSV), or a manual entry.
- **Leaks found**: flags overlapping services (for example two music apps), risky mandates (high or open-ended limits), and unused mandates that a new statement shows no debit for.
- **Subscriptions and debt together**: EMIs and loans (with outstanding balance, interest and a debt-free plan), SIPs and insurance sit beside subscriptions, with a monthly split.
- **Health score**, a **12-month forecast**, a payment **calendar**, a monthly **budget cap** and a **free-trial tracker** with reminders.
- **Re-import diff**: importing again updates what changed instead of duplicating.
- **Annual-renewal radar**: yearly payments coming up in the next 90 days, with a heads-up notification a month and a week before.
- **Year in review**: what everything adds up to, your priciest subscription, what you saved by cancelling and your busiest month. The shareable summary has totals only.
- **Home-screen widget**: this month's total and the next renewal. It stays blank until you turn on "Show amounts on the widget" in Settings, because a home screen is visible to anyone holding the phone.
- **Share to Leakproof**: share screenshots, a statement PDF or a CSV from any app (Files, WhatsApp, a browser) straight into the import. The app lock still comes first.
- **Stronger overlap detection**: bundles that already include other services (Apple One, Prime, YouTube Premium), several plans of one service, near-duplicate names, and more categories (gaming, VPN, design, wellness).
- **Cancel guides**: Leakproof cannot revoke mandates itself, so it deep-links to your UPI app or https://upihelp.npci.org.in and shows step-by-step guides from the bundled `merchants.json`.
- **Trust Center**: shows, from your own phone, that this copy has no internet permission, no cloud backup, and which certificate signed it, plus exactly what is and is not stored.
- Light, dark and system themes; encrypted backup and restore; DPDP Act consent and a built-in privacy policy.

## How it finds subscriptions

1. **Autopay screenshots**: text is read on-device with ML Kit (bundled model).
2. **Bank statements**: recurring debits are detected on-device; the PDF password is used in memory only.
3. **Manual add**: as a fallback.

Picked files are copied to app-private cache, parsed, then deleted in every code path.

## Security model

- Room database encrypted with SQLCipher; the passphrase is wrapped by an Android Keystore AES key. If encryption cannot be set up, the app fails closed rather than storing plain data.
- Only minimal fields are stored: merchant, amount, frequency, next date, payment type, source, last 4 account digits, status and your notes. Never full account numbers, UPI IDs, or raw OCR/statement text.
- App lock via biometrics or device credential, re-locking after 30 seconds in the background.
- `FLAG_SECURE` in release builds, `allowBackup="false"`, DB excluded from data extraction.
- R8 enabled in release builds; `Log` calls are stripped.
- "Delete all my data" wipes the database and the Keystore key and restarts the app.
- Encrypted backups use AES-256-GCM with a PBKDF2 key derived from your password.

This project has not had an independent security audit.

## Tech stack

Kotlin, Jetpack Compose (Material 3), Navigation Compose, MVVM (ViewModel + StateFlow), Room + SQLCipher, ML Kit Text Recognition, PdfBox-Android, androidx.biometric, WorkManager. Inter typeface (SIL OFL, see `docs/third-party/Inter-OFL.txt`). minSdk 26, compileSdk 36.

Parsing and money logic are pure Kotlin with no Android dependencies, under `app/src/main/java/com/leakproof/app/` in `parser/` and `insights/`, and are unit tested with synthetic samples.

## Download

Prebuilt APKs are on the [Releases page](https://github.com/akshithraob/subtrack/releases). They are signed with a presentation key for demos, not distributed through the Play Store yet. Check the file against the published SHA-256 before installing.

## Building

Requirements: JDK 21 and an Android SDK with platform 36.

```bash
# Unit tests
./gradlew testDebugUnitTest

# Debug APK
./gradlew assembleDebug
```

Debug builds use the standard Android debug keystore. Release builds read the signing key from `KEYSTORE_PATH`, `KEY_ALIAS`, `STORE_PASSWORD` and `KEY_PASSWORD`; without them the release build is unsigned. A `demo` build type (applicationId suffix `.demo`) allows screenshots so the app can be shown.

## Releasing

1. Create an upload key once and keep it **out of git and backed up** (losing it blocks updates unless you use Play App Signing, which is recommended):

   ```bash
   keytool -genkeypair -v -keystore my-upload-key.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000
   ```

2. Provide the signing details through the environment, never in files in the repo:

   ```bash
   export KEYSTORE_PATH=/secure/path/my-upload-key.jks
   export KEY_ALIAS=upload
   export STORE_PASSWORD=...
   export KEY_PASSWORD=...
   ./gradlew bundleRelease
   ```

3. The build runs `verifyNoInternetPermission` and fails if any library puts `android.permission.INTERNET` into the merged manifest. ML Kit's data-transport library does, so the app's manifest removes it explicitly. `verifyLegalPlaceholders` fails a release build while the privacy contact email is still a placeholder.

4. **Scan a real Autopay screenshot on the release APK itself** (install it, import from the photo picker, confirm the mandates appear). Shrunk builds can fail where debug builds work; `verifyMlKitKeepRules` guards the ML Kit rules but only a real scan proves it.

5. Before uploading: `./gradlew testDebugUnitTest lintRelease` must pass, and `./gradlew connectedDebugAndroidTest` (with a phone attached) checks the package id, no network permission, backups off, only the launcher activity exported, and database migrations.

## Security and privacy documents

- [`docs/incident-response-plan.md`](docs/incident-response-plan.md): who does what, and the 6-hour CERT-In and DPDP reporting steps
- [`docs/privacy-policy.md`](docs/privacy-policy.md): generated by `./gradlew exportPrivacyPolicy` for hosting at the Play Console URL

## Contributing

Parser changes need unit tests with realistic but synthetic or redacted samples; never commit real user data. See `CLAUDE.md` for the project context and security requirements.
