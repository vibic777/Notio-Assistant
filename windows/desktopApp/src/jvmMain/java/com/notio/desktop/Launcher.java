package com.notio.desktop;

import java.io.File;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The first class of the app (AGENT.md D91, D95), in Java on purpose: it needs nothing but the Java runtime, so it still runs when
 * the libraries of the app are gone. Whatever ends a start before the app's own log and crash window are there is written to
 * `logs/app.log` from here, at once, and told to the person: an installation that lost its files, an error on the way to the
 * window, a second start that found a Notio which does not answer. Nothing of Kotlin may be used in this file
 * ({@code LauncherTest} looks at the class).
 */
public final class Launcher {
    /** The exit code of a damaged installation (1 is Java's own, 10 a crash of the app). */
    public static final int DAMAGED = 3;
    /** The exit code of a second start that found a Notio which does not answer. */
    public static final int NOT_ANSWERING = 4;
    /** The exit code of an error at the start, the one a crash of the app has. */
    public static final int CRASH = 10;

    private static final String DAMAGED_TEXT =
        "Notio cannot start: files of the program are missing or damaged. Install Notio again. Your notes and settings stay in place.";
    private static final String NOT_ANSWERING_TEXT =
        "Notio is already running but does not answer. End Notio in the Task Manager and start it again.";
    private static final String CRASH_TEXT = "Notio closed because of an error";

    private Launcher() {}

    public static void main(String[] args) {
        List<String> missing = missing(System.getProperty("java.class.path", ""));
        if (!missing.isEmpty()) {
            damaged(missing.size() + " missing, the first: " + missing.get(0));
            return;
        }
        try {
            MainKt.main(args);
        } catch (LinkageError e) {
            // A file that is there but is not what it was: a class of the app or of a library does not load.
            damaged(e.toString());
        } catch (Throwable e) {
            // The app's own crash window is there: it writes the log and shows the text.
            if (Thread.getDefaultUncaughtExceptionHandler() != null) throw e;
            // Before it (the data folder, the check for a running Notio): nothing else would say why Notio did not open.
            startFailed(e);
        }
    }

    /** The entries of the class path that are not on the disk. */
    static List<String> missing(String classPath) {
        List<String> missing = new ArrayList<>();
        for (String entry : classPath.split(Pattern.quote(File.pathSeparator))) {
            if (!entry.isEmpty() && !new File(entry).exists()) missing.add(entry);
        }
        return missing;
    }

    private static void damaged(String detail) {
        String text = text("desktop_strings.xml", "desktop_damaged", DAMAGED_TEXT);
        log("FATAL", "the installation is damaged: " + detail);
        tell(text, detail);
        System.exit(DAMAGED);
    }

    private static void startFailed(Throwable error) {
        StringWriter stack = new StringWriter();
        error.printStackTrace(new PrintWriter(stack));
        log("FATAL", "the start failed before the window: " + stack.toString().trim());
        tell(text("strings.xml", "crash_now_title", CRASH_TEXT), firstLines(stack.toString(), 12));
        System.exit(CRASH);
    }

    /**
     * A second start that found the data folder held by a Notio which does not answer (D95): before, it ended without a word and
     * Notio "did not open". Called by {@code main} of the app.
     */
    public static void notAnswering() {
        log("ERROR", "a second start: another Notio holds the data folder and does not answer");
        tell(text("desktop_strings.xml", "desktop_not_answering", NOT_ANSWERING_TEXT), "");
        System.exit(NOT_ANSWERING);
    }

    private static void tell(String text, String detail) {
        System.err.println(text + (detail.isEmpty() ? "" : " (" + detail + ")"));
        try {
            String shown = wrapped(text) + (detail.isEmpty() ? "" : "\n\n" + wrappedLines(detail));
            javax.swing.JOptionPane.showMessageDialog(null, shown, "Notio", javax.swing.JOptionPane.ERROR_MESSAGE);
        } catch (Throwable ignored) {
            // No display (a Linux machine without a screen): the terminal has the text.
        }
    }

    /** The text of [key] in the language of the system when it is one of Notio's ten, in English otherwise; from the app's own jar. */
    static String text(String file, String key, String fallback) {
        String text = stringOf("strings/values-" + Locale.getDefault().getLanguage() + "/" + file, key);
        if (text == null) text = stringOf("strings/values/" + file, key);
        return text != null ? text : fallback;
    }

    /** The text about a damaged installation in [language]. */
    static String message(String language) {
        String text = stringOf("strings/values-" + language + "/desktop_strings.xml", "desktop_damaged");
        if (text == null) text = stringOf("strings/values/desktop_strings.xml", "desktop_damaged");
        return text != null ? text : DAMAGED_TEXT;
    }

    static String stringOf(String resource, String key) {
        try (InputStream in = Launcher.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) return null;
            String xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("<string name=\"" + Pattern.quote(key) + "\">(.*?)</string>", Pattern.DOTALL).matcher(xml);
            return m.find() ? m.group(1).trim().replace("\\'", "'").replace("\\\"", "\"") : null;
        } catch (Throwable e) {
            return null;
        }
    }

    /** Long lines broken for the message box, which does not wrap by itself. */
    static String wrapped(String text) {
        StringBuilder out = new StringBuilder();
        int line = 0;
        for (String word : text.split(" ")) {
            if (line > 0 && line + word.length() > 80) { out.append('\n'); line = 0; }
            else if (line > 0) { out.append(' '); line++; }
            out.append(word);
            line += word.length();
        }
        return out.toString();
    }

    private static String wrappedLines(String text) {
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n")) {
            if (out.length() > 0) out.append('\n');
            out.append(wrapped(line.replace("\t", "    ")));
        }
        return out.toString();
    }

    static String firstLines(String text, int count) {
        String[] lines = text.trim().split("\r?\n");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length && i < count; i++) {
            if (i > 0) out.append('\n');
            out.append(lines[i]);
        }
        if (lines.length > count) out.append("\n...");
        return out.toString();
    }

    /** One line of the app log in its format: `2026-10-05 07:53:53.987 FATAL    app: message`. */
    static String line(Date when, String level, String message) {
        StringBuilder padded = new StringBuilder(level);
        while (padded.length() < 8) padded.append(' ');
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(when) + " " + padded + " app: " + message + System.lineSeparator();
    }

    /**
     * A line in the app log written at once, on this thread, without the app's own log: for what happens before that log is
     * there or in a start that ends right after (the next look at the log then shows why Notio did not open).
     */
    public static void log(String level, String message) {
        try {
            File logs = new File(dataRoot(), "logs");
            logs.mkdirs();
            Files.write(new File(logs, "app.log").toPath(), line(new Date(), level, message).getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Throwable ignored) {
            // The log is a help, never a reason not to tell the person.
        }
    }

    /** Where the data is: the rules of {@code AppPaths.default()}, which is Kotlin and may not load here. */
    static File dataRoot() {
        String home = System.getProperty("notio.home");
        if (home != null) return new File(home);
        String env = System.getenv("NOTIO_HOME");
        if (env != null && !env.isBlank()) return new File(env);
        String app = System.getProperty("jpackage.app-path");
        if (app != null) {
            File dir = new File(app).getAbsoluteFile().getParentFile();
            for (File d : new File[] { dir, dir == null ? null : dir.getParentFile() }) {
                if (d != null && new File(d, "portable.txt").isFile()) return new File(d, "data");
            }
        }
        String local = System.getenv("LOCALAPPDATA");
        if (local != null && !local.isBlank()) return new File(local, "Notio");
        return new File(System.getProperty("user.home"), ".local/share/notio");
    }
}
