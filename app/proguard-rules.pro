# --- ANDROID DEFAULT RULES ---
-dontwarn android.support.**
-keep class android.support.** { *; }
-keep interface android.support.** { *; }
-keep class androidx.** { *; }
-keep interface androidx.** { *; }

# --- JLIBTORRENT (மிக முக்கியம்) ---
# இது இல்லை என்றால் Torrent Engine வேலை செய்யாது (Native Crash ஆகும்)
-keep class com.frostwire.jlibtorrent.** { *; }
-keep class com.frostwire.jlibtorrent.swig.** { *; }
-dontwarn com.frostwire.jlibtorrent.**

# --- RETROFIT & GSON (API Calls) ---
# API-லிருந்து வரும் Data Class பெயர்களை மாற்றக்கூடாது
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.aeoncorex.streamx.ui.movie.** { *; } 
-keep class retrofit2.** { *; }
-dontwarn retrofit2.**
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# --- GSON Specific ---
-keepattributes EnclosingMethod
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**

# --- COIL (Image Loading) ---
-keep class coil.** { *; }
-dontwarn coil.**

# --- KOTLIN COROUTINES ---
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# --- EXO PLAYER / MEDIA3 ---
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# --- FIREBASE ---
-keep class com.google.firebase.** { *; }
