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
    private TextView previewPlaceholder, recBadge, statusText, fpsText, roomText, recDot, camInfoText;
    private Button btnToggle, btnMic, btnFlip, btnRecord;
    private boolean registered = false;
    private boolean micOn = true, recOn = false;
    private String camFacing = "BACK";

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
                        intent.getStringExtra(CameraService.EXTRA_MSG),
                        intent.getBooleanExtra(CameraService.EXTRA_MIC, micOn),
                        intent.getBooleanExtra(CameraService.EXTRA_REC, recOn),
                        intent.getStringExtra(CameraService.EXTRA_CAM));
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
        camInfoText = findViewById(R.id.camInfoText);
        btnToggle = findViewById(R.id.btnToggle);
        btnMic = findViewById(R.id.btnMic);
        btnFlip = findViewById(R.id.btnFlip);
        btnRecord = findViewById(R.id.btnRecord);

        roomText.setText(Prefs.getRoom(this));
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        btnToggle.setOnClickListener(v -> toggle());
        btnMic.setOnClickListener(v -> {
            if (!hasPermissions()) requestPerms();
            else serviceAction(CameraService.ACTION_TOGGLE_MIC);
        });
        btnFlip.setOnClickListener(v -> serviceAction(CameraService.ACTION_FLIP));
        btnRecord.setOnClickListener(v -> {
            if (recOn) serviceAction(CameraService.ACTION_REC_STOP);
            else serviceAction(CameraService.ACTION_REC_START);
        });
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

    private void serviceAction(String action) {
        if (!CameraService.isRunning()) {
            Toast.makeText(this, "Pehle START BROADCAST dabao", Toast.LENGTH_SHORT).show();
            return;
        }
        startService(new Intent(this, CameraService.class).setAction(action));
    }

    private void refreshUi() {
        if (CameraService.isRunning()) {
            applyState("LIVE", 0, "Streaming", micOn, recOn, camFacing);
        } else {
            applyState("OFFLINE", 0, "", micOn, false, camFacing);
        }
    }

    private void toggle() {
        if (CameraService.isRunning()) {
            Intent i = new Intent(this, CameraService.class).setAction(CameraService.ACTION_STOP);
            startService(i);
            previewImage.setImageBitmap(null);
            previewPlaceholder.setVisibility(View.VISIBLE);
            recBadge.setVisibility(View.GONE);
            recOn = false;
            applyState("OFFLINE", 0, "Broadcast band ho gaya", micOn, false, camFacing);
        } else {
            if (!hasPermissions()) {
                requestPerms();
                return;
            }
            Intent i = new Intent(this, CameraService.class).setAction(CameraService.ACTION_START);
            ContextCompat.startForegroundService(this, i);
            previewPlaceholder.setVisibility(View.VISIBLE);
            previewPlaceholder.setText("Camera aur mic shuru ho rahe hain…");
            applyState("STARTING", 0, "Camera shuru ho rahi hai…", micOn, false, camFacing);
        }
    }

    private boolean hasPermissions() {
        boolean cam = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
        boolean mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        boolean notif = Build.VERSION.SDK_INT < 33
                || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
        return cam && mic && notif;
    }

    private void requestPerms() {
        ActivityCompat.requestPermissions(this, new String[]{
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS
        }, REQ_PERMS);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_PERMS) {
            if (hasPermissions()) toggle();
            else Toast.makeText(this, "Camera, mic aur notification permission zaroori hain", Toast.LENGTH_SHORT).show();
        }
    }

    private void applyState(String state, int fps, String msg, boolean mic, boolean rec, String cam) {
        micOn = mic;
        recOn = rec;
        if (cam != null) camFacing = cam;
        fpsText.setText(fps + " fps");
        btnMic.setText(micOn ? "MIC ON" : "MIC OFF");
        btnRecord.setText(recOn ? "STOP REC" : "RECORD");
        camInfoText.setText(camFacing + " CAM · MIC " + (micOn ? "ON" : "OFF") + " · REC " + (recOn ? "ON" : "OFF"));

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
