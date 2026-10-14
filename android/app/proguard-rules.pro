# R8 in release builds (O8, D289): shrinking and optimisation on, renaming off.
#
# Names stay readable on purpose: the error window (D224) and the app's journal (D230) show stack traces that the owner copies and
# sends; renamed classes (a.b.c) would make them useless without the mapping file of that very build. The source is open, so
# renaming would hide nothing. The libraries bring their own rules (kotlinx.serialization, Room, SQLCipher's JNI, Media3, CameraX).
-dontobfuscate

# File names and line numbers in stack traces.
-keepattributes SourceFile,LineNumberTable
