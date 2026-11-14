package com.goutou.junshi.core;

import android.os.Handler;
import android.os.Looper;

/** Main-thread plumbing and user feedback. */
public final class Ui {

    private static volatile Handler main;

    private Ui() {
    }

    public static Handler handler() {
        Handler h = main;
        if (h == null) {
            synchronized (Ui.class) {
                h = main;
                if (h == null) {
                    h = new Handler(Looper.getMainLooper());
                    main = h;
                }
            }
        }
        return h;
    }

    public static void run(Runnable r) {
        try {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                r.run();
            } else {
                handler().post(r);
            }
        } catch (Throwable t) {
            Logx.e("post failed", t);
        }
    }

    public static void later(Runnable r, long delayMs) {
        try {
            handler().postDelayed(r, Math.max(0L, delayMs));
        } catch (Throwable t) {
            Logx.e("postDelayed failed", t);
        }
    }

    public static void toast(final CharSequence text) {
        show(text, android.widget.Toast.LENGTH_SHORT);
    }

    /** Same as {@link #toast} but stays on screen twice as long (used for answer summaries). */
    public static void toastLong(final CharSequence text) {
        show(text, android.widget.Toast.LENGTH_LONG);
    }

    private static void show(final CharSequence text, final int length) {
        Cfg.Snapshot cfg = Cfg.get();
        if (!cfg.answer || !cfg.toastOn) {
            return;
        }
        run(new Runnable() {
            @Override
            public void run() {
                try {
                    android.content.Context ctx = Activities.app();
                    if (ctx == null) {
                        return;
                    }
                    android.widget.Toast.makeText(ctx, text, length).show();
                } catch (Throwable ignored) {
                }
            }
        });
    }
}
