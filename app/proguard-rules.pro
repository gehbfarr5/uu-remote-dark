-dontwarn io.github.libxposed.**
-dontwarn org.luckypray.dexkit.**
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    <init>();
}
-keep class io.github.uuremotedark.hook.ModuleEntry { *; }
-keep class io.github.uuremotedark.ui.** { *; }
