package com.goutou.junshi.core;

/**
 * Small, fixed section of the module's diagnostic log.
 *
 * <p>The log is intentionally dumb: a tag, a rotating file in the host app's cache directory and a
 * mirror in {@code Download/Lumen/lumen.log} so that a user can pull it without root.  Nothing is
 * ever written while the corresponding feature is disabled.</p>
 */
public final class Logx {

    public static final String TAG = "Lumen";
    private static final long MAX_BYTES = 256L * 1024L;

    private static java.io.File primary;
    private static java.io.File mirror;
    private static volatile boolean ready;

    private Logx() {
    }

    public static void bind(java.io.File cacheDir) {
        if (ready || cacheDir == null) {
            return;
        }
        try {
            // Only the host's private cache: no copy is written to shared storage, so the log can
            // never be read by another app (it also carries question ids, not secrets).
            primary = new java.io.File(cacheDir, "lumen.log");
            mirror = null;
            ready = true;
        } catch (Throwable ignored) {
            ready = false;
        }
    }

    public static void i(String message) {
        write("I", message, null);
    }

    public static void w(String message) {
        write("W", message, null);
    }

    public static void e(String message, Throwable cause) {
        write("E", message, cause);
    }

    private static void write(String level, String message, Throwable cause) {
        StringBuilder sb = new StringBuilder(160);
        sb.append('[').append(level).append("] ").append(message);
        if (cause != null) {
            sb.append(" :: ").append(cause.getClass().getName());
            if (cause.getMessage() != null) {
                sb.append(": ").append(cause.getMessage());
            }
        }
        String line = sb.toString();
        try {
            de.robv.android.xposed.XposedBridge.log(TAG + " " + line);
        } catch (Throwable ignored) {
        }
        append(primary, line);
        append(mirror, line);
    }

    private static void append(java.io.File file, String line) {
        if (file == null) {
            return;
        }
        java.io.RandomAccessFile raf = null;
        try {
            if (file.length() > MAX_BYTES) {
                java.io.File old = new java.io.File(file.getParentFile(), file.getName() + ".1");
                if (old.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    old.delete();
                }
                //noinspection ResultOfMethodCallIgnored
                file.renameTo(old);
            }
            raf = new java.io.RandomAccessFile(file, "a");
            raf.seek(raf.length());
            raf.write((line + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
