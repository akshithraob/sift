# Sift ProGuard / R8 Rules
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

# ML Kit text recognition: its components are wired up through reflection and manifest metadata.
# Without these, the shrunk build fails inside ML Kit on the first scan (debug builds are unaffected).
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-keep class com.google.android.odml.** { *; }
-keep class com.google.firebase.components.** { *; }
-keep class * implements com.google.firebase.components.ComponentRegistrar { *; }
-keep @com.google.android.gms.common.annotation.KeepName class * { *; }
-keepclassmembers class * {
    @com.google.android.gms.common.annotation.KeepName *;
}
-dontwarn com.google.mlkit.**
