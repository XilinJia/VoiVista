# https://developer.android.com/build/shrink-code

## Helps debug release versions
-dontobfuscate

## Rules for VistaGuide
-keep class ac.mdiq.vista.extractor.timeago.patterns.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-keep class java.beans.**
-dontwarn org.mozilla.javascript.tools.**
-dontwarn java.beans.**

## Rules for ExoPlayer
#-keep class com.google.android.exoplayer2.** { *; }

## Rules for Icepick. Copy pasted from https://github.com/frankiesardo/icepick
-dontwarn icepick.**
-keep class icepick.** { *; }
-keep class **$$Icepick { *; }
-keepclasseswithmembernames class * {
    @icepick.* <fields>;
}
-keepnames class * { @icepick.State *;}

## Rules for OkHttp. Copy pasted from https://github.com/square/okhttp
-dontwarn okhttp3.**
-dontwarn okio.**

## See https://github.com/XilinJia/VoiVista/pull/1441
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
}

## For some reason NotificationModeConfigFragment wasn't kept (only referenced in a preference xml)
-keep class ac.mdiq.vista.settings.notifications.** { *; }
