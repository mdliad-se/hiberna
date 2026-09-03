<div align="center">
  <img src="assets/icon-512.png" width="96" alt="hiberna">
  <h1>hiberna</h1>
  <p><strong>Stop apps running in the background. All of them, from one screen.</strong></p>
</div>

---

Android already lets you stop an app from working in the background. The controls
are just buried three taps deep in Settings, per app, in two different places -
so nobody does it for more than the two or three apps that annoy them most.

hiberna puts every installed app in one list with those same controls attached,
adds multi-select so you can change a hundred apps at once, and warns you before
you restrict something that will break if you do.

It needs no computer, no root, and no account.

## Requirements

- Android 11 or newer
- [Shizuku](https://github.com/RikkaApps/Shizuku), started via Wireless debugging

Android 11 is a hard floor rather than a preference: starting Shizuku without a
computer relies on Wireless debugging, which older versions do not have. On
Android 10 and below you would need to plug into a PC after every reboot, and
this app would be making a promise it cannot keep.

## What it changes

hiberna is not a background service and does not stay resident. It reads and
writes the same three system settings you could change by hand:

| Control | What it does |
|---|---|
| Background activity | Whether the app may run when you are not using it. Restricted, Optimized, or Unrestricted - the same three states Android's own battery screen shows |
| Battery whitelist | Whether the app is exempt from Doze and app-standby entirely |
| Background data | Whether the app may use mobile data while in the background |

Nothing is hidden: every change hiberna makes is visible afterwards in Settings,
and you can undo any of it there without hiberna installed.

## Setup

1. Install Shizuku.
2. On your phone, enable **Developer options**, then turn on **Wireless debugging**.
3. In Wireless debugging, choose **Pair device with pairing code**.
4. Give that code to Shizuku and start it.
5. Open hiberna and grant it access when asked.

Shizuku stops when the phone reboots, so step 4 is repeated after a restart.
That is a Shizuku characteristic, not something hiberna can work around.

## Doing it by hand instead

You do not need this app. For each app you care about:

1. Settings, then Apps, then the app.
2. **App battery usage**, then set Background usage to Restricted.
3. **Mobile data**, then turn off Background data.

That is roughly six taps per app. hiberna exists because doing it for an entire
phone is the same six taps a hundred times over, and because nothing in that
flow warns you when you are about to break your own alarm clock.

## Building from source

Requires JDK 17 or newer and the Android SDK with API 37.

```bash
git clone https://github.com/mdliad-se/hiberna.git
cd hiberna
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`.

To run the tests:

```bash
./gradlew testDebugUnitTest
```

Instrumented tests need a connected device with Shizuku running:

```bash
./gradlew connectedDebugAndroidTest
```

## Licence

[GPL-3.0-or-later](LICENSE). Copyright (C) 2026 Jinatra Ltd.

hiberna is a privileged system tool, so copyleft is deliberate: any fork must
also be free software with its source available. Nobody gets to take this, bolt
on telemetry, and ship a closed lookalike that users trust with shell access.

Trademarks are a separate matter and are **not** licensed - fork it under your
own name. See [NOTICE.md](NOTICE.md).

## Notices

Android is a trademark of Google LLC. hiberna is not affiliated with, endorsed
by, or connected to Google.
