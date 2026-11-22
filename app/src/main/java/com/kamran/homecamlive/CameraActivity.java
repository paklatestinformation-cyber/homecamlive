package com.kamran.homecamlive;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

public class CameraActivity extends AppCompatActivity {
    private static final int REQ_PERMS = 21;

    private ImageView previewImage;
    private TextView previewPlaceholder, recBadge, statusText, fpsText, roomText, recDot;
    private Button btnToggle;
    private boolean registered = false;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            if (CameraService.FRAME_EVENT.equals(intent.getAction())) {
                byte[] frame = intent.getByteArrayExtra(CameraService.EXTRA_FRAME);
                if (frame != null) {
                    Bitmap bmp = BitmapFactory.decodeByteArray(frame, 0, frame.length);
                    if (bmp != null) {
                        previewImage.setImageBitmap(bmp);
                        previewPlaceholder.setVisibility(View.GONE);
                        recBadge.setVisibility(View.VISIBLE);
                    }
                }
            } else if (CameraService.STATUS_EVENT.equals(intent.getAction())) {
                applyState(intent.getStringExtra(CameraService.EXTRA_STATE),
                        intent.getIntExtra(CameraService.EXTRA_FPS, 0),
                        intent.getStringExtra(CameraService.EXTRA_MSG));
            }
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);

        previewImage = findViewById(R.id.previewImage);
        previewPlaceholder = findViewById(R.id.previewPlaceholder);
        recBadge = findViewById(R.id.recBadge);
        statusText = findViewById(R.id.statusText);
        fpsText = findViewById(R.id.fpsText);
        roomText = findViewById(R.id.roomText);
        recDot = findViewById(R.id.recDot);
        btnToggle = findViewById(R.id.btnToggle);

        roomText.setText(Prefs.getRoom(this));
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        btnToggle.setOnClickListener(v -> toggle());
        refreshUi();
    }

    @Override protected void onStart() {
        super.onStart();
        LocalBroadcastManager.getInstance(this).registerReceiver(receiver,
                new IntentFilter(CameraService.FRAME_EVENT));
        LocalBroadcastManager.getInstance(this).registerReceiver(receiver,
                new IntentFilter(CameraService.STATUS_EVENT));
        registered = true;
        refreshUi();
    }

    @Override protected void onStop() {
        if (registered) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver);
            registered = false;
        }
        super.onStop();
    }

    private void refreshUi() {
        if (CameraService.isRunning()) {
            applyState("LIVE", 0, "Streaming");
        } else {
            applyState("OFFLINE", 0, "");
        }
    }

    private void toggle() {
        if (CameraService.isRunning()) {
            Intent i = new Intent(this, CameraService.class).setAction(CameraService.ACTION_STOP);
            startService(i);
            previewImage.setImageBitmap(null);
            previewPlaceholder.setVisibility(View.VISIBLE);
            recBadge.setVisibility(View.GONE);
            applyState("OFFLINE", 0, "Broadcast band ho gaya");
        } else {
            if (!hasPermissions()) {
                ActivityCompat.requestPermissions(this, new String[]{
                        Manifest.permission.CAMERA,
                        Manifest.permission.POST_NOTIFICATIONS
                }, REQ_PERMS);
                return;
            }
            Intent i = new Intent(this, CameraService.class).setAction(CameraService.ACTION_START);
            ContextCompat.startForegroundService(this, i);
            previewPlaceholder.setVisibility(View.VISIBLE);
            previewPlaceholder.setText("Camera shuru ho rahi hai…");
            applyState("STARTING", 0, "Camera shuru ho rahi hai…");
        }
    }

    private boolean hasPermissions() {
        boolean cam = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
        boolean notif = Build.VERSION.SDK_INT < 33
                || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
        return cam && notif;
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_PERMS) {
            if (hasPermissions()) toggle();
            else Toast.makeText(this, "Camera aur notification permission zaroori hain", Toast.LENGTH_SHORT).show();
        }
    }

    private void applyState(String state, int fps, String msg) {
        fpsText.setText(fps + " fps");
        if ("LIVE".equals(state)) {
            statusText.setText("STREAMING");
            statusText.setTextColor(ContextCompat.getColor(this, R.color.accent));
            recDot.setText("● LIVE");
            recDot.setTextColor(ContextCompat.getColor(this, R.color.rec));
            recBadge.setVisibility(View.VISIBLE);
            btnToggle.setText("STOP BROADCAST");
        } else if ("STARTING".equals(state) || "RECONNECTING".equals(state)) {
            statusText.setText(state);
            statusText.setTextColor(ContextCompat.getColor(this, R.color.accent));
            recDot.setText("● ...");
            recDot.setTextColor(ContextCompat.getColor(this, R.color.accent));
            btnToggle.setText("STOP BROADCAST");
        } else if ("ERROR".equals(state)) {
            statusText.setText("ERROR");
            statusText.setTextColor(ContextCompat.getColor(this, R.color.rec));
            recDot.setText("● ERR");
            recDot.setTextColor(ContextCompat.getColor(this, R.color.rec));
            btnToggle.setText("STOP BROADCAST");
            if (msg != null) previewPlaceholder.setText(msg);
        } else {
            statusText.setText("OFFLINE");
            statusText.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
            recDot.setText("● OFF");
            recDot.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
            recBadge.setVisibility(View.GONE);
            btnToggle.setText("START BROADCAST");
            if (msg != null && !msg.isEmpty()) previewPlaceholder.setText(msg);
        }
    }
}
