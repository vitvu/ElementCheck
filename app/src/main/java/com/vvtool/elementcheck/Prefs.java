package com.vvtool.elementcheck;

import android.content.Context;
import android.content.SharedPreferences;

final class Prefs {
    private Prefs() { }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("ec", Context.MODE_PRIVATE);
    }

    static boolean overlayEnabled(Context c) { return sp(c).getBoolean("overlay", true); }
    static void setOverlayEnabled(Context c, boolean v) { sp(c).edit().putBoolean("overlay", v).apply(); }

    static boolean serverEnabled(Context c) { return sp(c).getBoolean("server", true); }
    static void setServerEnabled(Context c, boolean v) { sp(c).edit().putBoolean("server", v).apply(); }

    static int overlayX(Context c) { return sp(c).getInt("ox", 0); }
    static int overlayY(Context c) { return sp(c).getInt("oy", 300); }
    static void setOverlayPos(Context c, int x, int y) { sp(c).edit().putInt("ox", x).putInt("oy", y).apply(); }
}
