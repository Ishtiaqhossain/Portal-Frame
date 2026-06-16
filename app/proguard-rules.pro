# R8 / ProGuard rules for the release build.
#
# The app uses no reflection, dynamic class loading, or serialization, and every
# entry point (Activities, the ConfigReceiver/BootReceiver, FrameDreamService,
# ScreensaverGuardService) is declared in AndroidManifest.xml, so AAPT generates
# keep rules for them automatically. The vendored ZXing core and the Compose/
# AndroidX libraries are called directly and ship their own consumer rules, so no
# extra -keep rules are needed here.

# Keep source-file + line-number info so release crash traces stay readable
# (the APK is sideloaded, so there's no Play Console de-obfuscation mapping flow).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
