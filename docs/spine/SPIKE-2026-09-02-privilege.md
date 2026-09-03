# Privilege spike - verdict

- **Date:** 2026-09-03
- **Device:** Android emulator, AVD Medium_Phone_API_36.1
- **Android:** release 16, SDK 36
- **Privilege path:** direct `adb shell` (equivalent to what Shizuku executes as the shell user)
- **Verdict: GO** - all three levers work.

Shizuku itself was NOT exercised here; that is proven separately by the
instrumented smoke test in Task 14, on the physical Pixel. What this spike
settles is that the three commands exist, succeed, and what their output looks
like - which is what every parser in Task 4 is written against.

## Lever 1 - appops

```
cmd appops set <pkg> RUN_ANY_IN_BACKGROUND ignore|allow   # write, exit 0
cmd appops get <pkg> RUN_ANY_IN_BACKGROUND                # -> "RUN_ANY_IN_BACKGROUND: ignore"
cmd appops query-op RUN_ANY_IN_BACKGROUND ignore          # read
```

Populated output is one bare package name per line:

```
com.android.vending
com.jinatra.finatra
```

**Empty has two different representations**, and both occur:

| Situation | Output |
|---|---|
| Nothing ever set | `No operations.` |
| Set then reverted to `allow` | empty string, zero bytes |

This is the spike's most important finding. A parser that keeps any line
containing a dot turns `No operations.` into a package named
`"No operations."`.

## Lever 2 - deviceidle

```
dumpsys deviceidle whitelist +<pkg>    # write -> "Added: <pkg>", exit 0
dumpsys deviceidle whitelist -<pkg>    # remove
dumpsys deviceidle whitelist           # read
```

Read output is `source,package,uid` per line. Sources observed:
`system-excidle` and `user`.

```
system-excidle,com.android.providers.calendar,10098
user,com.jinatra.finatra,10220
```

Never empty on a real device - every build ships system packages whitelisted.
An empty result here means the command failed silently.

## Lever 3 - netpolicy

```
cmd netpolicy add    restrict-background-blacklist <uid>   # write, exit 0
cmd netpolicy remove restrict-background-blacklist <uid>
cmd netpolicy list   restrict-background-blacklist         # read
```

**The uids are inline on the header line, space-separated, with a trailing
space** - not one per line:

```
Restrict background blacklisted UIDs: 10153 10220 
```

Empty:

```
Restrict background blacklisted UIDs: none
```

A line-oriented parser returns an empty set here every time, silently.

## uid resolution

`dumpsys package <pkg> | grep userId=` proved unreliable. Use:

```
pm list packages -U <pkg>     # -> "package:com.jinatra.finatra uid:10220"
```

Passing a bad uid is rejected loudly rather than ignored, which is good:

```
java.lang.IllegalArgumentException: cannot apply policy to UID 197611
```

## Defects this spike caught before any app code existed

1. `parseAppOpsRestricted` would parse `No operations.` as a package name.
2. `parseNetPolicyBlacklist` would always return empty - it reads line by
   line, but the uids share the header line.
3. The plan's own capture script used `UID=$(...)`. `UID` is readonly in
   bash, so it silently kept the host uid (197611) and the netpolicy write
   failed against a nonsense value.
4. uid resolution via `dumpsys package` returned empty; `pm list packages -U`
   is the reliable form.

## Fixtures captured

`app/src/test/resources/fixtures/` - populated and empty cases for both
formats with a genuine empty representation:

- `appops_query_restricted.txt`, `appops_query_empty.txt` (zero bytes)
- `deviceidle_whitelist.txt`
- `netpolicy_blacklist.txt`, `netpolicy_blacklist_empty.txt`

Note the third appops empty form - the literal `No operations.` - is asserted
in Task 4's tests directly rather than as a file, since a one-line fixture file
adds nothing.

## Not proven here

- Shizuku's own binder path (Task 14, physical device)
- Android 17 behaviour - this ran on API 36. The Pixel is on Android 17, so
  re-run `appops query-op` there before release and diff the format.
- Reboot persistence - the emulator was not rebooted.
