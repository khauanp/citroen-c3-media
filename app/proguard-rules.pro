# Keep JNI callback interface (called from native code)
-keep class io.github.jqssun.airplay.bridge.RaopCallbackHandler { *; }
-keep class * implements io.github.jqssun.airplay.bridge.RaopCallbackHandler { *; }
-keep class io.github.jqssun.airplay.bridge.LogListener { *; }
-keep class * implements io.github.jqssun.airplay.bridge.LogListener { *; }

# Resolved directly on the concrete service by the restored K00E native stack.
-keepclassmembers class io.github.jqssun.airplay.service.AirPlayService {
    public void onAudioActivity();
}

# Keep NativeBridge native methods
-keep class io.github.jqssun.airplay.bridge.NativeBridge { *; }
