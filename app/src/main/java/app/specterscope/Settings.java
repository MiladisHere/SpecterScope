package app.specterscope;

import android.content.Context;
import android.content.SharedPreferences;

/** Small wrapper around SharedPreferences. */
final class Settings {
    static final int[] THEMES = {0xFF39FF14, 0xFFFF3B30, 0xFF00E5FF, 0xFFFFB300};
    static final String[] THEME_NAMES = {"Green", "Red", "Cyan", "Amber"};

    private final SharedPreferences sp;

    Settings(Context c) {
        sp = c.getSharedPreferences("specter_scope", Context.MODE_PRIVATE);
    }

    int getInt(String k, int d) {
        return sp.getInt(k, d);
    }

    void putInt(String k, int v) {
        sp.edit().putInt(k, v).apply();
    }

    boolean getBool(String k, boolean d) {
        return sp.getBoolean(k, d);
    }

    void putBool(String k, boolean v) {
        sp.edit().putBoolean(k, v).apply();
    }

    int theme() {
        return THEMES[Math.max(0, Math.min(THEMES.length - 1, getInt("theme", 0)))];
    }
}
