package app.specterscope;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.Button;
import android.widget.TextView;

/** Tiny helpers for building the UI in code (the app has no layout XML and no AndroidX dependency). */
final class Ui {
    private Ui() { }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static TextView text(Context c, String s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    static TextView mono(Context c, String s, float sp, int color) {
        TextView t = text(c, s, sp, color);
        t.setTypeface(Typeface.MONOSPACE);
        return t;
    }

    static Button button(Context c, String label, int accent) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTextColor(accent);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(c, 12), dp(c, 8), dp(c, 12), dp(c, 8));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xAA000000);
        g.setCornerRadius(dp(c, 20));
        g.setStroke(dp(c, 1), accent);
        b.setBackground(g);
        return b;
    }
}
