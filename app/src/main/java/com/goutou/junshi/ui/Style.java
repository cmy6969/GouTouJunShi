package com.goutou.junshi.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;

/**
 * Flat, modern palette and surfaces (Material 3 flavour) without any dependency.
 *
 * <p>Everything is derived from the system light/dark mode so the screen matches the rest of the
 * phone: a neutral background, slightly raised cards, tonal input fields, one accent colour and
 * pill buttons.</p>
 */
public final class Style {

    private Style() {
    }

    public static boolean dark(Context c) {
        int mode = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    public static int bg(Context c) {
        return dark(c) ? 0xFF0E1116 : 0xFFF4F6FA;
    }

    public static int surface(Context c) {
        return dark(c) ? 0xFF171B22 : 0xFFFFFFFF;
    }

    public static int field(Context c) {
        return dark(c) ? 0xFF20252D : 0xFFF1F3F8;
    }

    public static int outline(Context c) {
        return dark(c) ? 0xFF2A3139 : 0xFFE1E5EE;
    }

    public static int text(Context c) {
        return dark(c) ? 0xFFEDEFF3 : 0xFF15171C;
    }

    public static int textDim(Context c) {
        return dark(c) ? 0x99EDEFF3 : 0x9915171C;
    }

    public static int accent(Context c) {
        return dark(c) ? 0xFF7CC4FF : 0xFF1F6FEB;
    }

    public static int onAccent() {
        return 0xFFFFFFFF;
    }

    /** Card surface with a large corner radius and a hairline outline. */
    public static GradientDrawable card(Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(surface(c));
        g.setCornerRadius(dp(c, 20));
        g.setStroke(dp(c, 1), outline(c));
        return g;
    }

    /** Tonal container used for text fields and inline panels. */
    public static GradientDrawable fieldBox(Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(field(c));
        g.setCornerRadius(dp(c, 14));
        g.setStroke(dp(c, 1), outline(c));
        return g;
    }

    /** Filled accent button. */
    public static GradientDrawable primary(Context c) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{accent(c), dark(c) ? 0xFF4A9BE0 : 0xFF1557B8});
        g.setCornerRadius(dp(c, 24));
        return g;
    }

    /** Outlined tonal button. */
    public static GradientDrawable secondary(Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(dark(c) ? 0xFF1E242C : 0xFFFFFFFF);
        g.setCornerRadius(dp(c, 24));
        g.setStroke(dp(c, 1), outline(c));
        return g;
    }

    public static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }
}
