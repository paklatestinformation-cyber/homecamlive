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

/** Foreground camera broadcaster with mic audio, remote flip and MP4 recording. */
@SuppressWarnings("deprecation")
public class CameraService extends Service {
    private static final String TAG = "CameraService";

    public static final String ACTION_START = "com.kamran.homecamlive.START";
    public static final String ACTION_STOP = "com.kamran.homecamlive.STOP";
    public static final String ACTION_TOGGLE_MIC = "com.kamran.homecamlive.TOGGLE_MIC";
    public static final String ACTION_FLIP = "com.kamran.homecamlive.FLIP";
    public static final String ACTION_REC_START = "com.kamran.homecamlive.REC_START";
    public static final String ACTION_REC_STOP = "com.kamran.homecamlive.REC_STOP";

    public static final String FRAME_EVENT = "com.kamran.homecamlive.FRAME";
    public static final String STATUS_EVENT = "com.kamran.homecamlive.STATUS";
    public static final String EXTRA_FRAME = "frame";
    public static final String EXTRA_STATE = "state";
    public static final String EXTRA_FPS = "fps";
    public static final String EXTRA_MSG = "msg";
    public static final String EXTRA_MIC = "mic";
    public static final String EXTRA_REC = "rec";
    public static final String EXTRA_CAM = "cam";

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
    private AudioCapture audioCapture;
    private Mp4Recorder recorder;
    private String room = "";
    private int previewW = 640, previewH = 480;
    private int currentCamId = -1;
    private String camFacing = "BACK";
    private volatile boolean micEnabled = true;
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
                Mp4Recorder r = recorder;
                if (r != null && r.isRecording()) r.feedVideo(data);
                YuvImage yuv = new YuvImage(data, ImageFormat.NV21, previewW, previewH, null);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                yuv.compressToJpeg(new Rect(0, 0, previewW, previewH), 45, out);
                byte[] jpeg = out.toByteArray();
                if (relay != null) relay.publish("frame", jpeg);
                LocalBroadcastManager.getInstance(this)
                        .sendBroadcast(new Intent(FRAME_EVENT).putExtra(EXTRA_FRAME, jpeg));
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
        } else if (ACTION_TOGGLE_MIC.equals(action)) {
            toggleMic();
        } else if (ACTION_FLIP.equals(action)) {
            flipCamera();
        } else if (ACTION_REC_START.equals(action)) {
            startRecording();
        } else if (ACTION_REC_STOP.equals(action)) {
            stopRecording();
        } else {
            startBroadcasting();
        }
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
            @Override public void onMessage(String topic, byte[] payload) {
                if (!topic.endsWith("/control")) return;
                String cmd = Control.text(payload);
                if (Control.FLIP.equals(cmd)) flipCamera();
                else if (Control.REC_START.equals(cmd)) startRecording();
                else if (Control.REC_STOP.equals(cmd)) stopRecording();
            }
            @Override public void onError(String message) {
                sendStatus("ERROR", 0, message);
                updateNotification("Relay error — retrying");
            }
        });
        relay.connect(true, relay.topic("control")); // sirf control suno; apne frame/audio wapas mat lo

        audioCapture = new AudioCapture(pcm -> {
            if (!RUNNING.get()) return;
            if (micEnabled && relay != null) relay.publish("audio", pcm);
            Mp4Recorder r = recorder;
            if (r != null && r.isRecording()) r.feedAudio(pcm);
        });
        audioCapture.start();

        camHandler.post(() -> openCamera(findBackCamera()));
    }

    private void openCamera(int camId) {
        closeCameraOnly();
        try {
            camera = Camera.open(camId);
            currentCamId = camId;
            camFacing = facingOf(camId);
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
            publishCameraStatus();
        } catch (Throwable e) {
            Log.e(TAG, "camera open failed", e);
            sendStatus("ERROR", 0, "Camera nahi khul payi: " + e.getMessage());
        }
    }

    private void closeCameraOnly() {
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
    }

    private void flipCamera() {
        camHandler.post(() -> {
            if (!RUNNING.get()) return;
            try {
                int n = Camera.getNumberOfCameras();
                if (n < 2) return;
                openCamera((currentCamId + 1) % n);
            } catch (Throwable e) { Log.w(TAG, "flip failed", e); }
        });
    }

    private String facingOf(int camId) {
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(camId, info);
        return info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT ? "FRONT" : "BACK";
    }

    private void publishCameraStatus() {
        if (relay == null) return;
        relay.publish("status", Control.bytes(
                "FRONT".equals(camFacing) ? Control.CAM_FRONT : Control.CAM_BACK));
    }

    private synchronized void toggleMic() {
        micEnabled = !micEnabled;
        if (relay != null) relay.publish("status",
                Control.bytes(micEnabled ? Control.MIC_ON : Control.MIC_OFF));
        sendStatus(RUNNING.get() ? "LIVE" : "OFFLINE", 0, "");
    }

    private synchronized void startRecording() {
        Mp4Recorder current = recorder;
        if (current != null && current.isRecording()) return;
        if (previewW <= 0 || previewH <= 0) return; // camera abhi open nahi hui
        Mp4Recorder r = new Mp4Recorder();
        try {
            String name = r.start(this, previewW, previewH);
            recorder = r;
            if (relay != null) relay.publish("status", Control.bytes(Control.REC_ON));
            sendStatus(RUNNING.get() ? "LIVE" : "OFFLINE", 0, "Recording: " + name);
        } catch (Exception e) {
            Log.e(TAG, "record start failed", e);
            sendStatus("ERROR", 0, "Recording start nahi hui: " + e.getMessage());
        }
    }

    private synchronized void stopRecording() {
        Mp4Recorder r = recorder;
        if (r == null) return;
        String name = r.stop();
        recorder = null;
        if (relay != null) relay.publish("status", Control.bytes(Control.REC_OFF));
        sendStatus(RUNNING.get() ? "LIVE" : "OFFLINE", 0,
                name == null ? "Recording saved" : "Saved: " + name);
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
        stopRecording();
        if (relay != null) { relay.disconnect(); relay = null; }
        if (audioCapture != null) { audioCapture.stop(); audioCapture = null; }
        closeCameraOnly();
        try { if (wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) { }
        if (camHandler != null) camHandler.removeCallbacksAndMessages(null);
        sendStatus("OFFLINE", 0, "Broadcast band ho gaya");
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
        Mp4Recorder r = recorder;
        Intent i = new Intent(STATUS_EVENT)
                .putExtra(EXTRA_STATE, state)
                .putExtra(EXTRA_FPS, fps)
                .putExtra(EXTRA_MSG, msg)
                .putExtra(EXTRA_MIC, micEnabled)
                .putExtra(EXTRA_REC, r != null && r.isRecording())
                .putExtra(EXTRA_CAM, camFacing);
        LocalBroadcastManager.getInstance(this).sendBroadcast(i);
    }
}
