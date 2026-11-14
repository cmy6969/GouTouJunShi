package com.goutou.junshi.answer;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;

import com.goutou.junshi.core.Cfg;
import com.goutou.junshi.core.Logx;
import com.goutou.junshi.core.Ui;

/**
 * Floating answer button.
 *
 * <p>A single round button lives inside the activity's own decor view (no
 * {@code SYSTEM_ALERT_WINDOW}), can be dragged to any corner and reflects what the module is doing:</p>
 * <ul>
 *   <li><b>green</b> — automatic mode (detected questions are answered as soon as the page loads);</li>
 *   <li><b>blue</b> — manual mode (nothing happens until the button is tapped);</li>
 *   <li><b>amber “…”</b> — a lookup is running;</li>
 *   <li><b>grey</b> — the page had no questions / the answer failed.</li>
 * </ul>
 *
 * <p>All textual feedback goes through toasts, so the button itself never covers page content.</p>
 */
public final class Ball {

    private static final int COLOR_AUTO = 0xFF059669;
    private static final int COLOR_MANUAL = 0xFF2563EB;
    private static final int COLOR_BUSY = 0xFFF59E0B;
    private static final int COLOR_IDLE = 0xFF64748B;

    private static WeakReference<Activity> owner = new WeakReference<>(null);
    private static TextView handle;
    private static int color = COLOR_AUTO;

    private static float downRawX;
    private static float downRawY;
    private static int startRight;
    private static int startTop;

    private Ball() {
    }

    public interface Action {
        void onTap();

        void onLongPress();
    }

    private static volatile Action action;

    public static void setAction(Action a) {
        action = a;
    }

    /** Attaches the button to the given activity if the feature is enabled. */
    public static void attach(Activity activity) {
        if (activity == null) {
            return;
        }
        Cfg.Snapshot cfg = Cfg.get();
        if (!cfg.answer || !cfg.ball) {
            return;
        }
        Activity prev = owner.get();
        if (prev == activity && handle != null && handle.getParent() != null) {
            paint(activity, color, false);
            return;
        }
        detach();
        try {
            View decor = activity.getWindow() == null ? null : activity.getWindow().peekDecorView();
            if (!(decor instanceof ViewGroup)) {
                decor = activity.findViewById(android.R.id.content);
            }
            if (!(decor instanceof ViewGroup)) {
                return;
            }
            TextView button = new TextView(activity);
            button.setText("答");
            button.setTextColor(Color.WHITE);
            button.setTextSize(15f);
            button.setGravity(Gravity.CENTER);
            button.setTypeface(button.getTypeface(), android.graphics.Typeface.BOLD);
            button.setElevation(dp(activity, 6));
            int size = dp(activity, 48);
            button.setWidth(size);
            button.setHeight(size);
            button.setAlpha(0.92f);

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
            lp.gravity = Gravity.END | Gravity.BOTTOM;
            lp.rightMargin = dp(activity, 8);
            lp.bottomMargin = dp(activity, 180);
            button.setLayoutParams(lp);
            ((ViewGroup) decor).addView(button);

            handle = button;
            color = Engine.isAutoMode() ? COLOR_AUTO : COLOR_MANUAL;
            paint(activity, color, false);
            bindTouch(activity, button, lp);
            owner = new WeakReference<>(activity);
            Logx.i("ball attached to " + activity.getClass().getSimpleName()
                    + " mode=" + (Engine.isAutoMode() ? "auto" : "manual"));
        } catch (Throwable t) {
            Logx.e("ball attach", t);
        }
    }

    /** Applies a circular background of the given colour. */
    private static void paint(Activity activity, int fill, boolean busy) {
        TextView h = handle;
        if (h == null) {
            return;
        }
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(fill);
        bg.setStroke(dp(activity, 1), 0x55FFFFFF);
        h.setBackground(bg);
        h.setText(busy ? "…" : "答");
        h.setTextSize(busy ? 17f : 15f);
        h.setAlpha(busy ? 1f : 0.92f);
        color = fill;
    }

