package com.vvtool.elementcheck;

import android.util.Base64;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Binary frames: 1 byte type + JPEG (1 = snapshot image, 2 = stream frame).
 * Text frames: JSON messages.
 */
final class WebSocketSession {
    private static final int MAX_FRAME = 1 << 20;
    private static final byte IMG_SNAPSHOT = 1, IMG_STREAM = 2;

    private final ECAccessibilityService svc;
    private final InputStream in;
    private final OutputStream out;
    private final String key;
    private volatile boolean open = true;
    private volatile boolean streaming;
    private volatile boolean awaitingAck;
    private volatile float streamScale = 0.5f;
    private final Object ackLock = new Object();
    private Thread streamThread;
    private final java.util.concurrent.ExecutorService exec = java.util.concurrent.Executors.newSingleThreadExecutor();

    WebSocketSession(ECAccessibilityService svc, InputStream in, OutputStream out, String key) {
        this.svc = svc;
        this.in = in;
        this.out = out;
        this.key = key;
    }

    void run() throws IOException {
        handshake();
        try {
            while (open) {
                String msg = readMessage();
                if (msg == null) break;
                if (msg.contains("\"ack\"")) {
                    synchronized (ackLock) { awaitingAck = false; ackLock.notifyAll(); }
                } else {
                    exec.execute(() -> handle(msg));
                }
            }
        } finally {
            open = false;
            setStreaming(false);
            exec.shutdownNow();
        }
    }

    private void handshake() throws IOException {
        String accept;
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] d = sha1.digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII));
            accept = Base64.encodeToString(d, Base64.NO_WRAP);
        } catch (Exception e) {
            throw new IOException(e);
        }
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private void handle(String raw) {
        JSONObject m;
        try { m = new JSONObject(raw); } catch (Exception e) { return; }
        String t = m.optString("t");
        int id = m.optInt("id");
        String err = null;
        switch (t) {
            case "snap": {
                float scale = clamp((float) m.optDouble("scale", 0.6), 0.1f, 1f);
                int q = Math.max(10, Math.min(100, m.optInt("q", 80)));
                ECAccessibilityService.Snapshot s = svc.snapshot(false, scale, q);
                if (s.jpeg != null) sendBinary(IMG_SNAPSHOT, s.jpeg);
                sendText("{\"t\":\"snap\",\"id\":" + id + ",\"data\":" + s.json + "}");
                return;
            }
            case "gesture":
                Signals.burst(1500);
                err = svc.gesture(m.optString("type"),
                        (float) m.optDouble("x"), (float) m.optDouble("y"),
                        (float) m.optDouble("x2", 0), (float) m.optDouble("y2", 0),
                        m.optLong("duration", 0));
                break;
            case "global":
                Signals.burst(1500);
                err = svc.globalAction(m.optString("action"));
                break;
            case "text":
                Signals.burst(800);
                err = svc.inputText(m.optString("text"), m.optBoolean("enter"));
                break;
            case "stream":
                streamScale = clamp((float) m.optDouble("scale", 0.5), 0.1f, 1f);
                setStreaming(m.optBoolean("on"));
                if (streaming) Signals.burst(300);
                break;
            default:
                err = "unknown message";
        }
        sendText(err == null ? "{\"t\":\"ok\",\"id\":" + id + "}"
                : "{\"t\":\"err\",\"id\":" + id + ",\"error\":" + Json.str(err) + "}");
    }

    private synchronized void setStreaming(boolean on) {
        if (on == streaming) return;
        streaming = on;
        if (on) {
            streamThread = new Thread(this::streamLoop, "ws-stream");
            streamThread.start();
        } else if (streamThread != null) {
            synchronized (ackLock) { awaitingAck = false; ackLock.notifyAll(); }
            Signals.wake();
            streamThread = null;
        }
    }

    private void streamLoop() {
        long seen = -1;
        int lastHash = 0;
        while (streaming && open) {
            synchronized (ackLock) {
                long until = System.currentTimeMillis() + 3000;
                while (awaitingAck && streaming && open) {
                    long left = until - System.currentTimeMillis();
                    if (left <= 0) break;
                    try { ackLock.wait(left); } catch (InterruptedException e) { return; }
                }
            }
            long v;
            try { v = Signals.await(seen, 1000); } catch (InterruptedException e) { return; }
            if (!streaming || !open) break;
            if (v < 0) continue;
            seen = v;
            byte[] jpeg = svc.frame(streamScale, 70);
            if (jpeg == null) continue;
            int hash = java.util.Arrays.hashCode(jpeg);
            if (hash == lastHash) continue;
            lastHash = hash;
            awaitingAck = true;
            sendBinary(IMG_STREAM, jpeg);
        }
    }

    private String readMessage() throws IOException {
        java.io.ByteArrayOutputStream msg = new java.io.ByteArrayOutputStream();
        while (true) {
            int b0 = in.read();
            int b1 = in.read();
            if (b0 < 0 || b1 < 0) return null;
            boolean fin = (b0 & 0x80) != 0;
            int op = b0 & 0x0F;
            long len = b1 & 0x7F;
            if (len == 126) len = (readByte() << 8) | readByte();
            else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) len = (len << 8) | readByte();
            }
            if (len > MAX_FRAME) return null;
            byte[] mask = new byte[4];
            if ((b1 & 0x80) != 0) readFully(mask);
            byte[] payload = new byte[(int) len];
            readFully(payload);
            if ((b1 & 0x80) != 0) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];

            if (op == 0x8) {
                sendFrame(0x8, new byte[0], 0, 0);
                return null;
            }
            if (op == 0x9) { sendFrame(0xA, payload, 0, payload.length); continue; }
            if (op == 0xA) continue;
            msg.write(payload);
            if (fin) return msg.toString("UTF-8");
        }
    }

    private int readByte() throws IOException {
        int b = in.read();
        if (b < 0) throw new IOException("eof");
        return b;
    }

    private void readFully(byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) throw new IOException("eof");
            off += n;
        }
    }

    private void sendText(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        sendFrame(0x1, b, 0, b.length);
    }

    private void sendBinary(byte type, byte[] data) {
        byte[] b = new byte[data.length + 1];
        b[0] = type;
        System.arraycopy(data, 0, b, 1, data.length);
        sendFrame(0x2, b, 0, b.length);
    }

    private synchronized void sendFrame(int op, byte[] data, int off, int len) {
        if (!open && op != 0x8) return;
        try {
            byte[] head;
            if (len < 126) {
                head = new byte[]{(byte) (0x80 | op), (byte) len};
            } else if (len < 65536) {
                head = new byte[]{(byte) (0x80 | op), 126, (byte) (len >> 8), (byte) len};
            } else {
                head = new byte[10];
                head[0] = (byte) (0x80 | op);
                head[1] = 127;
                for (int i = 0; i < 8; i++) head[9 - i] = (byte) (((long) len) >> (8 * i));
            }
            out.write(head);
            out.write(data, off, len);
            out.flush();
        } catch (IOException e) {
            open = false;
            streaming = false;
        }
    }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
}
