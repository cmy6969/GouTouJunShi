package com.goutou.junshi.answer;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Build;
import android.view.PixelCopy;
import android.view.View;
import android.view.Window;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import com.goutou.junshi.core.Activities;
import com.goutou.junshi.core.Logx;
import com.goutou.junshi.core.Ui;

/**
 * Captures the question that is currently on screen as one JPEG.
 *
 * <p>Three facts shape this class:</p>
 * <ol>
 *   <li>WebView pixels are not produced by {@code View.draw()}, so they are copied from the window
 *       surface with {@link PixelCopy} (API 26+).</li>
 *   <li>A physics question with formula images does not fit one screen, and 学习通 clips it inside a
 *       JavaScript-scrolled box ({@code div.answerCon}, {@code overflow:hidden} + {@code scrollTop}),
 *       so the box reported by {@code __lumen.shotBox()} is used as the camera frame.</li>
 *   <li>The box is scrolled in steps and the slices are stitched by the exact scroll offsets, so the
 *       result is the whole question regardless of how tall it is.</li>
 * </ol>
 */
public final class Shot {

    private static final int MAX_WIDTH = 960;
    private static final int JPEG_QUALITY = 85;
    private static final long SETTLE_MS = 350;
    private static final int MAX_SLICES = 5;

    public interface Callback {
        /** @param base64 JPEG bytes as base64, or {@code null} when the capture failed. */
        void onShot(String base64, String note);
    }

    private Shot() {
    }

