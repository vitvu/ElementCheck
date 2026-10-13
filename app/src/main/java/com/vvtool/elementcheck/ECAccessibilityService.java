package com.vvtool.elementcheck;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Point;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class ECAccessibilityService extends AccessibilityService {
    static final int PORT = 8765;

    private static volatile ECAccessibilityService instance;

    static ECAccessibilityService get() { return instance; }

    /** A capture: screen image + tree, with the live nodes kept for actions. */
    static final class Snapshot {
        long seq;
        byte[] jpeg;
        String json;
        List<AccessibilityNodeInfo> nodes;
    }

    final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Object captureLock = new Object();
    private final AtomicLong version = new AtomicLong();
    private final AtomicLong seq = new AtomicLong();
    private volatile Snapshot latest;
    private volatile String currentPkg = "";
    private volatile String currentActivity = "";
    private long lastScreenshotAt;

    private HttpServer server;
    private OverlayController overlay;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        server = new HttpServer(this, PORT);
        server.start();
        overlay = new OverlayController(this);
        if (Prefs.overlayEnabled(this)) overlay.show();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        version.incrementAndGet();
        if (e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence p = e.getPackageName(), c = e.getClassName();
            if (p != null && !getPackageName().contentEquals(p)) {
                currentPkg = p.toString();
                if (c != null) currentActivity = c.toString();
            }
        }
    }

    @Override public void onInterrupt() { }

    @Override
    public boolean onUnbind(Intent intent) {
        shutdown();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        shutdown();
        super.onDestroy();
    }

    private void shutdown() {
        if (instance == this) instance = null;
        if (server != null) { server.stop(); server = null; }
        if (overlay != null) { overlay.hide(); overlay = null; }
        Snapshot s = latest;
        latest = null;
        if (s != null) NodeDumper.recycle(s.nodes);
        worker.shutdownNow();
        if (shotExecutor != null) shotExecutor.shutdownNow();
    }

    // ---------------------------------------------------------------- overlay

    void setOverlayVisible(boolean visible) {
        Prefs.setOverlayEnabled(this, visible);
        if (overlay == null) return;
        main.post(() -> { if (visible) overlay.show(); else overlay.hide(); });
    }

    /** Called by the overlay's capture button. */
    void captureFromOverlay() {
        worker.execute(() -> {
            String msg;
            try {
                String[] res = captureAndSave();
                msg = "Saved " + res[0] + " (" + res[1] + " nodes)";
            } catch (Exception e) {
                msg = "Capture failed: " + e.getMessage();
            }
            final String m = msg;
            main.post(() -> Toast.makeText(this, m, Toast.LENGTH_SHORT).show());
        });
    }

    // ---------------------------------------------------------------- capture

    long version() { return version.get(); }
    String currentPackage() { return currentPkg; }
    Snapshot latest() { return latest; }

    static boolean canScreenshot() {
        return Build.VERSION.SDK_INT >= 30 || ProjectionService.isRunning();
    }

    /** Captures image + tree, stores it as the "latest" live snapshot. */
    Snapshot snapshot(boolean withImage, boolean hideOverlay, float scale, int quality) {
        synchronized (captureLock) {
            Bitmap bmp = withImage ? captureBitmap(hideOverlay) : null;
            NodeDumper.Result tree = NodeDumper.dump(this);
            Point size = realSize();

            byte[] jpeg = null;
            int imgW = 0, imgH = 0;
            if (bmp != null) {
                Bitmap out = bmp;
                if (scale > 0 && scale < 0.99f) {
                    out = Bitmap.createScaledBitmap(bmp, Math.max(1, Math.round(bmp.getWidth() * scale)),
                            Math.max(1, Math.round(bmp.getHeight() * scale)), true);
                    bmp.recycle();
                }
                imgW = out.getWidth();
                imgH = out.getHeight();
                ByteArrayOutputStream bos = new ByteArrayOutputStream(256 * 1024);
                out.compress(Bitmap.CompressFormat.JPEG, quality, bos);
                out.recycle();
                jpeg = bos.toByteArray();
            }

            Snapshot s = new Snapshot();
            s.seq = seq.incrementAndGet();
            s.jpeg = jpeg;
            s.nodes = tree.nodes;
            String pkg = tree.topPackage != null ? tree.topPackage : currentPkg;
            Json j = new Json(tree.windowsJson.length() + 512);
            j.beginObj()
                    .put("seq", s.seq)
                    .put("time", System.currentTimeMillis())
                    .put("w", size.x).put("h", size.y)
                    .put("rotation", rotation())
                    .put("density", getResources().getDisplayMetrics().densityDpi)
                    .put("pkg", pkg)
                    .put("activity", pkg.equals(currentPkg) ? currentActivity : "")
                    .put("sdk", Build.VERSION.SDK_INT)
                    .put("release", Build.VERSION.RELEASE)
                    .put("device", Build.MANUFACTURER + " " + Build.MODEL)
                    .put("hasImage", jpeg != null)
                    .put("imgW", imgW).put("imgH", imgH)
                    .put("nodeCount", tree.nodes.size())
                    .key("windows").raw(tree.windowsJson)
                    .endObj();
            s.json = j.toString();

            Snapshot old = latest;
            latest = s;
            if (old != null) NodeDumper.recycle(old.nodes);
            return s;
        }
    }

    /** Full-quality capture with the overlay hidden, saved to disk. Returns {id, nodeCount}. */
    String[] captureAndSave() throws Exception {
        if (!canScreenshot()) requestProjection();
        Snapshot s = snapshot(true, true, 1f, 90);
        String meta = new Json(256).beginObj()
                .put("seq", s.seq)
                .put("time", System.currentTimeMillis())
                .put("pkg", currentPkg)
                .put("nodes", s.nodes.size())
                .put("hasImage", s.jpeg != null)
                .endObj().toString();
        String id = CaptureStore.save(this, s.jpeg, s.json, meta);
        return new String[]{id, String.valueOf(s.nodes.size())};
    }

    void requestProjection() {
        if (Build.VERSION.SDK_INT >= 30 || ProjectionService.isRunning()) return;
        main.post(() -> startActivity(new Intent(this, ProjectionRequestActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
    }

    private Bitmap captureBitmap(boolean hideOverlay) {
        if (!canScreenshot()) return null;
        boolean hidden = false;
        if (hideOverlay && overlay != null && overlay.isShown()) {
            runOnMainSync(() -> overlay.setInvisible(true));
            hidden = true;
            sleep(120); // let the compositor drop the overlay frame
        }
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                Bitmap b = takeScreenshotSync();
                if (b == null) { sleep(350); b = takeScreenshotSync(); } // rate limit retry
                return b;
            }
            ProjectionService ps = ProjectionService.get();
            return ps == null ? null : ps.grab();
        } catch (Exception e) {
            return null;
        } finally {
            if (hidden) main.post(() -> { if (overlay != null) overlay.setInvisible(false); });
        }
    }

    @android.annotation.TargetApi(30)
    private Bitmap takeScreenshotSync() {
        // The platform rejects screenshots taken < ~333 ms apart.
        long wait = 340 - (System.currentTimeMillis() - lastScreenshotAt);
        if (wait > 0) sleep(wait);
        lastScreenshotAt = System.currentTimeMillis();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Bitmap> out = new AtomicReference<>();
        takeScreenshot(Display.DEFAULT_DISPLAY, screenshotExecutor(), new TakeScreenshotCallback() {
            @Override
            public void onSuccess(ScreenshotResult result) {
                HardwareBuffer hb = result.getHardwareBuffer();
                try {
                    Bitmap hw = Bitmap.wrapHardwareBuffer(hb, result.getColorSpace());
                    if (hw != null) {
                        out.set(hw.copy(Bitmap.Config.ARGB_8888, false));
                        hw.recycle();
                    }
                } finally {
                    hb.close();
                    latch.countDown();
                }
            }

            @Override
            public void onFailure(int errorCode) { latch.countDown(); }
        });
        try { latch.await(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        return out.get();
    }

    private ExecutorService shotExecutor;

    private synchronized ExecutorService screenshotExecutor() {
        if (shotExecutor == null) shotExecutor = Executors.newSingleThreadExecutor();
        return shotExecutor;
    }

    Point realSize() {
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        Point p = new Point();
        wm.getDefaultDisplay().getRealSize(p);
        return p;
    }

    private int rotation() {
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        switch (wm.getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_90: return 90;
            case Surface.ROTATION_180: return 180;
            case Surface.ROTATION_270: return 270;
            default: return 0;
        }
    }

    // ---------------------------------------------------------------- control

    /** Performs an action on node #i of the latest snapshot. */
    String nodeAction(long snapSeq, int i, String action, String text) {
        Snapshot s = latest;
        if (s == null || (snapSeq > 0 && s.seq != snapSeq)) return "stale snapshot, refresh first";
        if (i < 0 || i >= s.nodes.size()) return "bad node index";
        AccessibilityNodeInfo n = s.nodes.get(i);
        if (!n.refresh()) return "node no longer exists";
        boolean ok;
        switch (action) {
            case "click": ok = n.performAction(AccessibilityNodeInfo.ACTION_CLICK); break;
            case "longClick": ok = n.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK); break;
            case "focus": ok = n.performAction(AccessibilityNodeInfo.ACTION_FOCUS); break;
            case "a11yFocus": ok = n.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS); break;
            case "select": ok = n.performAction(AccessibilityNodeInfo.ACTION_SELECT); break;
            case "scrollForward": ok = n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); break;
            case "scrollBackward": ok = n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD); break;
            case "expand": ok = n.performAction(AccessibilityNodeInfo.ACTION_EXPAND); break;
            case "collapse": ok = n.performAction(AccessibilityNodeInfo.ACTION_COLLAPSE); break;
            case "dismiss": ok = n.performAction(AccessibilityNodeInfo.ACTION_DISMISS); break;
            case "copy": ok = n.performAction(AccessibilityNodeInfo.ACTION_COPY); break;
            case "paste": ok = n.performAction(AccessibilityNodeInfo.ACTION_PASTE); break;
            case "showOnScreen":
                ok = n.performAction(android.R.id.accessibilityActionShowOnScreen);
                break;
            case "setText": {
                Bundle b = new Bundle();
                b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text == null ? "" : text);
                ok = n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
                break;
            }
            case "tap": {
                android.graphics.Rect r = new android.graphics.Rect();
                n.getBoundsInScreen(r);
                return gesture("tap", r.centerX(), r.centerY(), 0, 0, 0);
            }
            default: return "unknown action";
        }
        return ok ? null : "action rejected by node";
    }

    String globalAction(String name) {
        int a;
        switch (name) {
            case "back": a = GLOBAL_ACTION_BACK; break;
            case "home": a = GLOBAL_ACTION_HOME; break;
            case "recents": a = GLOBAL_ACTION_RECENTS; break;
            case "notifications": a = GLOBAL_ACTION_NOTIFICATIONS; break;
            case "quickSettings": a = GLOBAL_ACTION_QUICK_SETTINGS; break;
            case "power": a = GLOBAL_ACTION_POWER_DIALOG; break;
            case "lock":
                if (Build.VERSION.SDK_INT < 28) return "requires Android 9+";
                a = GLOBAL_ACTION_LOCK_SCREEN;
                break;
            default: return "unknown global action";
        }
        return performGlobalAction(a) ? null : "rejected";
    }

    /** tap / long / swipe via dispatchGesture (Android 7+). */
    String gesture(String type, float x, float y, float x2, float y2, long duration) {
        if (Build.VERSION.SDK_INT < 24) return "gestures require Android 7+";
        Path p = new Path();
        p.moveTo(Math.max(0, x), Math.max(0, y));
        long d;
        switch (type) {
            case "tap": d = 50; break;
            case "long": d = duration > 0 ? duration : 700; break;
            case "swipe":
                p.lineTo(Math.max(0, x2), Math.max(0, y2));
                d = duration > 0 ? duration : 300;
                break;
            default: return "unknown gesture";
        }
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, d))
                .build();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean ok = new AtomicBoolean();
        boolean sent = dispatchGesture(g, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription gd) { ok.set(true); latch.countDown(); }
            @Override public void onCancelled(GestureDescription gd) { latch.countDown(); }
        }, main);
        if (!sent) return "gesture rejected";
        try { latch.await(d + 2000, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) { }
        return ok.get() ? null : "gesture cancelled";
    }

    // ---------------------------------------------------------------- utils

    private void runOnMainSync(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) { r.run(); return; }
        CountDownLatch l = new CountDownLatch(1);
        main.post(() -> { try { r.run(); } finally { l.countDown(); } });
        try { l.await(1, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }
}
