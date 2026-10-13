package com.vvtool.elementcheck;

import android.content.res.AssetManager;
import android.os.Build;
import android.graphics.Point;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class HttpServer {
    private static final int MAX_BODY = 1 << 20;

    private final ECAccessibilityService svc;
    private final int port;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private volatile ServerSocket socket;
    private Thread acceptThread;

    HttpServer(ECAccessibilityService svc, int port) {
        this.svc = svc;
        this.port = port;
    }

    void start() {
        acceptThread = new Thread(this::acceptLoop, "http-accept");
        acceptThread.start();
    }

    void stop() {
        try { if (socket != null) socket.close(); } catch (IOException ignored) { }
        pool.shutdownNow();
    }

    private void acceptLoop() {
        for (int attempt = 0; attempt < 20 && socket == null; attempt++) {
            try {
                ServerSocket s = new ServerSocket();
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(port));
                socket = s;
            } catch (IOException e) {
                try { Thread.sleep(500); } catch (InterruptedException ie) { return; }
            }
        }
        ServerSocket s = socket;
        if (s == null) return;
        while (!s.isClosed()) {
            try {
                Socket c = s.accept();
                c.setSoTimeout(15000);
                c.setTcpNoDelay(true);
                pool.execute(() -> handle(c));
            } catch (SocketException e) {
                break;
            } catch (Exception ignored) { }
        }
    }


    private static final class Req {
        String method, path;
        final Map<String, String> query = new HashMap<>();
        final Map<String, String> headers = new HashMap<>();
        byte[] body = new byte[0];

        String q(String k, String def) { String v = query.get(k); return v == null ? def : v; }
        float qf(String k, float def) { try { return Float.parseFloat(q(k, "")); } catch (Exception e) { return def; } }
        int qi(String k, int def) { try { return Integer.parseInt(q(k, "")); } catch (Exception e) { return def; } }

        JSONObject json() {
            try { return new JSONObject(new String(body, StandardCharsets.UTF_8)); }
            catch (Exception e) { return new JSONObject(); }
        }
    }

    private void handle(Socket c) {
        try (Socket sock = c) {
            InputStream in = new BufferedInputStream(sock.getInputStream());
            OutputStream out = sock.getOutputStream();
            Req req = parse(in);
            if (req == null) return;
            String key = req.headers.get("sec-websocket-key");
            if (req.path.equals("/ws") && key != null) {
                sock.setSoTimeout(0);
                new WebSocketSession(svc, in, out, key).run();
                return;
            }
            route(req, out);
            out.flush();
        } catch (Exception ignored) { }
    }

    private static Req parse(InputStream in) throws IOException {
        String line = readLine(in);
        if (line == null || line.isEmpty()) return null;
        String[] parts = line.split(" ");
        if (parts.length < 2) return null;
        Req r = new Req();
        r.method = parts[0].toUpperCase(Locale.US);
        String target = parts[1];
        int qm = target.indexOf('?');
        r.path = decode(qm >= 0 ? target.substring(0, qm) : target);
        if (qm >= 0) {
            for (String kv : target.substring(qm + 1).split("&")) {
                if (kv.isEmpty()) continue;
                int eq = kv.indexOf('=');
                if (eq < 0) r.query.put(decode(kv), "");
                else r.query.put(decode(kv.substring(0, eq)), decode(kv.substring(eq + 1)));
            }
        }
        int len = 0;
        String h;
        while ((h = readLine(in)) != null && !h.isEmpty()) {
            int colon = h.indexOf(':');
            if (colon <= 0) continue;
            String name = h.substring(0, colon).trim().toLowerCase(Locale.US);
            String value = h.substring(colon + 1).trim();
            r.headers.put(name, value);
            if (name.equals("content-length")) {
                try { len = Integer.parseInt(value); } catch (NumberFormatException ignored) { }
            }
        }
        if (len > 0) {
            if (len > MAX_BODY) return null;
            byte[] b = new byte[len];
            int off = 0;
            while (off < len) {
                int n = in.read(b, off, len - off);
                if (n < 0) break;
                off += n;
            }
            r.body = b;
        }
        return r;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(128);
        int ch;
        while ((ch = in.read()) >= 0) {
            if (ch == '\n') break;
            if (ch != '\r') b.write(ch);
            if (b.size() > 16384) throw new IOException("line too long");
        }
        if (ch < 0 && b.size() == 0) return null;
        return b.toString("UTF-8");
    }

    private static String decode(String s) {
        try { return URLDecoder.decode(s, "UTF-8"); } catch (Exception e) { return s; }
    }


    private void route(Req r, OutputStream out) throws IOException {
        String p = r.path;
        if (r.method.equals("OPTIONS")) { send(out, 204, "text/plain", new byte[0]); return; }

        if (!p.startsWith("/api/")) { serveAsset(p, out); return; }

        switch (p) {
            case "/api/info": {
                Point s = svc.realSize();
                sendJson(out, new Json(512).beginObj()
                        .put("app", "ElementCheck")
                        .put("device", Build.MANUFACTURER + " " + Build.MODEL)
                        .put("sdk", Build.VERSION.SDK_INT)
                        .put("release", Build.VERSION.RELEASE)
                        .put("w", s.x).put("h", s.y)
                        .put("screenshot", ECAccessibilityService.canScreenshot())
                        .put("gestures", Build.VERSION.SDK_INT >= 24)
                        .endObj().toString());
                return;
            }
            case "/api/snapshot": {
                float scale = clampF(r.qf("scale", 1f), 0.1f, 1f);
                int quality = Math.max(10, Math.min(100, r.qi("q", 85)));
                sendJson(out, svc.snapshot(false, scale, quality).json);
                return;
            }
            case "/api/screen.jpg": {
                ECAccessibilityService.Snapshot s = svc.latest();
                if (s == null || s.jpeg == null) { sendError(out, 404, "no image"); return; }
                send(out, 200, "image/jpeg", s.jpeg);
                return;
            }
            case "/api/capture": {
                try {
                    ECAccessibilityService.Snapshot snap = svc.captureAndSave();
                    sendJson(out, "{\"nodes\":" + snap.nodeCount + "}");
                } catch (Exception e) {
                    sendError(out, 500, String.valueOf(e.getMessage()));
                }
                return;
            }
            case "/api/captures": {
                List<String> ids = CaptureStore.list(svc);
                Json j = new Json(ids.size() * 128 + 16).beginArr();
                for (String id : ids) {
                    File meta = new File(CaptureStore.dir(svc, id), CaptureStore.META);
                    String m = meta.exists() ? CaptureStore.readText(meta) : "{}";
                    j.beginObj().put("id", id).key("meta").raw(m).endObj();
                }
                sendJson(out, j.endArr().toString());
                return;
            }
            case "/api/nodes": {
                File f = new File(svc.getFilesDir(), "saved_nodes.json");
                if (r.method.equals("POST")) {
                    try {
                        new org.json.JSONArray(new String(r.body, StandardCharsets.UTF_8));
                        java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
                        fo.write(r.body);
                        fo.close();
                        result(out, null);
                    } catch (Exception e) {
                        sendError(out, 400, "invalid JSON array");
                    }
                    return;
                }
                sendJson(out, f.exists() ? CaptureStore.readText(f) : "[]");
                return;
            }
            case "/api/global":
                result(out, svc.globalAction(r.json().optString("action")));
                return;
            case "/api/gesture": {
                JSONObject b = r.json();
                result(out, svc.gesture(b.optString("type"),
                        (float) b.optDouble("x"), (float) b.optDouble("y"),
                        (float) b.optDouble("x2", 0), (float) b.optDouble("y2", 0),
                        b.optLong("duration", 0)));
                return;
            }
            default:
                break;
        }

        if (p.startsWith("/api/captures/")) {
            String[] seg = p.substring("/api/captures/".length()).split("/");
            File dir = CaptureStore.dir(svc, seg[0]);
            if (dir == null || !dir.isDirectory()) { sendError(out, 404, "not found"); return; }
            if (r.method.equals("DELETE")) {
                result(out, CaptureStore.delete(svc, seg[0]) ? null : "delete failed");
                return;
            }
            String name = seg.length > 1 ? seg[1] : CaptureStore.TREE;
            if (name.equals(CaptureStore.META) && r.method.equals("POST")) {
                result(out, CaptureStore.rename(svc, seg[0], r.json().optString("name")) ? null : "rename failed");
                return;
            }
            if (name.equals(CaptureStore.THUMB)) {
                byte[] t = CaptureStore.thumb(svc, seg[0]);
                if (t == null) sendError(out, 404, "not found");
                else send(out, 200, "image/jpeg", t);
                return;
            }
            if (!name.equals(CaptureStore.TREE) && !name.equals(CaptureStore.IMG) && !name.equals(CaptureStore.META)) {
                sendError(out, 404, "not found");
                return;
            }
            File f = new File(dir, name);
            if (!f.exists()) { sendError(out, 404, "not found"); return; }
            send(out, 200, name.endsWith(".jpg") ? "image/jpeg" : "application/json; charset=utf-8",
                    CaptureStore.readBytes(f));
            return;
        }
        sendError(out, 404, "unknown endpoint");
    }

    private void serveAsset(String p, OutputStream out) throws IOException {
        if (p.equals("/")) p = "/index.html";
        if (p.contains("..")) { sendError(out, 400, "bad path"); return; }
        String type;
        if (p.endsWith(".html")) type = "text/html; charset=utf-8";
        else if (p.endsWith(".js")) type = "application/javascript; charset=utf-8";
        else if (p.endsWith(".css")) type = "text/css; charset=utf-8";
        else { sendError(out, 404, "not found"); return; }
        AssetManager am = svc.getAssets();
        try (InputStream in = am.open("web" + p)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream(64 * 1024);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            send(out, 200, type, b.toByteArray());
        } catch (IOException e) {
            sendError(out, 404, "not found");
        }
    }


    private static void result(OutputStream out, String err) throws IOException {
        if (err == null) sendJson(out, "{\"ok\":true}");
        else sendJson(out, "{\"ok\":false,\"error\":" + Json.str(err) + "}");
    }

    private static void sendJson(OutputStream out, String json) throws IOException {
        send(out, 200, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendError(OutputStream out, int code, String msg) throws IOException {
        send(out, code, "application/json; charset=utf-8",
                ("{\"ok\":false,\"error\":" + Json.str(msg) + "}").getBytes(StandardCharsets.UTF_8));
    }

    private static void send(OutputStream out, int code, String type, byte[] body) throws IOException {
        String status;
        switch (code) {
            case 200: status = "OK"; break;
            case 204: status = "No Content"; break;
            case 400: status = "Bad Request"; break;
            case 404: status = "Not Found"; break;
            default: status = "Error";
        }
        String head = "HTTP/1.1 " + code + " " + status + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Access-Control-Allow-Methods: GET, POST, DELETE, OPTIONS\r\n"
                + "Access-Control-Allow-Headers: Content-Type\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
    }

    private static float clampF(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
}
