# R8 for release builds: shrinking, and optimizing what isn't kept.
#
# Nothing is renamed: readable class names keep the stack traces in "Copy
# log" useful. The app itself is kept whole below. Everything else (Compose, AndroidX,
# OkHttp, coroutines) is optimized, as BitChord does: Compose run unoptimized
# is markedly slower, the stutter on the first scrolls and the opening.
# Shrinking removes library code nothing calls, the bulk of it unused
# Material icons, which is where the size goes.
-dontobfuscate
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable,Exceptions

# The app itself is small next to its libraries; keeping all of it means no
# setting, serializer, provider or JavaScript interface can go missing.
-keep class com.opentune.** { *; }

# Optional pieces of these libraries that Android doesn't have.
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn javax.lang.model.**
-dontwarn org.slf4j.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn com.google.re2j.**
-dontwarn jdk.dynalink.**
-dontwarn java.lang.management.**
