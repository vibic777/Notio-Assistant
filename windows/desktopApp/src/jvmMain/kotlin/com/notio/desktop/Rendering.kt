package com.notio.desktop

import java.io.File
import java.util.Properties

/**
 * How the window is drawn (D83; user, 2026-10-04: "FitEasy on Windows does not lag, the cursor does not jerk, ours does — find why").
 * Compose draws through the graphics card (Direct3D, else OpenGL). On a virtual or basic display adapter — VMware SVGA 3D on the
 * owner's computer, where the log said "drawn through OPENGL" — that path is emulated and the window stutters under the mouse;
 * drawing by the processor (Skia's SOFTWARE_FAST, what FitEasy uses) is smooth there. So: AUTO draws by the processor when every
 * display adapter is a virtual or basic one and through the graphics card otherwise; a person can choose in Settings. The choice
 * is read before the first window (Skia takes it once), so a change works from the next start.
 */
object Rendering {
    const val AUTO = "AUTO"
    const val SOFTWARE = "SOFTWARE_FAST"
    const val DIRECT3D = "DIRECT3D"
    const val OPENGL = "OPENGL"
    val MODES = listOf(AUTO, SOFTWARE, DIRECT3D, OPENGL)

    private const val FILE = "renderer.properties"
    private const val KEY_MODE = "mode"
    /** What AUTO came to at the last look: SOFTWARE_FAST, or HARDWARE (Compose's own choice). */
    private const val KEY_AUTO = "auto"
    private const val KEY_ADAPTERS = "adapters"
    const val HARDWARE = "HARDWARE"

    /** The display adapters seen at the last look, for Settings. */
    @Volatile var adapters: String = ""
        private set

    /** Called first in `main`, before anything of Compose. A `-Dskiko.renderApi` or SKIKO_RENDER_API given from outside wins. */
    fun configure(dataDir: File) {
        if (System.getProperty("skiko.renderApi") != null || System.getenv("SKIKO_RENDER_API") != null) return
        val stored = read(dataDir)
        adapters = stored.getProperty(KEY_ADAPTERS).orEmpty()
        val mode = stored.getProperty(KEY_MODE)?.takeIf { it in MODES } ?: AUTO
        val known = stored.getProperty(KEY_AUTO)
        val use = if (mode == AUTO) known ?: look(dataDir, stored) else mode
        if (use != HARDWARE) System.setProperty("skiko.renderApi", use)
        // The adapters may change (another computer for a portable Notio, a new driver): looked at again off the start path, for
        // the next start.
        if (mode == AUTO && known != null) {
            Thread({ runCatching { look(dataDir, read(dataDir)) } }, "notio-rendering").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
        }
    }

    fun mode(dataDir: File): String = read(dataDir).getProperty(KEY_MODE)?.takeIf { it in MODES } ?: AUTO

    fun setMode(dataDir: File, mode: String) {
        require(mode in MODES)
        val stored = read(dataDir)
        stored.setProperty(KEY_MODE, mode)
        write(dataDir, stored)
    }

    /** Looks at the display adapters, keeps what AUTO comes to, and returns it. */
    private fun look(dataDir: File, stored: Properties): String {
        val names = adapterNames()
        val auto = autoFor(names)
        adapters = names.joinToString(", ")
        if (stored.getProperty(KEY_AUTO) != auto || stored.getProperty(KEY_ADAPTERS) != adapters) {
            stored.setProperty(KEY_AUTO, auto)
            stored.setProperty(KEY_ADAPTERS, adapters)
            write(dataDir, stored)
        }
        return auto
    }

    /** By the processor only when there is an adapter and none of them is a real graphics card. */
    fun autoFor(adapterNames: List<String>): String =
        if (adapterNames.isNotEmpty() && adapterNames.all(::isVirtual)) SOFTWARE else HARDWARE

    private val VIRTUAL = listOf(
        "vmware", "svga", "virtualbox", "vbox", "hyper-v", "microsoft basic", "basic display", "basic render", "parallels", "qxl", "virtio",
        "red hat", "citrix", "remote display", "remotefx", "standard vga",
    )

    fun isVirtual(adapterName: String): Boolean = VIRTUAL.any { adapterName.lowercase().contains(it) }

    /** The names of the display adapters from the registry (a few milliseconds; no process is started). Empty when not on Windows. */
    private fun adapterNames(): List<String> {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return emptyList()
        return runCatching {
            val root = com.sun.jna.platform.win32.WinReg.HKEY_LOCAL_MACHINE
            val key = "SYSTEM\\CurrentControlSet\\Control\\Class\\{4d36e968-e325-11ce-bfc1-08002be10318}"
            com.sun.jna.platform.win32.Advapi32Util.registryGetKeys(root, key)
                .filter { it.length == 4 && it.all(Char::isDigit) }
                .mapNotNull { sub -> runCatching { com.sun.jna.platform.win32.Advapi32Util.registryGetStringValue(root, "$key\\$sub", "DriverDesc") }.getOrNull() }
                .filter { it.isNotBlank() }
                .distinct()
        }.getOrDefault(emptyList())
    }

    private fun read(dataDir: File): Properties = Properties().apply {
        runCatching { File(dataDir, FILE).takeIf { it.isFile && it.length() < 4096 }?.inputStream()?.use(::load) }
    }

    private fun write(dataDir: File, properties: Properties) {
        runCatching {
            dataDir.mkdirs()
            val temp = File(dataDir, "$FILE.tmp")
            temp.outputStream().use { properties.store(it, null) }
            java.nio.file.Files.move(temp.toPath(), File(dataDir, FILE).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
