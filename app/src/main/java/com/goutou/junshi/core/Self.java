package com.goutou.junshi.core;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Access to the module's own package.
 *
 * <p>The module runs inside the host process, so its JavaScript payloads cannot be read with the
 * host's {@code AssetManager}.  They are read straight out of the module APK instead: the path comes
 * from the zygote parameter when the framework provides it, and from the package manager otherwise.
 * </p>
 */
public final class Self {

    public static final String PACKAGE = "com.goutou.junshi";

    private static volatile String modulePath;
    private static final Map<String, String> ASSETS = new HashMap<>();

    private Self() {
    }

    public static void setModulePath(String path) {
        if (path != null && !path.isEmpty()) {
            modulePath = path;
            Logx.i("module path: " + path);
        }
    }

    public static String apkPath() {
        String p = modulePath;
        if (p != null) {
            return p;
        }
        Context ctx = Activities.anyContext();
        if (ctx != null) {
            try {
                ApplicationInfo info = ctx.getPackageManager().getApplicationInfo(PACKAGE, 0);
                if (info != null && info.sourceDir != null) {
                    modulePath = info.sourceDir;
                    return modulePath;
                }
            } catch (PackageManager.NameNotFoundException ignored) {
            } catch (Throwable t) {
                Logx.e("resolve module apk", t);
            }
        }
        return null;
    }

    /** Reads a text asset out of the module APK, {@code null} when unavailable. */
    public static String asset(String name) {
        synchronized (ASSETS) {
            String cached = ASSETS.get(name);
            if (cached != null) {
                return cached;
            }
        }
        String path = apkPath();
        if (path == null) {
            Logx.w("asset " + name + ": module apk unknown");
            return null;
        }
        ZipFile zip = null;
        try {
            zip = new ZipFile(path);
            ZipEntry entry = zip.getEntry("assets/" + name);
            if (entry == null) {
                // aapt2 on Windows stores "assets/js\probe.js"; match by normalised name.
                String want = "assets/" + name;
                java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry candidate = entries.nextElement();
                    String normalised = candidate.getName().replace('\\', '/');
                    if (normalised.equals(want)) {
                        entry = candidate;
                        break;
                    }
                }
            }
            if (entry == null) {
                Logx.w("asset missing: " + name);
                return null;
            }
            InputStream in = zip.getInputStream(entry);
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, (int) entry.getSize()));
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            in.close();
            String text = new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            synchronized (ASSETS) {
                ASSETS.put(name, text);
            }
            return text;
        } catch (Throwable t) {
            Logx.e("asset read " + name, t);
            return null;
        } finally {
            if (zip != null) {
                try {
                    zip.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
