package com.vvtool.elementcheck;

/** Minimal allocation-light JSON writer (faster than org.json for large trees). */
final class Json {
    private final StringBuilder sb;
    private boolean needComma;

    Json(int capacity) { sb = new StringBuilder(capacity); }

    Json beginObj() { sep(); sb.append('{'); needComma = false; return this; }
    Json endObj() { sb.append('}'); needComma = true; return this; }
    Json beginArr() { sep(); sb.append('['); needComma = false; return this; }
    Json endArr() { sb.append(']'); needComma = true; return this; }

    Json key(String k) { sep(); quote(k); sb.append(':'); needComma = false; return this; }

    Json val(CharSequence s) {
        sep();
        if (s == null) sb.append("null"); else quote(s);
        needComma = true;
        return this;
    }
    Json val(long v) { sep(); sb.append(v); needComma = true; return this; }
    Json val(double v) { sep(); sb.append(v); needComma = true; return this; }
    Json val(boolean v) { sep(); sb.append(v); needComma = true; return this; }
    Json raw(String json) { sep(); sb.append(json); needComma = true; return this; }

    Json put(String k, CharSequence v) { return key(k).val(v); }
    Json put(String k, long v) { return key(k).val(v); }
    Json put(String k, double v) { return key(k).val(v); }
    Json put(String k, boolean v) { return key(k).val(v); }

    /** Writes the pair only when the value is non-empty. */
    Json opt(String k, CharSequence v) {
        if (v != null && v.length() > 0) put(k, v);
        return this;
    }

    private void sep() { if (needComma) sb.append(','); }

    private void quote(CharSequence s) {
        sb.append('"');
        for (int i = 0, n = s.length(); i < n; i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    static String str(String s) {
        Json j = new Json(s == null ? 4 : s.length() + 8);
        j.val(s);
        return j.toString();
    }

    @Override public String toString() { return sb.toString(); }
}
