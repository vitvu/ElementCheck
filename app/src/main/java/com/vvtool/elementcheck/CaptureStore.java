package com.vvtool.elementcheck;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

final class CaptureStore {
    static final String IMG = "screen.jpg";
    static final String TREE = "tree.json";
    static final String META = "meta.json";
    static final String THUMB = "thumb.jpg";
    private static final Pattern ID = Pattern.compile("[0-9_]{6,40}");

    private CaptureStore() { }

    static File root(Context c) {
        File d = new File(c.getFilesDir(), "captures");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    static boolean validId(String id) { return id != null && ID.matcher(id).matches(); }

    static File dir(Context c, String id) { return validId(id) ? new File(root(c), id) : null; }

    static String save(Context c, byte[] jpeg, String treeJson, String metaJson) throws IOException {
        String id = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
        File d = new File(root(c), id);
        if (!d.mkdirs()) throw new IOException("mkdir failed");
        if (jpeg != null) write(new File(d, IMG), jpeg);
        write(new File(d, TREE), treeJson.getBytes(StandardCharsets.UTF_8));
        write(new File(d, META), metaJson.getBytes(StandardCharsets.UTF_8));
        return id;
    }

    static List<String> list(Context c) {
        String[] names = root(c).list();
        List<String> out = new ArrayList<>();
        if (names == null) return out;
        Arrays.sort(names);
        for (int i = names.length - 1; i >= 0; i--) if (validId(names[i])) out.add(names[i]);
        return out;
    }

    static boolean delete(Context c, String id) {
        File d = dir(c, id);
        if (d == null || !d.isDirectory()) return false;
        File[] files = d.listFiles();
        if (files != null) for (File f : files) f.delete();
        return d.delete();
    }

    static boolean rename(Context c, String id, String name) {
        File d = dir(c, id);
        if (d == null || !d.isDirectory()) return false;
        try {
            File f = new File(d, META);
            org.json.JSONObject m = f.exists() ? new org.json.JSONObject(readText(f)) : new org.json.JSONObject();
            m.put("name", name == null ? "" : name.trim());
            write(f, m.toString().getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static String displayName(org.json.JSONObject meta, String id) {
        String n = meta.optString("name");
        if (!n.isEmpty()) return n;
        n = meta.optString("pkg");
        return n.isEmpty() ? id : n;
    }

    static synchronized byte[] thumb(Context c, String id) {
        File d = dir(c, id);
        if (d == null) return null;
        try {
            File t = new File(d, THUMB);
            if (t.exists()) return readBytes(t);
            File img = new File(d, IMG);
            if (!img.exists()) return null;
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inSampleSize = 6;
            android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeFile(img.getPath(), o);
            if (b == null) return null;
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, bos);
            b.recycle();
            byte[] data = bos.toByteArray();
            write(t, data);
            return data;
        } catch (IOException e) {
            return null;
        }
    }

    static String readText(File f) throws IOException {
        return new String(readBytes(f), StandardCharsets.UTF_8);
    }

    static byte[] readBytes(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) break;
                off += n;
            }
            return buf;
        }
    }

    private static void write(File f, byte[] data) throws IOException {
        try (OutputStream out = new FileOutputStream(f)) { out.write(data); }
    }
}
