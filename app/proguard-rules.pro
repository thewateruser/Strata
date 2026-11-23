-android
-dontobfuscate
-dontwarn **
-dontnote **
-ignorewarnings
-optimizationpasses 3
-allowaccessmodification
# Backport Java 8+ invokedynamic lambdas (Kotlin stdlib) so the dx dexer can handle them.
-target 1.7

-keep class app.strata.psd.** extends android.app.Activity { *; }
-keep class app.strata.psd.** extends android.app.Application { *; }
-keep class app.strata.psd.** extends android.content.ContentProvider { *; }
-keepclassmembers class * extends android.view.View { public <init>(...); }

-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void checkNotNull*(...);
    public static void checkParameterIsNotNull(...);
    public static void checkExpressionValueIsNotNull(...);
    public static void checkNotNullExpressionValue(...);
    public static void checkNotNullParameter(...);
    public static void checkFieldIsNotNull(...);
    public static void checkReturnedValueIsNotNull(...);
}

# dx is strict about local variable tables that optimization leaves stale; drop them,
# and avoid optimizations that dx historically mis-handles.
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*,!code/allocation/variable
