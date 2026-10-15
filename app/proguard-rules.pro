# Room, Coil and Gson ship their own consumer rules. Nothing here is reached by reflection: the edit
# JSON is read and written by hand, and the shaders are plain strings.

# LiteRT's native code makes its results and exceptions by class and member name.
-keep class com.google.ai.edge.litert.** { *; }
# Only LiteRT's Play AI-pack delivery uses these, and it is left out of the build.
-dontwarn com.google.android.play.core.aipacks.**
-dontwarn com.google.android.gms.tasks.**

# Crash reports from a shrunk build stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