    /**
     * Blocking variant for background threads: posts the capture to the main thread and waits.
     *
     * @return base64 JPEG, or {@code null} on failure/timeout
     */
    public static String captureBlocking(final WebRef web, final int index, long timeoutMs) {
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final String[] result = new String[1];
        Ui.run(new Runnable() {
            @Override
            public void run() {
                try {
                    capture(web, index, new Callback() {
                        @Override
                        public void onShot(String base64, String note) {
                            result[0] = base64;
                            if (base64 == null) {
                                Logx.w("shot: " + note);
                            }
                            latch.countDown();
                        }
                    });
                } catch (Throwable t) {
                    Logx.e("shot: blocking capture", t);
                    latch.countDown();
                }
            }
        });
        try {
            if (!latch.await(Math.max(2000L, timeoutMs), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                Logx.w("shot: timed out");
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return result[0];
    }

    /** Entry point: must be called on the main thread. */
    public static void capture(final WebRef web, final int index, final Callback cb) {
        if (web == null) {
            cb.onShot(null, "no webview");
            return;
        }
        web.eval("(window.__lumen?__lumen.shotBox():'{}')", new WebRef.JsResult() {
            @Override
            public void onValue(String value) {
                JSONObject box;
                try {
                    box = new JSONObject(unquote(value));
                } catch (Throwable t) {
                    cb.onShot(null, "no camera frame");
                    return;
                }
                if (box.optInt("h", 0) <= 0 || box.optInt("w", 0) <= 0) {
                    cb.onShot(null, "no camera frame");
                    return;
                }
                try {
                    run(web, box, cb);
                } catch (Throwable t) {
                    Logx.e("shot failed", t);
                    cb.onShot(null, "capture failed: " + t.getClass().getSimpleName());
                }
            }
        });
    }

    private static void run(final WebRef web, JSONObject box, final Callback cb) {
        Activity activity = Activities.current();
        View view = web.raw() instanceof View ? (View) web.raw() : null;
        if (activity == null || view == null || Build.VERSION.SDK_INT < 26) {
            cb.onShot(null, "unsupported");
            return;
        }
        Window window = activity.getWindow();
        if (window == null) {
            cb.onShot(null, "no window");
            return;
        }
        int[] loc = new int[2];
        view.getLocationOnScreen(loc);
        int viewW = view.getWidth();
        int viewH = view.getHeight();
        if (viewW <= 0 || viewH <= 0) {
            cb.onShot(null, "no view size");
            return;
        }

        double scale = box.optDouble("dpr", 1d);
        if (scale <= 0.2 || scale > 4) {
            scale = 1d;
        }
        final int cssX = Math.max(0, box.optInt("x", 0));
        final int cssY = Math.max(0, box.optInt("y", 0));
        final int cssW = Math.max(1, box.optInt("w", viewW));
        final int cssH = Math.max(1, box.optInt("h", viewH));
        final int max = Math.max(0, box.optInt("max", 0));
        final int y0 = Math.max(0, box.optInt("scrollY", 0));

        int rx = loc[0] + (int) Math.round(cssX * scale);
        int ry = loc[1] + (int) Math.round(cssY * scale);
        int rw = Math.min((int) Math.round(cssW * scale), loc[0] + viewW - rx);
        int rh = Math.min((int) Math.round(cssH * scale), loc[1] + viewH - ry);
        if (rw <= 0 || rh <= 0) {
            cb.onShot(null, "camera frame outside the view");
            return;
        }
        final Rect src = new Rect(rx, ry, rx + rw, ry + rh);

        // Stops: one screenful at a time; the tail is always captured so nothing is cut off.
        final List<Integer> stops = new ArrayList<>();
        int step = Math.max(120, (int) (cssH * 0.9));
        stops.add(0);
        for (int y = step; y < max && stops.size() < MAX_SLICES - 1; y += step) {
            stops.add(y);
        }
        if (max > 0 && (stops.size() == 1 || stops.get(stops.size() - 1) != max)) {
            if (stops.size() < MAX_SLICES) {
                stops.add(max);
            }
        }

        int contentH = Math.min(stops.get(stops.size() - 1) + cssH, max + cssH);
        int canvasH = Math.max(1, Math.min((int) Math.ceil(contentH * scale), 16000));
        final Bitmap canvas = Bitmap.createBitmap(rw, canvasH, Bitmap.Config.ARGB_8888);
        final Canvas c = new Canvas(canvas);
        c.drawARGB(255, 255, 255, 255);

        Logx.i("shot: frame " + box.optString("mode", "?") + " " + cssW + "x" + cssH
                + " at " + cssX + "," + cssY + " max=" + max + " slices=" + stops.size()
                + " scale=" + scale);

        final double fScale = scale;
        final int fY0 = y0;
        Ball.setVisible(false);
        next(web, window, src, rw, rh, stops, 0, canvas, c, fScale, new Runnable() {
            @Override
            public void run() {
                web.eval("__lumen.shotScroll(" + fY0 + ")", null);
                Ball.setVisible(true);
                String base64 = encode(canvas);
                canvas.recycle();
                done(base64, cb);
            }
        });
    }

    private static void done(String base64, Callback cb) {
        cb.onShot(base64, base64 == null ? "encode failed" : "");
    }

    /** Scrolls to {@code stops[index]}, copies that slice, then recurses. */
    private static void next(final WebRef web, final Window window, final Rect src,
                             final int rw, final int rh, final List<Integer> stops,
                             final int index, final Bitmap canvas, final Canvas c,
                             final double scale, final Runnable finished) {
        if (index >= stops.size()) {
            finished.run();
            return;
        }
        final int want = stops.get(index);
        web.eval("__lumen.shotScroll(" + want + ")", new WebRef.JsResult() {
            @Override
            public void onValue(String value) {
                final int actual = Math.max(0, parseInt(unquote(value), want));
                Ui.later(new Runnable() {
                    @Override
                    public void run() {
                        copySlice(window, src, rw, rh, canvas, c, actual, scale, new Runnable() {
                            @Override
                            public void run() {
                                next(web, window, src, rw, rh, stops, index + 1, canvas, c, scale,
                                        finished);
                            }
                        });
                    }
                }, SETTLE_MS);
            }
        });
    }

    /** Copies one screenful into the stitched canvas at its content offset. */
    private static void copySlice(Window window, Rect src, int rw, int rh,
                                  final Bitmap canvas, final Canvas c, final int scrollY,
                                  final double scale, final Runnable nextSlice) {
        final Bitmap slice = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888);
        try {
            PixelCopy.request(window, src, slice, new PixelCopy.OnPixelCopyFinishedListener() {
                @Override
                public void onPixelCopyFinished(int result) {
                    if (result == PixelCopy.SUCCESS) {
                        int dstTop = (int) Math.round(scrollY * scale);
                        int h = Math.min(rh, canvas.getHeight() - dstTop);
                        if (h > 0) {
                            Rect s = new Rect(0, 0, rw, h);
                            Rect d = new Rect(0, dstTop, rw, dstTop + h);
                            c.drawBitmap(slice, s, d, null);
                        }
                    } else {
                        Logx.w("shot: pixel copy result=" + result);
                    }
                    slice.recycle();
                    nextSlice.run();
                }
            }, Ui.handler());
        } catch (Throwable t) {
            Logx.e("shot: pixel copy", t);
            slice.recycle();
            nextSlice.run();
        }
    }

    private static String encode(Bitmap bitmap) {
        try {
            Bitmap out = bitmap;
            if (bitmap.getWidth() > MAX_WIDTH) {
                int w = MAX_WIDTH;
                int h = Math.max(1, Math.round(bitmap.getHeight() * (w / (float) bitmap.getWidth())));
                out = Bitmap.createScaledBitmap(bitmap, w, h, true);
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            out.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bytes);
            if (out != bitmap) {
                out.recycle();
            }
            byte[] data = bytes.toByteArray();
            saveDebugCopy(data);
            return android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP);
        } catch (Throwable t) {
            Logx.e("shot: encode", t);
            return null;
        }
    }

    /** Writes the last capture into the host's private cache when {@code debug_shot} is on. */
    private static void saveDebugCopy(byte[] jpeg) {
        try {
            if (!com.goutou.junshi.core.Cfg.get().debugShot) {
                return;
            }
            android.content.Context ctx = com.goutou.junshi.core.Activities.anyContext();
            if (ctx == null) {
                return;
            }
            java.io.File dir = new java.io.File(ctx.getCacheDir(), "shot");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return;
            }
            java.io.File target = new java.io.File(dir, "shot-last.jpg");
            java.io.FileOutputStream out = new java.io.FileOutputStream(target);
            try {
                out.write(jpeg);
            } finally {
                out.close();
            }
            Logx.i("shot: saved " + target.getAbsolutePath() + " (" + jpeg.length + " bytes)");
        } catch (Throwable ignored) {
        }
    }

    private static int parseInt(String s, int def) {
        try {
            return (int) Double.parseDouble(s.trim());
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static String unquote(String s) {
        if (s == null) {
            return "";
        }
        String v = s.trim();
        if (v.length() >= 2 && v.charAt(0) == '"' && v.charAt(v.length() - 1) == '"') {
            v = v.substring(1, v.length() - 1);
        }
        return v.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\/", "/")
                .replace("\\n", "\n").replace("\\r", "").replace("\\t", " ");
    }
}
