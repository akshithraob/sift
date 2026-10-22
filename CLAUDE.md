# Leakproof — Project Context for Claude

## What this app is
A native Android subscription manager for Indian users. It finds the user's recurring payments (UPI Autopay mandates, e-NACH/ACH debits, standing instructions, card subscriptions) **without manual entry**, then helps them track and cancel them.

The initial project was generated in Google AI Studio ("Build an Android app") and exported here. Work in this repo is refinement: real parsers, security hardening, tests.

## Key decisions already made (don't revisit without asking)
- **No licences / no Account Aggregator.** The founder has no RBI/SEBI licence, so AA (FIU) bank-data access is out. Users bring their own data instead.
- **Data inputs:**
  1. Screenshots of the UPI app's Autopay/Mandates list (GPay, PhonePe, Paytm, bank apps). Under NPCI circular OC-223 (Oct 2025), UPI apps show all of a user's active mandates, including ones created in other apps, so one or two screenshots capture everything, even mandates that haven't debited yet.
  2. Uploaded bank statements (PDF, often password-protected, or CSV) → recurring-debit detection.
  3. Manual add as a fallback.
- **Rejected:** SMS reading (Play Store restricts READ_SMS), net-banking credential scraping (never), Gmail API (restricted scope + CASA audit). Email forwarding to a server is a possible V2, but it breaks the on-device model.
- **Android first** (about 95% of the Indian market). Native Kotlin + Jetpack Compose. iOS later, possibly via Kotlin Multiplatform.
- **Cancellation:** the app can't revoke mandates itself (that needs TPAP status, a future V3). It deep-links to the source UPI app or https://upihelp.npci.org.in and shows step-by-step guides from a bundled `merchants.json`.

## Security model (the core selling point; treat as hard requirements)
- **All processing on-device.** The app must NOT declare the INTERNET permission. No backend, no cloud APIs, no analytics, crash-reporting or ad SDKs.
- OCR: Google ML Kit Text Recognition, **bundled** model.
- PDFs: PdfBox-Android (`com.tom-roush:pdfbox-android`). The PDF password is used in memory only: never stored, never logged.
- Picked files are copied to app-private cache, parsed, then **deleted in every code path** (including errors).
- Room + SQLCipher (`net.zetetic:sqlcipher-android`). A random DB passphrase is wrapped with an Android Keystore AES key; only the wrapped form is stored.
- Store only: merchant, amount, frequency, next date, rail (UPI/NACH/SI/Card/Other), source, last 4 account digits, status, user-confirmed flag. **Never** full account numbers, UPI IDs, raw OCR/statement text, or non-subscription transactions.
- `FLAG_SECURE` on all activities. `android:allowBackup="false"` plus data-extraction rules that exclude the DB. Biometric/PIN app lock (androidx.biometric), on by default.
- R8 strips `Log` calls in release builds. No sensitive data in logs or exceptions.
- "Delete all my data" wipes the DB and the Keystore key.
- Never claim "100% secure" in UI or marketing copy. Tagline: "Plug the leaks. Keep your data." (the claim itself, that data never leaves the phone, stays true and may be stated plainly).

## Architecture
- MVVM, ViewModel + StateFlow, Hilt, Navigation Compose, coroutines, WorkManager (local renewal reminders, 2 days before).
- minSdk 26, targetSdk latest. Indian rupee formatting (₹1,23,456). Light and dark themes.
- Parsing logic lives in a pure-Kotlin `parser/` package with **no Android dependencies**, behind interfaces:
  - `MandateScreenshotParser`: OCR text blocks (with positions) → mandates. Groups lines into cards/rows; handles "up to ₹X", "Monthly", "As presented", dates like "17 Oct 2026" and "17/10/2026"; merges duplicates across screenshots.
  - `StatementParser`: PDF/CSV → date, narration, debit, credit.
  - `RecurringDetector`: normalise merchants (strip refs, UPI IDs, "UPI/", "ACH D-"); flag 2+ debits within ±10% amount at weekly/monthly/quarterly/yearly intervals; recognise "UPI-MANDATE", "AUTOPAY", "ACH D-", "NACH", "SI-"; output High/Medium/Low confidence. Raw transactions are discarded after detection.
- UPI app package names: Google Pay `com.google.android.apps.nbu.paisa.user`, PhonePe `com.phonepe.app`, Paytm `net.one97.paytm`.

## Screens
Onboarding (privacy explainer + app-lock setup) → Import (screenshots / statement / manual) → Review (Confirm / Not a subscription / Edit, with confidence) → Dashboard (monthly/yearly totals, next-7-days renewals, grouped by rail, search/filter) → Detail (edit, status, how to cancel) → Settings (lock, delete all data, privacy).

## Working rules
- Every parser change needs unit tests with realistic sample text (SBI, HDFC, ICICI, Axis narrations; GPay/PhonePe/Paytm OCR layouts). Use only synthetic or redacted samples; never commit real user data.
- After any change touching files, storage, permissions or dependencies, re-check the security model above.
- Before release: an independent security audit (ideally a CERT-In empanelled auditor), a DPDP Act-compliant consent screen and privacy policy, and an incident response plan (CERT-In 6-hour reporting).

## Current status / next steps
1. Confirm the AI Studio export builds; replace placeholder parsers.
2. Implement and test `MandateScreenshotParser` against real (redacted) screenshots.
3. Implement and test `StatementParser` + `RecurringDetector`.
4. Run the security checklist; fix gaps.
5. Internal testing track on Play Console.
