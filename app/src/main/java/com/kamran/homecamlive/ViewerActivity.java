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
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class ViewerActivity extends AppCompatActivity {
    private EditText roomInput;
    private ImageView feedImage, feedBackImage, feedFrontImage;
    private TextView feedPlaceholder, liveBadge, feedFps, connChip, viewerInfoText;
    private LinearLayout dualFeed;
    private Button btnSound, btnFlipCam, btnSplit, btnRecord;

    private RelayClient relay;
    private final AudioPlayer audioPlayer = new AudioPlayer();
    private final FullscreenView fullscreen = new FullscreenView();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService frameExec = Executors.newSingleThreadExecutor();
    private final AtomicReference<FrameData> pendingFrame = new AtomicReference<>();
    private final AtomicBoolean frameWorker = new AtomicBoolean(false);

    private int frameCount = 0;
    private long windowStart = 0;
    private volatile boolean soundOn = true;
    private volatile boolean pendingRecord = false;
    private volatile boolean remoteRec = false;
    private volatile int recToken = 0;
    private volatile boolean dualOn = false;
    private boolean camMicOn = true;
    private String camFacing = "BACK";
    private volatile Bitmap lastBack, lastFront;

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
        dualFeed = findViewById(R.id.dualFeed);
        feedBackImage = findViewById(R.id.feedBackImage);
        feedFrontImage = findViewById(R.id.feedFrontImage);
        btnSound = findViewById(R.id.btnSound);
        btnFlipCam = findViewById(R.id.btnFlipCam);
        btnSplit = findViewById(R.id.btnSplit);
        btnRecord = findViewById(R.id.btnRecord);

        roomInput.setText(Prefs.getRoom(this));
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnConnect).setOnClickListener(v -> connect());
        findViewById(R.id.btnFullscreen).setOnClickListener(v -> {
            Bitmap b = dualOn && lastBack != null ? lastBack : CamBitmap.of(feedImage);
            fullscreen.show(this, b);
        });

        btnSound.setOnClickListener(v -> {
            soundOn = !soundOn;
            if (soundOn) audioPlayer.start();
            else audioPlayer.stop();
            refreshInfo();
        });
        btnFlipCam.setOnClickListener(v -> {
            publish(Control.FLIP);
            Toast.makeText(this, "Camera flip command bheja gaya", Toast.LENGTH_SHORT).show();
        });
        btnSplit.setOnClickListener(v -> {
            dualOn = !dualOn;
            applyDualUi();
            publish(dualOn ? Control.DUAL_ON : Control.DUAL_OFF);
            Toast.makeText(this, dualOn ? "Split view on" : "Single view on", Toast.LENGTH_SHORT).show();
        });
        btnRecord.setOnClickListener(v -> toggleRecord());

        audioPlayer.start();
        connect();
    }

    private void publish(String cmd) {
        if (relay != null) relay.publish("control", Control.bytes(cmd));
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
                if (topic.endsWith("/frame") || topic.endsWith("/frame_back")
                        || topic.endsWith("/frame_front")) {
                    boolean back = !topic.endsWith("/frame_front");
                    enqueueFrame(payload, back);
                } else if (topic.endsWith("/audio")) {
                    if (soundOn) audioPlayer.feed(payload);
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

    /** MQTT callback must never decode/encode — keep only the newest frame and return. */
    private void enqueueFrame(byte[] jpeg, boolean back) {
        pendingFrame.set(new FrameData(jpeg, back));
        if (frameWorker.compareAndSet(false, true)) {
            try { frameExec.execute(this::drainFrames); }
            catch (Exception e) { frameWorker.set(false); }
        }
    }

    private void drainFrames() {
        while (true) {
            FrameData frame = pendingFrame.getAndSet(null);
            if (frame == null) {
                frameWorker.set(false);
                if (pendingFrame.get() != null && frameWorker.compareAndSet(false, true)) continue;
                return;
            }
            processFrame(frame);
        }
    }

    private void processFrame(FrameData frame) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap bmp = BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.length, opts);
        if (bmp == null) return;
        if (frame.back) lastBack = bmp; else lastFront = bmp;

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
            if (dualOn) {
                if (frame.back) feedBackImage.setImageBitmap(bmp);
                else feedFrontImage.setImageBitmap(bmp);
                dualFeed.setVisibility(View.VISIBLE);
                feedImage.setVisibility(View.GONE);
                feedPlaceholder.setVisibility(View.GONE);
            } else if (frame.back) {
                feedImage.setImageBitmap(bmp);
                feedImage.setVisibility(View.VISIBLE);
                dualFeed.setVisibility(View.GONE);
                feedPlaceholder.setVisibility(View.GONE);
            }
            liveBadge.setVisibility(View.VISIBLE);
            if (fullscreen.isShowing() && frame.back) fullscreen.setBitmap(bmp);
        });
    }

    private void onStatus(String cmd) {
        if (cmd != null && cmd.startsWith("SAVED:")) {
            ui.post(() -> Toast.makeText(this, "Camera saved: " + cmd.substring(6), Toast.LENGTH_LONG).show());
            return;
        }
        if (Control.CAM_FRONT.equals(cmd)) camFacing = "FRONT";
        else if (Control.CAM_BACK.equals(cmd)) camFacing = "BACK";
        else if (Control.MIC_ON.equals(cmd)) camMicOn = true;
        else if (Control.MIC_OFF.equals(cmd)) camMicOn = false;
        else if (Control.DUAL_ON.equals(cmd)) dualOn = true;
        else if (Control.DUAL_OFF.equals(cmd)) dualOn = false;
        else if (Control.REC_ON.equals(cmd)) {
            remoteRec = true;
            pendingRecord = false;
            recToken++;
            ui.post(() -> { btnRecord.setText("STOP REC"); refreshInfo(); });
            return;
        } else if (cmd != null && cmd.startsWith(Control.REC_FAIL)) {
            String reason = cmd.length() > Control.REC_FAIL.length()
                    ? cmd.substring(Control.REC_FAIL.length() + 1) : "";
            remoteRec = false;
            pendingRecord = false;
            recToken++;
            ui.post(() -> {
                btnRecord.setText("RECORD");
                Toast.makeText(this, reason.isEmpty()
                        ? "Camera recording start nahi hui"
                        : "Camera REC fail: " + reason, Toast.LENGTH_LONG).show();
                refreshInfo();
            });
            return;
        } else if (Control.REC_OFF.equals(cmd)) {
            remoteRec = false;
            pendingRecord = false;
            recToken++;
            ui.post(() -> { btnRecord.setText("RECORD"); refreshInfo(); });
            return;
        }
        ui.post(() -> { applyDualUi(); refreshInfo(); });
    }

    private void applyDualUi() {
        btnSplit.setText(dualOn ? "SINGLE" : "SPLIT");
        if (dualOn) {
            dualFeed.setVisibility(View.VISIBLE);
            feedImage.setVisibility(View.GONE);
        } else {
            dualFeed.setVisibility(View.GONE);
            feedImage.setVisibility(View.VISIBLE);
        }
    }

    private void toggleRecord() {
        if (dualOn) {
            Toast.makeText(this, "Split view me recording pehle SINGLE karo", Toast.LENGTH_SHORT).show();
            return;
        }
        if (remoteRec || pendingRecord) {
            remoteRec = false;
            pendingRecord = false;
            recToken++;
            btnRecord.setText("STOPPING…");
            publish(Control.REC_STOP);
            ui.postDelayed(() -> {
                if (!remoteRec && !pendingRecord) {
                    btnRecord.setText("RECORD");
                    refreshInfo();
                }
            }, 1500);
            return;
        }
        pendingRecord = true;
        btnRecord.setText("STARTING…");
        final int token = ++recToken;
        publish(Control.REC_START);
        Toast.makeText(this, "Recording camera phone me save hogi", Toast.LENGTH_SHORT).show();
        ui.postDelayed(() -> {
            if (recToken == token && pendingRecord) {
                pendingRecord = false;
                btnRecord.setText("RECORD");
                Toast.makeText(this, "Camera ne recording reply nahi diya", Toast.LENGTH_SHORT).show();
                refreshInfo();
            }
        }, 6000);
    }

    private void refreshInfo() {
        btnSound.setText(soundOn ? "SOUND ON" : "SOUND OFF");
        btnSplit.setText(dualOn ? "SINGLE" : "SPLIT");
        viewerInfoText.setText((dualOn ? "FRONT + BACK" : camFacing + " CAM")
                + " · MIC " + (camMicOn ? "ON" : "OFF") + " · REC "
                + (remoteRec || pendingRecord ? "ON" : "OFF"));
    }

    private static final class FrameData {
        final byte[] jpeg;
        final boolean back;

        FrameData(byte[] jpeg, boolean back) {
            this.jpeg = jpeg;
            this.back = back;
        }
    }

    static final class CamBitmap {
        static Bitmap of(ImageView iv) {
            if (iv == null || iv.getDrawable() == null) return null;
            android.graphics.drawable.Drawable d = iv.getDrawable();
            if (!(d instanceof android.graphics.drawable.BitmapDrawable)) return null;
            return ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
        }
    }

    @Override protected void onDestroy() {
        if (relay != null) relay.disconnect();
        pendingFrame.set(null);
        frameExec.shutdownNow();
        audioPlayer.stop();
        fullscreen.hide();
        super.onDestroy();
    }
}
