# hiberna currently needs no custom R8/ProGuard keep rules.
#
# Note for later: Task 3 adds reflection into Shizuku's internal
# `newProcess` method (used to invoke shell commands via Shizuku's
# privileged process). If that reflective call breaks under R8
# minification/obfuscation in a release build, a `-keep` rule for the
# relevant Shizuku-side class/method (or `-keepattributes`/`-dontobfuscate`
# scoped to it) will need to be added here.
