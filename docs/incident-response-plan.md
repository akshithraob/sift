# Sift incident response plan

Version 1 · 18 October 2026 · Owner: Akshith Rao B

Sift keeps all user data on the user's phone. There is no server, no account and no network permission, so the usual incident (a breached database) cannot happen here. The realistic incidents are about **the app itself**: a bad update, a compromised signing key, a vulnerable library, a bug that exposes data on the device, or someone impersonating the app. This plan covers those, and meets the reporting duties below.

## Reporting duties this plan satisfies

| Duty | Deadline | To whom |
|---|---|---|
| CERT-In Directions of 28 April 2022 (IT Act s.70B): report listed cyber incidents | **Within 6 hours** of noticing or being told | CERT-In: incident@cert-in.org.in (confirm current phone and form details at cert-in.org.in) |
| DPDP Act 2023: personal data breach | Tell affected users **without delay**, and the Data Protection Board with details within the period set in the DPDP Rules in force | Affected users (in-app notice, store listing, email if known) and the Board |
| Google Play policy: security issues and data safety accuracy | As soon as a user-impacting fix ships | Play Console (halt rollout, publish fix, update data-safety form if it changed) |

When unsure whether something is reportable, treat it as reportable. Late reporting is the failure to avoid; over-reporting is not.

## Roles

One person owns this today: the publisher. Fill these in before launch and keep a copy offline.

- Incident lead: Akshith Rao B
- Backup contact who can act if the lead is unavailable: _to be named_
- Where credentials and the upload keystore backup are kept: _to be recorded (offline, not in the repository)_

## What counts as an incident

| Scenario | Examples | Default severity |
|---|---|---|
| Release integrity | Upload key or Play account compromised, unexpected release appears, tampered APK/AAB | Critical |
| Data exposure by the app | A bug writes subscription data to logs, backups, notifications or exports, re-enables a network permission, or weakens the database encryption | Critical |
| Vulnerable dependency | A published vulnerability in SQLCipher, PdfBox-Android, ML Kit, AndroidX or another library the app ships | High, Critical if exploitable through a screenshot or statement the user opens |
| Impersonation | A fake "Sift" app on Play or elsewhere, a phishing page using the name | High |
| Privacy request failure | A user cannot delete data or withdraw consent, or a grievance goes unanswered past 30 days | Medium |

Critical and High incidents start the clock at the moment someone first notices or is told, not when it is confirmed.

## Detection

- Reports to the grievance address in the privacy policy and in the Play listing
- Play Console: security notices, pre-launch report findings, vitals, policy emails
- Dependency advisories: run `./gradlew dependencyUpdates` or check release notes monthly, and subscribe to advisories for SQLCipher, PdfBox-Android, ML Kit and AndroidX
- The build itself: `verifyNoInternetPermission`, `verifyLegalPlaceholders`, lint and the on-device safety tests fail loudly if a promise is broken
- Security researcher reports: acknowledge within 48 hours

## Response steps

**0 to 1 hour: contain**
1. Write down the time you first learned of it, who reported it and what they saw. Keep screenshots and messages as evidence.
2. Classify it using the table above.
3. For anything that ships to users: **halt the staged rollout** and consider unpublishing the affected version in Play Console.
4. If a credential may be compromised: change the Google account password and sign-out everywhere, review Play Console access, and revoke access for anyone unexpected. If the upload key is suspect, request an upload key reset in Play Console (Play App Signing keeps the real signing key safe).

**By 6 hours: report**
5. For a CERT-In reportable incident, email incident@cert-in.org.in with: your name and contact, what happened, when it was noticed, affected app and versions, and what has been done so far. Send what you know; add detail later.
6. Note the report time and any reference number in the incident record.

**By 24 hours: fix and verify**
7. Reproduce the problem on a test phone. Fix it in the smallest possible change and add a test that would have caught it.
8. Run the full checks: `./gradlew testDebugUnitTest lintRelease connectedDebugAndroidTest`, then build the release bundle and confirm the merged manifest and dependencies are as expected.
9. Publish the fix as a new version with a higher `versionCode`, and ask Play to prioritise the update if the issue is serious.

**By 72 hours: tell people**
10. If users' data was or could have been exposed, tell them without delay: a notice on the Play listing, and, where the app can show it, an in-app message on the next launch explaining what happened, what data was involved, what to do, and who to contact.
11. File the Data Protection Board intimation and detailed report as required by the DPDP Rules in force at the time.
12. Update the privacy policy, bump `policy_version` and the effective date if what the app does with data changed, so users are asked for consent again.

## After the incident

Within a week, write a short review: timeline, root cause, what detected it, what was slow, and what check or test will stop it recurring. Keep it with the incident record. Add the missing guard to the build if one was missing.

## Evidence and records

Keep for at least 180 days: the incident record, emails to and from CERT-In and Google, release and signing-key change history, and the review. The app itself keeps no logs and no user data on any server, so there is nothing server-side to preserve or hand over.

## Pre-launch checklist

- [ ] Upload key created, backed up offline, and Play App Signing enabled
- [ ] Two-step verification on the Google account that owns the Play Console and on GitHub
- [ ] A working grievance email in `res/values/legal.xml`, checked weekly
- [ ] Backup contact named above
- [ ] CERT-In contact details re-confirmed at cert-in.org.in
- [ ] Dependency check scheduled monthly
- [ ] Staged rollout (for example 5 percent first) used for every release
