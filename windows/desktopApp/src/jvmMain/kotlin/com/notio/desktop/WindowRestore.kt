package com.notio.desktop

import com.notio.shared.AppLog
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.SystemTray
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent

/**
 * The main window brought back from the tray, the taskbar or a second start (DESKTOP-ONLY, D45, D70), straight on AWT: it does not
 * wait for a frame of the window's own composition. Shown, no longer minimised, on a screen that exists now (a monitor switched off
 * or unplugged overnight takes its place with it), of a size a person can see, in front. On Windows the same is then asked of
 * Windows itself (D140): after a night in the tray AWT said "showing" and no window was there.
 */
object WindowRestore {
    /** How much of the window must lie on some screen to be reachable with the mouse. */
    private const val MIN_VISIBLE = 80
    /** A window smaller than this is not one a person can use: it gets [START_WIDTH] x [START_HEIGHT] again. */
    private const val MIN_WIDTH = 320
    private const val MIN_HEIGHT = 240
    /** The size the window starts with (`Main.kt`). */
    private const val START_WIDTH = 1100
    private const val START_HEIGHT = 820
    /** A screen smaller than this is a display that is off, not a place for a window. */
    private const val MIN_SCREEN = 200

    private val onWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")

    fun show(w: Frame) {
        val wasHidden = !w.isVisible
        val wasMinimized = w.extendedState and Frame.ICONIFIED != 0
        if (wasHidden) w.isVisible = true
        if (wasMinimized) w.extendedState = w.extendedState and Frame.ICONIFIED.inv()
        val before = w.bounds
        val screens = screens()
        val place = placement(before, screens)
        if (place != null) {
            if (w.extendedState and Frame.MAXIMIZED_BOTH != 0) w.extendedState = Frame.NORMAL
            w.bounds = place
        }
        w.toFront()
        w.requestFocus()
        val windows = if (onWindows) runCatching { askWindows(w) }.getOrElse { "failed: $it" } else null
        AppLog.i(
            "window",
            "back in front (hidden: $wasHidden, minimised: $wasMinimized, moved onto a screen: ${place != null}, showing: ${w.isShowing}, " +
                "was at ${text(before)}, now at ${text(w.bounds)}, screens: ${screens.joinToString { text(it) }}" + (windows?.let { ", Windows: $it" } ?: "") + ")",
        )
    }

    private fun text(r: Rectangle) = "${r.x},${r.y} ${r.width}x${r.height}"

    /** The screens of now, the main one first. */
    private fun screens(): List<Rectangle> = runCatching {
        val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val main = ge.defaultScreenDevice
        (listOf(main) + ge.screenDevices.filter { it != main }).map { it.defaultConfiguration.bounds }
    }.getOrDefault(emptyList())

    /** No screen shows at least [MIN_VISIBLE] x [MIN_VISIBLE] of [bounds]. No screens known: the window is left where it is. */
    fun offScreen(bounds: Rectangle, screens: List<Rectangle>): Boolean =
        screens.isNotEmpty() && screens.none { s -> s.intersection(bounds).let { it.width >= MIN_VISIBLE && it.height >= MIN_VISIBLE } }

    /**
     * Where a window standing at [bounds] is put so that a person sees it; `null` - it is seen as it stands. A window outside every
     * screen, or one too small to use, goes to the middle of the first screen of a real size, never larger than that screen; the
     * too small one gets the starting size. Screens without a size (a display that is off) are not counted; none left - `null`.
     */
    fun placement(bounds: Rectangle, screens: List<Rectangle>): Rectangle? {
        val real = screens.filter { it.width >= MIN_SCREEN && it.height >= MIN_SCREEN }
        val tooSmall = bounds.width < MIN_WIDTH || bounds.height < MIN_HEIGHT
        if (real.isEmpty() || (!tooSmall && !offScreen(bounds, real))) return null
        val screen = real.first()
        val width = (if (tooSmall) START_WIDTH else bounds.width).coerceAtMost(screen.width)
        val height = (if (tooSmall) START_HEIGHT else bounds.height).coerceAtMost(screen.height)
        return Rectangle(screen.x + (screen.width - width) / 2, screen.y + (screen.height - height) / 2, width, height)
    }

    /**
     * What Windows holds of the window, and the same repair there (D140): AWT and Windows can disagree after the displays changed.
     * Hidden or minimised for Windows - shown and restored; on no monitor or too small - into the work area of the main monitor;
     * then in front. Returns the words of the log line.
     */
    private fun askWindows(w: Frame): String {
        val user32 = User32.INSTANCE
        val hwnd = WinDef.HWND(com.sun.jna.Native.getWindowPointer(w) ?: return "no window handle")
        val visible = user32.IsWindowVisible(hwnd)
        val minimized = user32.GetWindowLong(hwnd, WinUser.GWL_STYLE) and WinUser.WS_MINIMIZE != 0
        if (!visible) user32.ShowWindow(hwnd, WinUser.SW_SHOW)
        if (minimized) user32.ShowWindow(hwnd, WinUser.SW_RESTORE)
        val rect = WinDef.RECT()
        user32.GetWindowRect(hwnd, rect)
        val at = Rectangle(rect.left, rect.top, rect.right - rect.left, rect.bottom - rect.top)
        val onMonitor = user32.MonitorFromWindow(hwnd, WinUser.MONITOR_DEFAULTTONULL) != null
        var moved: Rectangle? = null
        if (!onMonitor || at.width < MIN_WIDTH || at.height < MIN_HEIGHT) {
            val info = WinUser.MONITORINFO()
            val main = user32.MonitorFromWindow(hwnd, WinUser.MONITOR_DEFAULTTOPRIMARY)
            if (main != null && user32.GetMonitorInfo(main, info).booleanValue()) {
                val work = Rectangle(info.rcWork.left, info.rcWork.top, info.rcWork.right - info.rcWork.left, info.rcWork.bottom - info.rcWork.top)
                // Windows counts in its own pixels: the work area is the screen, the window keeps its size when it is a usable one.
                moved = placement(Rectangle(work.x - at.width - MIN_SCREEN, work.y, at.width, at.height), listOf(work))
                moved?.let { user32.SetWindowPos(hwnd, null, it.x, it.y, it.width, it.height, WinUser.SWP_NOZORDER or WinUser.SWP_NOACTIVATE) }
            }
        }
        val front = user32.SetForegroundWindow(hwnd)
        return "visible $visible, minimised $minimized, on a monitor $onMonitor, at ${text(at)}" + (moved?.let { ", put at ${text(it)}" } ?: "") + ", in front $front"
    }

    /**
     * One left click on the tray icon opens the window, as the other Windows apps do: AWT reports only a double click as the icon's
     * action, so a single click did nothing (the user, 2026-09-29: the window "did not open from the tray"). Called once the tray
     * icon is added; `false` while it is not there yet.
     */
    fun openOnClick(open: () -> Unit): Boolean {
        val icon = runCatching { SystemTray.getSystemTray().trayIcons.firstOrNull() }.getOrNull() ?: return false
        icon.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.button == MouseEvent.BUTTON1 && e.clickCount == 1) open()
            }
        })
        return true
    }
}
