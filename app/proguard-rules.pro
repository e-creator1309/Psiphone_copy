# ── Native JNI — must keep ─────────────────────────────────────────────────
-keep class ca.psiphon.Tun2SocksJniLoader { native <methods>; }
-keep class com.psiphon3.VpnManager {
    public static void logTun2Socks(java.lang.String, java.lang.String, java.lang.String);
}

# ── Aggressive R8 / ProGuard optimization ──────────────────────────────────
-optimizationpasses 7
-allowaccessmodification
-overloadaggressively
-repackageclasses ''
-optimizations !code/simplification/arithmetic,!field/*,!class/merging/*

# ── Strip unused code & resources ──────────────────────────────────────────
-dontwarn **
-ignorewarnings

# ── Keep Android entry points ───────────────────────────────────────────────
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.Application

# ── Keep Parcelable ────────────────────────────────────────────────────────
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

# ── Keep Serializable ──────────────────────────────────────────────────────
-keepclassmembers class * implements java.io.Serializable {
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ── RxJava ────────────────────────────────────────────────────────────────
-keepclassmembers class rx.** { *; }
-keep class io.reactivex.** { *; }
-dontwarn io.reactivex.**

# ── Jackson ───────────────────────────────────────────────────────────────
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**

# ── SnakeYAML ─────────────────────────────────────────────────────────────
-keep class org.yaml.snakeyaml.** { *; }

# ── Remove logging in release ─────────────────────────────────────────────
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
