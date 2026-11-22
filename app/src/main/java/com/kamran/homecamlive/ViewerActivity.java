package com.kamran.homecamlive;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class ViewerActivity extends AppCompatActivity {
    private EditText roomInput;
    private ImageView feedImage;
    private TextView feedPlaceholder, liveBadge, feedFps, connChip, viewerInfoText;
    private Button btnSound, btnFlipCam, btnRecord;

    private RelayClient relay;
    private final AudioPlayer audioPlayer = new AudioPlayer();
    private final Mp4Recorder recorder = new Mp4Recorder();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private int frameCount = 0;
    private long windowStart = 0;
    private boolean fullscreen = false;
    private boolean soundOn = true;
    private boolean pendingRecord = false;
    private boolean camMicOn = true;
    private String camFacing = "BACK";

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_viewer);

        roomInput = findViewById(R.id.roomInput);
        feedImage = findViewById(R.id.feedImage);
        feedPlaceholder = findViewById(R.id.feedPlaceholder);
        liveBadge = findViewById(R.id.liveBadge);
        feedFps = findViewById(R.id.feedFps);
        connChip = findViewById(R.id.connChip);
        viewerInfoText = findViewById(R.id.viewerInfoText);
        btnSound = findViewById(R.id.btnSound);
        btnFlipCam = findViewById(R.id.btnFlipCam);
        btnRecord = findViewById(R.id.btnRecord);

        roomInput.setText(Prefs.getRoom(this));
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnConnect).setOnClickListener(v -> connect());
        findViewById(R.id.btnFullscreen).setOnClickListener(v -> toggleFullscreen());

        btnSound.setOnClickListener(v -> {
            soundOn = !soundOn;
            if (soundOn) audioPlayer.start();
            else audioPlayer.stop();
            refreshInfo();
        });
        btnFlipCam.setOnClickListener(v -> {
            if (relay != null) relay.publish("control", Control.bytes(Control.FLIP));
            Toast.makeText(this, "Camera flip command bheja gaya", Toast.LENGTH_SHORT).show();
        });
        btnRecord.setOnClickListener(v -> toggleRecord());

        audioPlayer.start();
        connect();
    }

    private void connect() {
        String room = Prefs.sanitize(roomInput.getText().toString());
        roomInput.setText(room);
        Prefs.setRoom(this, room);

        if (relay != null) relay.disconnect();
        frameCount = 0;
        windowStart = 0;
        connChip.setText("CONNECTING");
        feedPlaceholder.setVisibility(View.VISIBLE);
        feedPlaceholder.setText("Relay se connect ho rahe hain…");
        liveBadge.setVisibility(View.GONE);

        relay = new RelayClient(room, "view", new RelayClient.Callback() {
            @Override public void onConnected(String broker) {
                ui.post(() -> {
                    connChip.setText("CONNECTED");
                    connChip.setTextColor(ContextCompat.getColor(ViewerActivity.this, R.color.accent));
                    feedPlaceholder.setText("Camera ka intezaar…");
                });
            }
            @Override public void onDisconnected(String reason) {
                ui.post(() -> {
                    connChip.setText("RECONNECTING");
                    liveBadge.setVisibility(View.GONE);
                });
            }
            @Override public void onMessage(String topic, byte[] payload) {
                if (topic.endsWith("/frame")) {
                    onFrame(payload);
                } else if (topic.endsWith("/audio")) {
                    if (soundOn) audioPlayer.feed(payload);
                    if (recorder.isRecording()) recorder.feedAudio(payload);
                } else if (topic.endsWith("/status")) {
                    onStatus(Control.text(payload));
                }
            }
            @Override public void onError(String message) {
                ui.post(() -> {
                    connChip.setText("OFFLINE");
                    feedPlaceholder.setVisibility(View.VISIBLE);
                    feedPlaceholder.setText(message);
                });
            }
        });
        relay.connect(true);
    }

    private void onFrame(byte[] jpeg) {
        final Bitmap bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        if (bmp == null) return;

        if (pendingRecord) {
            pendingRecord = false;
            try {
                final String name = recorder.start(this, bmp.getWidth(), bmp.getHeight());
                final byte[] nv21 = Mp4Recorder.bitmapToNv21(bmp, bmp.getWidth(), bmp.getHeight());
                recorder.feedVideo(nv21);
                ui.post(() -> {
                    btnRecord.setText("STOP REC");
                    Toast.makeText(this, "Recording shuru: " + name, Toast.LENGTH_SHORT).show();
                    refreshInfo();
                });
            } catch (Exception e) {
                ui.post(() -> Toast.makeText(this, "Recording start nahi hui", Toast.LENGTH_SHORT).show());
            }
        } else if (recorder.isRecording()) {
            recorder.feedVideo(Mp4Recorder.bitmapToNv21(bmp, bmp.getWidth(), bmp.getHeight()));
        }

        frameCount++;
        long now = System.currentTimeMillis();
        if (windowStart == 0) windowStart = now;
        if (now - windowStart >= 1000) {
            int fps = (int) (frameCount * 1000L / Math.max(1L, now - windowStart));
            frameCount = 0;
            windowStart = now;
            ui.post(() -> feedFps.setText(fps + " fps"));
        }
        ui.post(() -> {
            feedImage.setImageBitmap(bmp);
            feedPlaceholder.setVisibility(View.GONE);
            liveBadge.setVisibility(View.VISIBLE);
        });
    }

    private void onStatus(String cmd) {
        if (Control.CAM_FRONT.equals(cmd)) camFacing = "FRONT";
        else if (Control.CAM_BACK.equals(cmd)) camFacing = "BACK";
        else if (Control.MIC_ON.equals(cmd)) camMicOn = true;
        else if (Control.MIC_OFF.equals(cmd)) camMicOn = false;
        else if (Control.REC_ON.equals(cmd)) {
            ui.post(() -> { btnRecord.setText("STOP REC"); refreshInfo(); });
            return;
        } else if (Control.REC_OFF.equals(cmd)) {
            ui.post(() -> { if (!recorder.isRecording() && !pendingRecord) btnRecord.setText("RECORD"); refreshInfo(); });
            return;
        }
        ui.post(this::refreshInfo);
    }

    private void toggleRecord() {
        if (pendingRecord) {
            pendingRecord = false;
            if (relay != null) relay.publish("control", Control.bytes(Control.REC_STOP));
            btnRecord.setText("RECORD");
            return;
        }
        if (recorder.isRecording()) {
            btnRecord.setText("RECORD");
            if (relay != null) relay.publish("control", Control.bytes(Control.REC_STOP));
            new Thread(() -> {
                final String name = recorder.stop();
                ui.post(() -> {
                    Toast.makeText(this, "Saved: " + name, Toast.LENGTH_SHORT).show();
                    refreshInfo();
                });
            }, "save-rec").start();
        } else {
            pendingRecord = true;
            btnRecord.setText("STOP REC");
            if (relay != null) relay.publish("control", Control.bytes(Control.REC_START));
            Toast.makeText(this, "Recording agle frame se shuru hogi", Toast.LENGTH_SHORT).show();
        }
    }

    private void refreshInfo() {
        btnSound.setText(soundOn ? "SOUND ON" : "SOUND OFF");
        viewerInfoText.setText(camFacing + " CAM · MIC " + (camMicOn ? "ON" : "OFF") + " · REC "
                + (recorder.isRecording() || pendingRecord ? "ON" : "OFF"));
    }

    @SuppressWarnings("deprecation")
    private void toggleFullscreen() {
        fullscreen = !fullscreen;
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(fullscreen
                ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                : 0);
    }

    @Override protected void onDestroy() {
        if (relay != null) relay.disconnect();
        if (recorder.isRecording()) recorder.stop();
        audioPlayer.stop();
        super.onDestroy();
    }
}
