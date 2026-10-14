# Room, Coil and Gson ship their own consumer rules. Nothing here is reached by reflection: the edit
# JSON is read and written by hand, and the shaders are plain strings.

# Crash reports from a shrunk build stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
