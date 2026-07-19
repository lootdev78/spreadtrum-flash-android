# JNI entry points and callback method names are resolved by string from native code.
-keep class io.github.lootdev78.spdflash.NativeBridge { *; }
-keep interface io.github.lootdev78.spdflash.NativeCallbacks { *; }
-keepclassmembers class * implements io.github.lootdev78.spdflash.NativeCallbacks {
    public void onNativeLog(java.lang.String);
    public void onNativeProgress(long, long, long, java.lang.String);
    public int onNativeOpenOutput(java.lang.String, boolean);
    public int onNativeOpenInput(java.lang.String);
}
