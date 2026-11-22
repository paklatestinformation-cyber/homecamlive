package com.kamran.homecamlive;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class ViewerActivity extends AppCompatActivity {
    private EditText roomInput;
    private ImageView feedImage;
    private TextView feedPlaceholder, liveBadge, feedFps, connChip;
    private RelayClient relay;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private int frameCount = 0;
    private long windowStart = 0;
    private boolean fullscreen = false;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_viewer);

        roomInput = findViewById(R.id.roomInput);
        feedImage = findViewById(R.id.feedImage);
        feedPlaceholder = findViewById(R.id.feedPlaceholder);
        liveBadge = findViewById(R.id.liveBadge);
        feedFps = findViewById(R.id.feedFps);
        connChip = findViewById(R.id.connChip);

        roomInput.setText(Prefs.getRoom(this));
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnConnect).setOnClickListener(v -> connect());
        findViewById(R.id.btnFullscreen).setOnClickListener(v -> toggleFullscreen());
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
            @Override public void onMessage(byte[] payload) {
                final Bitmap bmp = BitmapFactory.decodeByteArray(payload, 0, payload.length);
                if (bmp == null) return;
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
        super.onDestroy();
    }
}
