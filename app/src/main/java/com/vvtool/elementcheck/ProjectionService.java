package com.vvtool.elementcheck;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.nio.ByteBuffer;

/** MediaProjection capture: required on API 23-29, optional on 30+ for a smooth, change-driven stream. */
public class ProjectionService extends Service {
    static final String EXTRA_CODE = "code";
    static final String EXTRA_DATA = "data";
    static final String ACTION_STOP = "com.vvtool.elementcheck.STOP_STREAM";
    private static final String CHANNEL = "capture";
    private static final long IDLE_STOP_MS = 5 * 60 * 1000;

    private static final java.util.concurrent.atomic.AtomicInteger streamers = new java.util.concurrent.atomic.AtomicInteger();
    private static volatile long lastUse;

    private static volatile ProjectionService instance;

    private HandlerThread thread;
    private Handler handler;
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private Image latest;
    private int width, height;
    private final Object lock = new Object();

    static boolean isRunning() { return instance != null; }

    static ProjectionService get() { return instance; }

    static void stop(Context c) {
        c.stopService(new Intent(c, ProjectionService.class));
    }

    static void streamStarted() { streamers.incrementAndGet(); lastUse = System.currentTimeMillis(); }

    static void streamStopped() { streamers.decrementAndGet(); lastUse = System.currentTimeMillis(); }

    private final Runnable idleCheck = new Runnable() {
        @Override
        public void run() {
            if (streamers.get() <= 0 && System.currentTimeMillis() - lastUse > IDLE_STOP_MS) {
                stopSelf();
                return;
            }
            handler.postDelayed(this, 30_000);
        }
    };

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startAsForeground();
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent == null || projection != null) return START_NOT_STICKY;
        int code = intent.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED);
        Intent data = intent.getParcelableExtra(EXTRA_DATA);
        if (code != Activity.RESULT_OK || data == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(code, data);
        if (projection == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        thread = new HandlerThread("projection");
        thread.start();
        handler = new Handler(thread.getLooper());
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { stopSelf(); }
        }, handler);
        setupDisplay();
        lastUse = System.currentTimeMillis();
        handler.postDelayed(idleCheck, 30_000);
        instance = this;
        return START_NOT_STICKY;
    }

    private void startAsForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Screen capture", NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        int flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE;
        android.app.PendingIntent open = android.app.PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), flags);
        android.app.PendingIntent stop = android.app.PendingIntent.getService(this, 1,
                new Intent(this, ProjectionService.class).setAction(ACTION_STOP), flags);
        Notification n = b.setSmallIcon(R.drawable.ic_camera)
                .setContentTitle("Screen stream on")
                .setContentText("Stops automatically after 5 min idle")
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(1, n);
        }
    }

    private Point realSize() {
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        Point p = new Point();
        wm.getDefaultDisplay().getRealSize(p);
        return p;
    }

    private void setupDisplay() {
        Point size = realSize();
        width = size.x;
        height = size.y;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        ImageReader old = reader;
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        reader.setOnImageAvailableListener(r -> {
            Image img;
            try { img = r.acquireLatestImage(); } catch (Exception e) { return; }
            if (img == null) return;
            synchronized (lock) {
                if (latest != null) latest.close();
                latest = img;
            }
            Signals.changed();
        }, handler);
        if (display == null) {
            display = projection.createVirtualDisplay("ElementCheck", width, height, dm.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, handler);
        } else {
            display.resize(width, height, dm.densityDpi);
            display.setSurface(reader.getSurface());
        }
        if (old != null) {
            synchronized (lock) {
                if (latest != null) { latest.close(); latest = null; }
            }
            old.close();
        }
    }

    Bitmap grab() {
        lastUse = System.currentTimeMillis();
        Point size = realSize();
        if (size.x != width || size.y != height) {
            final Object done = new Object();
            synchronized (done) {
                handler.post(() -> { setupDisplay(); synchronized (done) { done.notifyAll(); } });
                try { done.wait(1000); } catch (InterruptedException ignored) { }
            }
            try { Thread.sleep(250); } catch (InterruptedException ignored) { }
        }
        synchronized (lock) {
            if (latest == null) return null;
            Image.Plane plane = latest.getPlanes()[0];
            ByteBuffer buf = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int w = latest.getWidth(), h = latest.getHeight();
            int paddedW = rowStride / pixelStride;
            Bitmap padded = Bitmap.createBitmap(paddedW, h, Bitmap.Config.ARGB_8888);
            buf.rewind();
            padded.copyPixelsFromBuffer(buf);
            if (paddedW == w) return padded;
            Bitmap out = Bitmap.createBitmap(padded, 0, 0, w, h);
            padded.recycle();
            return out;
        }
    }

    @Override
    public void onDestroy() {
        instance = null;
        if (handler != null) handler.removeCallbacks(idleCheck);
        synchronized (lock) {
            if (latest != null) { latest.close(); latest = null; }
        }
        if (display != null) display.release();
        if (reader != null) reader.close();
        if (projection != null) projection.stop();
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }
}
