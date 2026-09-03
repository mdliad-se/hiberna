# hiberna R8/ProGuard keep rules.
#
# RealShizukuPlatform.exec reflects into Shizuku's private, restricted
# `Shizuku.newProcess(String[], String[], String)` method to invoke privileged
# shell commands. R8 must not rename, inline, or strip it in a release build -
# a build that silently loses this method would look like it works (compiles,
# installs, launches) while every privileged command fails at runtime.
#
# The rule is deliberately a whole-package wildcard rather than an exact
# signature. R8 does NOT warn when a -keep rule matches nothing, so a pinned
# signature that drifts by one parameter or one rename in a Shizuku bump
# becomes a silent no-op and the build stays green. rikka.shizuku is 15
# classes; keeping all of them costs nothing measurable and cannot drift.
-keep class rikka.shizuku.** { *; }

# Which members R8 actually seeded. :app:verifyShizukuSeeds parses this file
# and fails the build if `newProcess` is absent, which is the only way to
# notice that the rule above stopped matching.
-printseeds build/outputs/mapping/release/seeds.txt
