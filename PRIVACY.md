# Privacy policy

**hiberna collects nothing, sends nothing, and has no network access.**

Last updated: 3 September 2026. Jinatra Ltd., Dhaka, Bangladesh.

## No network permission

hiberna does not declare the `INTERNET` permission. This is not a promise
about intent - it is a property of the app you can verify yourself:

```bash
aapt dump permissions hiberna.apk
```

There is no `android.permission.INTERNET` line. Without it Android will not
let the app open a network connection, so no data can leave your device even if
a future bug tried to send it.

There are no analytics, no crash reporting, no advertising identifiers, no
accounts, and no telemetry of any kind.

## What hiberna reads

| Data | Why | Where it goes |
|---|---|---|
| The list of apps installed on your device, with their names, icons and user IDs | To show you the list you are editing | Never leaves the device |
| Each app's current background settings | To show the current state and change it | Never leaves the device |
| Which apps hold system roles - SMS, dialer, alarms, authenticators | To warn you before restricting something that will break | Never leaves the device |

The app list is read through the `QUERY_ALL_PACKAGES` permission. hiberna
needs it because its entire purpose is showing you every installed app in one
place; an app list filtered by Android's default visibility rules would be
missing most of what you want to change.

## What hiberna stores

On your device only, in the app's private storage:

- Your saved presets
- Which apps you have chosen to override the safety warning for
- Your display preferences, such as whether system apps are shown

No policy state is stored keyed to anything identifying you. Uninstalling
hiberna deletes all of it.

## Shizuku

hiberna cannot change system settings on its own. It asks
[Shizuku](https://github.com/RikkaApps/Shizuku), a separate app you install and
control, to run specific commands. hiberna sends Shizuku only the commands
needed for the change you asked for, and Shizuku's own permission prompt governs
whether hiberna may talk to it at all. Revoking that permission in Shizuku stops
hiberna from changing anything.

Shizuku is not operated by Jinatra. Its own terms and behaviour are its
maintainers' to describe.

## Changes to the settings on your phone

hiberna writes to Android's own per-app background settings. Those changes are
made to your device by you, are visible afterwards in Settings, and persist
whether or not hiberna stays installed. Uninstalling hiberna does not revert
them - you can change them back in Settings, or reinstall hiberna and change
them there.

## Children

hiberna is a system utility with no content, no social features, and no data
collection. It is not directed at children, and it gathers nothing from anyone
regardless of age.

## Contact

Questions about this policy: open an issue at
<https://github.com/mdliad-se/hiberna/issues>.
