# hiberna R8/ProGuard keep rules.
#
# Task 3: RealShizukuPlatform.exec reflects into Shizuku's internal, restricted
# `Shizuku.newProcess(String[], String[], String)` method to invoke privileged
# shell commands. R8 must not rename, inline, or strip it in a release build —
# a build that silently loses this method would look like it works (compiles,
# installs) while every privileged command call fails at runtime.
-keep class rikka.shizuku.Shizuku {
    private static rikka.shizuku.ShizukuRemoteProcess newProcess(java.lang.String[], java.lang.String[], java.lang.String);
}
-keep class rikka.shizuku.ShizukuRemoteProcess { *; }
