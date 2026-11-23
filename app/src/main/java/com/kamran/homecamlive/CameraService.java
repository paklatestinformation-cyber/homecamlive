package com.kamran.homecamlive;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;

import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.ByteArrayOutputStream;
import java.util.List;

/** Foreground CCTV broadcaster: audio, flip, dual camera, recording, time overlay. */
public class CameraService extends Service {
    private static final String TAG = "CameraService";

    public static final String ACTION_START = "com.kamran.homecamlive.START";
    public static final String ACTION_STOP = "com.kamran.homecamlive.STOP";
    public static final String ACTION_TOGGLE_MIC = "com.kamran.homecamlive.TOGGLE_MIC";
    public static final String ACTION_FLIP = "com.kamran.homecamlive.FLIP";
    public static final String ACTION_REC_START = "com.kamran.homecamlive.REC_START";
    public static final String ACTION_REC_STOP = "com.kamran.homecamlive.REC_STOP";
    public static final String ACTION_DUAL_START = "com.kamran.homecamlive.DUAL_START";
    public static final String ACTION_DUAL_STOP = "com.kamran.homecamlive.DUAL_STOP";

    public static final String FRAME_EVENT = "com.kamran.homecamlive.FRAME";
    public static final String BACK_FRAME_EVENT = "com.kamran.homecamlive.BACK_FRAME";
    public static final String FRONT_FRAME_EVENT = "com.kamran.homecamlive.FRONT_FRAME";
    public static final String STATUS_EVENT = "com.kamran.homecamlive.STATUS";
    public static final String EXTRA_FRAME = "frame";
    public static final String EXTRA_CAM = "cam";
    public static final String EXTRA_STATE = "state";
    public static final String EXTRA_FPS = "fps";
    public static final String EXTRA_MSG = "msg";
    public static final String EXTRA_MIC = "mic";
    public static final String EXTRA_REC = "rec";
    public static final String EXTRA_DUAL = "dual";

    private static final long FRAME_INTERVAL_MS = 200L; // ~5 fps
    private static final int NOTIF_ID = 771;
    private static final String CHANNEL_ID = "homecamlive-stream";

    private static volatile boolean RUNNING = false;
    public static boolean isRunning() { return RUNNING; }

    private RelayClient relay;
    private AudioCapture audioCapture;
    private Mp4Recorder recorder;
    private PowerManager.WakeLock wakeLock;

    private HandlerThread camThread;
    private Handler camHandler;

    private Camera camera;
    private SurfaceTexture surfaceTexture;
    private int currentCamId = -1;
    private String camFacing = "BACK";
    private int previewW = 0, previewH = 0;
    private int frameRotation = 0;
    private boolean mirrorFrame = false;

    private Camera secondCamera;
    private SurfaceTexture secondSurface;
    private int secondCamId = -1;
    private String secondFacing = "FRONT";
    private int secondW = 0, secondH = 0;
    private int secondRotation = 0;
    private boolean mirrorSecond = true;
    private boolean dualMode = false;

    private boolean micEnabled = true;
    private String room = "";
    private long lastPublish = 0;
    private long fpsWindow = 0;
    private int frameCounter = 0;
    private long lastPublish2 = 0;

    private final Camera.PreviewCallback frameCallback = (data, cam) -> {
        if (!RUNNING || previewW == 0) { cam.addCallbackBuffer(data); return; }
        countFps();
        if (System.currentTimeMillis() - lastPublish >= FRAME_INTERVAL_MS) {
            lastPublish = System.currentTimeMillis();
            handleFrame(data, previewW, previewH, frameRotation, mirrorFrame,
                    "BACK".equals(camFacing), true, cam);
        }
        cam.addCallbackBuffer(data);
    };

    private final Camera.PreviewCallback secondCallback = (data, cam) -> {
        if (!RUNNING || !dualMode || secondW == 0) { cam.addCallbackBuffer(data); return; }
        if (System.currentTimeMillis() - lastPublish2 >= FRAME_INTERVAL_MS) {
            lastPublish2 = System.currentTimeMillis();
            handleFrame(data, secondW, secondH, secondRotation, mirrorSecond,
                    "BACK".equals(secondFacing), false, cam);
        }
        cam.addCallbackBuffer(data);
    };

    private void countFps() {
        frameCounter++;
        long now = System.currentTimeMillis();
        if (fpsWindow == 0) fpsWindow = now;
        if (now - fpsWindow >= 1000) {
            int fps = (int) (frameCounter * 1000L / Math.max(1L, now - fpsWindow));
            frameCounter = 0;
            fpsWindow = now;
            sendStatus("LIVE", fps, "Streaming");
            updateNotification("Broadcasting to " + room + " · " + fps + " fps");
        }
    }