    private static void bindTouch(final Activity activity, final TextView button,
                                  final FrameLayout.LayoutParams lp) {
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pulse(v);
                Action a = action;
                if (a != null) {
                    a.onTap();
                }
            }
        });
        button.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                pulse(v);
                Action a = action;
                if (a != null) {
                    a.onLongPress();
                }
                return true;
            }
        });
        button.setOnTouchListener(new View.OnTouchListener() {
            private boolean dragging;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        dragging = false;
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        startRight = lp.rightMargin;
                        startTop = lp.topMargin;
                        return false;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - downRawX;
                        float dy = event.getRawY() - downRawY;
                        if (!dragging && Math.hypot(dx, dy) < dp(activity, 12)) {
                            return false;
                        }
                        dragging = true;
                        lp.rightMargin = (int) Math.max(0, startRight - dx);
                        lp.topMargin = (int) (startTop + dy);
                        button.setLayoutParams(lp);
                        return true;
                    default:
                        dragging = false;
                        return false;
                }
            }
        });
    }

    private static void pulse(View v) {
        v.animate().scaleX(0.86f).scaleY(0.86f).setDuration(70).withEndAction(new Runnable() {
            @Override
            public void run() {
                v.animate().scaleX(1f).scaleY(1f).setDuration(110).start();
            }
        }).start();
    }

    public static void detach() {
        try {
            TextView h = handle;
            if (h != null) {
                ViewGroup parent = (ViewGroup) h.getParent();
                if (parent != null) {
                    parent.removeView(h);
                }
            }
        } catch (Throwable ignored) {
        }
        handle = null;
        owner = new WeakReference<>(null);
    }

    /** A lookup is running. */
    public static void busy() {
        Ui.run(new Runnable() {
            @Override
            public void run() {
                Activity a = owner.get();
                if (a != null) {
                    paint(a, COLOR_BUSY, true);
                }
            }
        });
    }

    /** Back to the mode colour. */
    public static void idle() {
        Ui.run(new Runnable() {
            @Override
            public void run() {
                Activity a = owner.get();
                if (a != null) {
                    paint(a, Engine.isAutoMode() ? COLOR_AUTO : COLOR_MANUAL, false);
                }
            }
        });
    }

    /** Briefly flashes grey when a page/question produced nothing. */
    public static void miss() {
        Ui.run(new Runnable() {
            @Override
            public void run() {
                Activity a = owner.get();
                if (a == null) {
                    return;
                }
                paint(a, COLOR_IDLE, false);
                Ui.later(new Runnable() {
                    @Override
                    public void run() {
                        idle();
                    }
                }, 900L);
            }
        });
    }

    /** Re-reads the mode colour after a switch; hides the button when it was turned off. */
    public static void refresh() {
        Cfg.Snapshot cfg = Cfg.get();
        if (!cfg.answer || !cfg.ball) {
            detach();
            return;
        }
        idle();
    }

    /** Hides/shows the button (it is kept out of the question screenshot). */
    public static void setVisible(final boolean visible) {
        Ui.run(new Runnable() {
            @Override
            public void run() {
                TextView h = handle;
                if (h != null) {
                    h.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
                }
            }
        });
    }

    /** Called from the activity lifecycle hook. */
    public static void onActivityResumed(Activity activity) {
        if (Cfg.get().answer && Cfg.get().ball) {
            attach(activity);
        }
    }

    public static void onActivityDestroyed(Activity activity) {
        Activity a = owner.get();
        if (a == activity) {
            detach();
        }
    }

    public static boolean attachedTo(Activity activity) {
        return owner.get() == activity && handle != null;
    }

    static int dp(Activity a, int value) {
        return (int) (value * a.getResources().getDisplayMetrics().density + 0.5f);
    }
}
