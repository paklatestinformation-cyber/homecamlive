package com.kamran.homecamlive;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Foreground camera broadcaster — keeps streaming when the app is backgrounded. */
@SuppressWarnings("deprecation")
public class CameraService extends Service {
    private static final String TAG = "CameraService";

    public static final String ACTION_START = "com.kamran.homecamlive.START";
    public static final String ACTION_STOP = "com.kamran.homecamlive.STOP";
    public static final String FRAME_EVENT = "com.kamran.homecamlive.FRAME";
    public static final String STATUS_EVENT = "com.kamran.homecamlive.STATUS";
    public static final String EXTRA_FRAME = "frame";
    public static final String EXTRA_STATE = "state";
    public static final String EXTRA_FPS = "fps";
    public static final String EXTRA_MSG = "msg";

    private static final String CHANNEL_ID = "homecamlive";
    private static final int NOTIF_ID = 11;
    private static final long FRAME_INTERVAL_MS = 200; // ~5 fps

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    public static boolean isRunning() { return RUNNING.get(); }

    private HandlerThread camThread;
    private Handler camHandler;
    private Camera camera;
    private SurfaceTexture surfaceTexture;
    private RelayClient relay;
    private PowerManager.WakeLock wakeLock;
    private String room = "";
    private int previewW = 640, previewH = 480;
    private long lastPublish = 0, fpsWindow = 0;
    private int frameCounter = 0;

    private final Camera.PreviewCallback frameCallback = (data, cam) -> {
        if (!RUNNING.get()) { cam.addCallbackBuffer(data); return; }
        long now = System.currentTimeMillis();
        frameCounter++;
        if (fpsWindow == 0) fpsWindow = now;
        if (now - fpsWindow >= 1000) {
            int fps = (int) (frameCounter * 1000L / Math.max(1L, now - fpsWindow));
            frameCounter = 0;
            fpsWindow = now;
            sendStatus("LIVE", fps, "Streaming");
            updateNotification("Broadcasting to " + room + " · " + fps + " fps");
        }
        if (now - lastPublish >= FRAME_INTERVAL_MS) {
            lastPublish = now;
            try {
                YuvImage yuv = new YuvImage(data, ImageFormat.NV21, previewW, previewH, null);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                yuv.compressToJpeg(new Rect(0, 0, previewW, previewH), 45, out);
                byte[] jpeg = out.toByteArray();
                if (relay != null) relay.publish(jpeg);
                Intent frame = new Intent(FRAME_EVENT).putExtra(EXTRA_FRAME, jpeg);
                LocalBroadcastManager.getInstance(this).sendBroadcast(frame);
            } catch (Throwable e) {
                Log.w(TAG, "frame encode failed", e);
            }
        }
        cam.addCallbackBuffer(data);
    };

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HomeCamLive::stream");
        wakeLock.setReferenceCounted(false);
        camThread = new HandlerThread("homecam-capture");
        camThread.start();
        camHandler = new Handler(camThread.getLooper());
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopBroadcasting();
            stopSelf();
            return START_NOT_STICKY;
        }
        startBroadcasting();
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private synchronized void startBroadcasting() {
        if (RUNNING.get()) return;
        RUNNING.set(true);
        room = Prefs.sanitize(Prefs.getRoom(this));
        startForeground(NOTIF_ID, buildNotification("Connecting…"));
        try { wakeLock.acquire(); } catch (Exception ignored) { }
        sendStatus("STARTING", 0, "Camera shuru ho rahi hai…");

        relay = new RelayClient(room, "cam", new RelayClient.Callback() {
            @Override public void onConnected(String broker) {
                updateNotification("Broadcasting to " + room);
            }
            @Override public void onDisconnected(String reason) {
                sendStatus("RECONNECTING", 0, "Relay disconnect, retry…");
            }
            @Override public void onMessage(byte[] payload) { }
            @Override public void onError(String message) {
                sendStatus("ERROR", 0, message);
                updateNotification("Relay error — retrying");
            }
        });
        relay.connect(false);
        camHandler.post(this::openCamera);
    }

    private void openCamera() {
        try {
            int camId = findBackCamera();
            camera = Camera.open(camId);
            Camera.Parameters params = camera.getParameters();
            Camera.Size size = pickSize(params.getSupportedPreviewSizes());
            previewW = size.width;
            previewH = size.height;
            params.setPreviewSize(previewW, previewH);
            params.setPreviewFormat(ImageFormat.NV21);
            camera.setParameters(params);

            int bufSize = previewW * previewH * ImageFormat.getBitsPerPixel(ImageFormat.NV21) / 8;
            camera.addCallbackBuffer(new byte[bufSize]);
            camera.addCallbackBuffer(new byte[bufSize]);
            camera.setPreviewCallbackWithBuffer(frameCallback);

            surfaceTexture = new SurfaceTexture(0);
            surfaceTexture.setDefaultBufferSize(previewW, previewH);
            camera.setPreviewTexture(surfaceTexture);
            camera.startPreview();
            lastPublish = 0;
            fpsWindow = 0;
            sendStatus("LIVE", 0, "Streaming");
        } catch (Throwable e) {
            Log.e(TAG, "camera open failed", e);
            sendStatus("ERROR", 0, "Camera nahi khul payi: " + e.getMessage());
            updateNotification("Camera error");
        }
    }

    private int findBackCamera() {
        int count = Camera.getNumberOfCameras();
        for (int i = 0; i < count; i++) {
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) return i;
        }
        return 0;
    }

    private Camera.Size pickSize(List<Camera.Size> sizes) {
        if (sizes == null || sizes.isEmpty()) throw new IllegalStateException("No preview sizes");
        Camera.Size best = sizes.get(0);
        int bestDiff = Integer.MAX_VALUE;
        for (Camera.Size s : sizes) {
            int diff = Math.abs(s.width - 640) + Math.abs(s.height - 480);
            if (diff < bestDiff) { bestDiff = diff; best = s; }
        }
        return best;
    }

    private synchronized void stopBroadcasting() {
        if (!RUNNING.get()) return;
        RUNNING.set(false);
        if (relay != null) { relay.disconnect(); relay = null; }
        try {
            if (camera != null) {
                camera.setPreviewCallback(null);
                camera.stopPreview();
                camera.release();
                camera = null;
            }
        } catch (Exception ignored) { }
        if (surfaceTexture != null) {
            try { surfaceTexture.release(); } catch (Exception ignored) { }
            surfaceTexture = null;
        }
        try { if (wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) { }
        if (camHandler != null) camHandler.removeCallbacksAndMessages(null);
        sendStatus("OFFLINE", 0, "Broadcast stopped");
        stopForeground(true);
    }

    @Override public void onDestroy() {
        if (RUNNING.get()) stopBroadcasting();
        if (camThread != null) camThread.quit();
        super.onDestroy();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW);
            ch.setDescription(getString(R.string.channel_desc));
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, CameraActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, CameraService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_cam)
                .setContentTitle("HomeCamLive")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(openPi)
                .addAction(android.R.drawable.ic_media_pause, "Stop", stopPi)
                .build();
    }

    private void updateNotification(String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
        } catch (Exception ignored) { }
    }

    private void sendStatus(String state, int fps, String msg) {
        Intent i = new Intent(STATUS_EVENT)
                .putExtra(EXTRA_STATE, state)
                .putExtra(EXTRA_FPS, fps)
                .putExtra(EXTRA_MSG, msg);
        LocalBroadcastManager.getInstance(this).sendBroadcast(i);
    }
}