    private void handleFrame(byte[] nv21, int w, int h, int rotation, boolean mirror,
                             boolean isBack, boolean feedRecorder, Camera cam) {
        try {
            YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, w, h, null);
            ByteArrayOutputStream rawOut = new ByteArrayOutputStream();
            yuv.compressToJpeg(new Rect(0, 0, w, h), 90, rawOut);
            Bitmap raw = BitmapFactory.decodeByteArray(rawOut.toByteArray(), 0, rawOut.size());
            if (raw == null) return;

            Bitmap oriented = orient(raw, rotation, mirror);
            Bitmap stamped = TimeOverlay.stamp(oriented);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            stamped.compress(Bitmap.CompressFormat.JPEG, 45, out);
            byte[] jpeg = out.toByteArray();

            String label = isBack ? "BACK" : "FRONT";
            String topic = dualMode ? (isBack ? "frame_back" : "frame_front") : "frame";
            if (relay != null) relay.publish(topic, jpeg);

            String event = dualMode ? (isBack ? BACK_FRAME_EVENT : FRONT_FRAME_EVENT) : FRAME_EVENT;
            LocalBroadcastManager.getInstance(this).sendBroadcast(
                    new Intent(event).putExtra(EXTRA_FRAME, jpeg).putExtra(EXTRA_CAM, label));

            Mp4Recorder r = recorder;
            if (feedRecorder && r != null && r.isRecording()) {
                r.feedVideo(Mp4Recorder.bitmapToNv21(stamped));
            }
            if (oriented != raw) oriented.recycle();
            if (stamped != oriented) stamped.recycle();
        } catch (Throwable e) {
            Log.w(TAG, "frame encode failed", e);
        }
    }

    private static Bitmap orient(Bitmap src, int rotation, boolean mirror) {
        if (rotation == 0 && !mirror) return src;
        Matrix m = new Matrix();
        if (rotation != 0) m.postRotate(rotation);
        if (mirror) m.postScale(-1f, 1f, src.getWidth() / 2f, src.getHeight() / 2f);
        return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
    }

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
        } else if (ACTION_DUAL_START.equals(action)) {
            startDual();
        } else if (ACTION_DUAL_STOP.equals(action)) {
            stopDual();
        } else {
            startBroadcasting();
        }
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private synchronized void startBroadcasting() {
        if (RUNNING) return;
        RUNNING = true;
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
                else if (Control.DUAL_ON.equals(cmd)) startDual();
                else if (Control.DUAL_OFF.equals(cmd)) stopDual();
            }
            @Override public void onError(String message) {
                sendStatus("ERROR", 0, message);
                updateNotification("Relay error — retrying");
            }
        });
        relay.connect(true, relay.topic("control"));

        audioCapture = new AudioCapture(pcm -> {
            if (!RUNNING) return;
            if (micEnabled && relay != null) relay.publish("audio", pcm);
            Mp4Recorder r = recorder;
            if (r != null && r.isRecording()) r.feedAudio(pcm);
        });
        audioCapture.start();

        camHandler.post(() -> openCamera(findBackCamera()));
    }

    private int deviceRotationDegrees() {
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        int r = wm.getDefaultDisplay().getRotation();
        switch (r) {
            case Surface.ROTATION_90: return 90;
            case Surface.ROTATION_180: return 180;
            case Surface.ROTATION_270: return 270;
            default: return 0;
        }
    }

    private void applyOrientation(Camera cam, int camId, boolean front, boolean primary) {
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(camId, info);
        int device = deviceRotationDegrees();
        int frameRot;
        int display;
        if (front) {
            frameRot = (info.orientation + device) % 360;
            display = (360 - frameRot) % 360;
        } else {
            frameRot = (info.orientation - device + 360) % 360;
            display = frameRot;
        }
        try { cam.setDisplayOrientation(display); } catch (Exception ignored) { }
        if (primary) {
            frameRotation = frameRot;
            mirrorFrame = front;
        } else {
            secondRotation = frameRot;
            mirrorSecond = front;
        }
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
            applyOrientation(camera, camId, "FRONT".equals(camFacing), true);

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
            sendStatus(dualMode ? "LIVE" : "LIVE", 0, "Streaming");
            publishCameraStatus();
        } catch (Throwable e) {
            Log.e(TAG, "camera open failed", e);
            sendStatus("ERROR", 0, "Camera nahi khul payi: " + e.getMessage());
        }
    }

    private boolean openSecondCamera(int camId) {
        try {
            secondCamera = Camera.open(camId);
            secondCamId = camId;
            secondFacing = facingOf(camId);
            Camera.Parameters params = secondCamera.getParameters();
            Camera.Size size = pickSize(params.getSupportedPreviewSizes());
            secondW = size.width;
            secondH = size.height;
            params.setPreviewSize(secondW, secondH);
            params.setPreviewFormat(ImageFormat.NV21);
            secondCamera.setParameters(params);
            applyOrientation(secondCamera, camId, "FRONT".equals(secondFacing), false);

            int bufSize = secondW * secondH * ImageFormat.getBitsPerPixel(ImageFormat.NV21) / 8;
            secondCamera.addCallbackBuffer(new byte[bufSize]);
            secondCamera.addCallbackBuffer(new byte[bufSize]);
            secondCamera.setPreviewCallbackWithBuffer(secondCallback);

            secondSurface = new SurfaceTexture(0);
            secondSurface.setDefaultBufferSize(secondW, secondH);
            secondCamera.setPreviewTexture(secondSurface);
            secondCamera.startPreview();
            return true;
        } catch (Throwable e) {
            Log.w(TAG, "second camera open failed", e);
            closeSecondCamera();
            return false;
        }
    }

    private void closeSecondCamera() {
        try {
            if (secondCamera != null) {
                secondCamera.setPreviewCallback(null);
                secondCamera.stopPreview();
                secondCamera.release();
            }
        } catch (Exception ignored) { }
        try { if (secondSurface != null) secondSurface.release(); } catch (Exception ignored) { }
        secondCamera = null;
        secondSurface = null;
        secondCamId = -1;
        secondW = 0;
        secondH = 0;
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
        try { if (surfaceTexture != null) surfaceTexture.release(); } catch (Exception ignored) { }
        surfaceTexture = null;
    }

    private void startDual() {
        camHandler.post(() -> {
            if (!RUNNING || dualMode) return;
            int n = Camera.getNumberOfCameras();
            if (n < 2) {
                publishStatus(Control.DUAL_NO);
                return;
            }
            int back = -1, front = -1;
            for (int i = 0; i < n; i++) {
                Camera.CameraInfo info = new Camera.CameraInfo();
                Camera.getCameraInfo(i, info);
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK && back < 0) back = i;
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT && front < 0) front = i;
            }
            openCamera(back);
            if (openSecondCamera(front)) {
                dualMode = true;
                publishStatus(Control.DUAL_ON);
                sendStatus("LIVE", 0, "Dual view: FRONT + BACK");
            } else {
                publishStatus(Control.DUAL_FAIL);
                sendStatus("ERROR", 0, "Second camera open nahi hua — single view chal raha hai");
            }
        });
    }

    private void stopDual() {
        camHandler.post(() -> {
            if (!dualMode) return;
            dualMode = false;
            closeSecondCamera();
            openCamera(currentCamId);
            publishStatus(Control.DUAL_OFF);
            sendStatus("LIVE", 0, "Single view");
        });
    }

    private void flipCamera() {
        camHandler.post(() -> {
            if (!RUNNING) return;
            if (dualMode) {
                sendStatus("LIVE", 0, "Dual view me dono camera already dikhte hain");
                return;
            }
            int n = Camera.getNumberOfCameras();
            if (n < 2) return;
            openCamera((currentCamId + 1) % n);
        });
    }

    private String facingOf(int camId) {
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(camId, info);
        return info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT ? "FRONT" : "BACK";
    }

    private void publishStatus(String cmd) {
        if (relay != null) relay.publish("status", Control.bytes(cmd));
    }

    private void publishCameraStatus() {
        publishStatus("FRONT".equals(camFacing) ? Control.CAM_FRONT : Control.CAM_BACK);
    }

    private synchronized void toggleMic() {
        micEnabled = !micEnabled;
        publishStatus(micEnabled ? Control.MIC_ON : Control.MIC_OFF);
        sendStatus(RUNNING ? "LIVE" : "OFFLINE", 0, "");
    }

    private synchronized void startRecording() {
        Mp4Recorder current = recorder;
        if (current != null && current.isRecording()) return;
        int w = previewW, h = previewH;
        if (w <= 0 || h <= 0) return;
        if (frameRotation == 90 || frameRotation == 270) { int t = w; w = h; h = t; }
        Mp4Recorder r = new Mp4Recorder();
        try {
            String name = r.start(this, w, h);
            recorder = r;
            publishStatus(Control.REC_ON);
            sendStatus(RUNNING ? "LIVE" : "OFFLINE", 0, "Recording: " + name);
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
        publishStatus(Control.REC_OFF);
        sendStatus(RUNNING ? "LIVE" : "OFFLINE", 0,
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

    private synchronized void stopBroadcasting() {
        if (!RUNNING) return;
        RUNNING = false;
        dualMode = false;
        stopRecording();
        closeSecondCamera();
        closeCameraOnly();
        if (relay != null) { relay.disconnect(); relay = null; }
        if (audioCapture != null) { audioCapture.stop(); audioCapture = null; }
        try { if (wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) { }
        camHandler.removeCallbacksAndMessages(null);
        sendStatus("OFFLINE", 0, "Broadcast band ho gaya");
    }

    private void sendStatus(String state, int fps, String msg) {
        Intent i = new Intent(STATUS_EVENT)
                .putExtra(EXTRA_STATE, state)
                .putExtra(EXTRA_FPS, fps)
                .putExtra(EXTRA_MSG, msg)
                .putExtra(EXTRA_MIC, micEnabled)
                .putExtra(EXTRA_REC, recorder != null && recorder.isRecording())
                .putExtra(EXTRA_DUAL, dualMode)
                .putExtra(EXTRA_CAM, camFacing);
        LocalBroadcastManager.getInstance(this).sendBroadcast(i);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(getString(R.string.channel_desc));
        if (nm != null) nm.createNotificationChannel(ch);
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, CameraActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("Personal CCTV")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
        } catch (Exception ignored) { }
    }

    @Override public void onDestroy() {
        stopBroadcasting();
        if (camThread != null) camThread.quit();
        super.onDestroy();
    }
}
