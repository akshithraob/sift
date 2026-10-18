# Leakproof ProGuard / R8 Rules
# Privacy & Security: Strip all Log calls in release builds
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}

# Keep SQLCipher classes
-keep class net.zetetic.database.** { *; }
-dontwarn net.zetetic.database.**

# Keep Biometric classes
-keep class androidx.biometric.** { *; }

# PdfBox-Android optional JPEG2000 decoder (not bundled)
-dontwarn com.gemalto.jp2.**
