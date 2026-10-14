package com.notebudget.app.core.database

/**
 * `net.zetetic:sqlcipher-android` does not load its own native library automatically — unlike most Android native
 * dependencies, its own README shows every usage example starting with an explicit `System.loadLibrary("sqlcipher")`
 * call. Missing this (D188: found on a real phone, after D186/D187 chased a different, wrong theory) throws
 * `UnsatisfiedLinkError: no implementation found for ... net.zetetic.database.sqlcipher.SQLiteConnection.native...`
 * the moment any native call is made — the class loads fine, only the JNI methods are missing their implementation.
 *
 * `System.loadLibrary` is itself safe to call more than once (the JVM/ART tracks what is already loaded and returns
 * immediately), so every entry point that touches `net.zetetic.database.sqlcipher.SQLiteDatabase` or
 * `SupportOpenHelperFactory` calls [ensureLoaded] first, rather than relying on exactly one of them running first.
 */
object SqlCipherNativeLibrary {
    fun ensureLoaded() {
        System.loadLibrary("sqlcipher")
    }
}
