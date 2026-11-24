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
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

public class CameraActivity extends AppCompatActivity {
    private static final int REQ_PERMS = 21;

    private ImageView previewImage, camBackImage, camFrontImage;
    private TextView previewPlaceholder, recBadge, statusText, fpsText, roomText, recDot, camInfoText;
    private LinearLayout dualPreview;
    private Button btnToggle, btnMic, btnFlip, btnDual, btnRecord;
    private final FullscreenView fullscreen = new FullscreenView();

    private boolean registered = false;
    private boolean micOn = true, recOn = false, dualOn = false;
    private volatile String recPending = null;
    private String camFacing = "BACK";

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            String action = intent.getAction();
            byte[] jpeg = intent.getByteArrayExtra(CameraService.EXTRA_FRAME);
            if (jpeg == null) return;
            Bitmap bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
            if (bmp == null) return;

            if (CameraService.BACK_FRAME_EVENT.equals(action)) {
                dualOn = true;
                dualPreview.setVisibility(View.VISIBLE);
                previewImage.setVisibility(View.GONE);
                previewPlaceholder.setVisibility(View.GONE);
                camBackImage.setImageBitmap(bmp);
                if (fullscreen.isShowing()) fullscreen.setBitmap(bmp);
            } else if (CameraService.FRONT_FRAME_EVENT.equals(action)) {
                dualOn = true;
                camFrontImage.setImageBitmap(bmp);
            } else if (CameraService.FRAME_EVENT.equals(action)) {
                previewImage.setImageBitmap(bmp);
                previewImage.setVisibility(View.VISIBLE);
                previewPlaceholder.setVisibility(View.GONE);
                fullscreen.setBitmap(bmp);
            }
            recBadge.setVisibility(View.VISIBLE);
            refreshInfo();
        }
    };

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null || !CameraService.STATUS_EVENT.equals(intent.getAction())) return;
            recPending = null;
            applyState(intent.getStringExtra(CameraService.EXTRA_STATE),
                    intent.getIntExtra(CameraService.EXTRA_FPS, 0),
                    intent.getStringExtra(CameraService.EXTRA_MSG),
                    intent.getBooleanExtra(CameraService.EXTRA_MIC, micOn),
                    intent.getBooleanExtra(CameraService.EXTRA_REC, recOn),
                    intent.getBooleanExtra(CameraService.EXTRA_DUAL, dualOn),
                    intent.getStringExtra(CameraService.EXTRA_CAM));
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
        dualPreview = findViewById(R.id.dualPreview);
        camBackImage = findViewById(R.id.camBackImage);
        camFrontImage = findViewById(R.id.camFrontImage);
        btnToggle = findViewById(R.id.btnToggle);
        btnMic = findViewById(R.id.btnMic);
        btnFlip = findViewById(R.id.btnFlip);
        btnDual = findViewById(R.id.btnDual);
        btnRecord = findViewById(R.id.btnRecord);

        roomText.setText(Prefs.getRoom(this));
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        btnToggle.setOnClickListener(v -> toggle());
        btnMic.setOnClickListener(v -> {
            if (!hasPermissions()) requestPerms();
            else serviceAction(CameraService.ACTION_TOGGLE_MIC);
        });
        btnFlip.setOnClickListener(v -> serviceAction(CameraService.ACTION_FLIP));
        btnDual.setOnClickListener(v -> serviceAction(dualOn
                ? CameraService.ACTION_DUAL_STOP : CameraService.ACTION_DUAL_START));
        btnRecord.setOnClickListener(v -> {
            if (!CameraService.isRunning()) {
                Toast.makeText(this, "Pehle START BROADCAST dabao", Toast.LENGTH_SHORT).show();
                return;
            }
            if (recPending != null) return; // already waiting for service result
            boolean start = !recOn;
            recPending = start ? "STARTING…" : "STOPPING…";
            btnRecord.setText(recPending);
            serviceAction(start
                    ? CameraService.ACTION_REC_START : CameraService.ACTION_REC_STOP);
        });
        findViewById(R.id.btnCamFullscreen).setOnClickListener(v -> {
            Bitmap b = CamBitmap.capture(previewImage);
            if (b == null) b = CamBitmap.capture(camBackImage);
            fullscreen.show(this, b);
        });
        refreshInfo();
    }

    @Override protected void onStart() {
        super.onStart();
        LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(this);
        lbm.registerReceiver(receiver, new IntentFilter(CameraService.FRAME_EVENT));
        lbm.registerReceiver(receiver, new IntentFilter(CameraService.BACK_FRAME_EVENT));
        lbm.registerReceiver(receiver, new IntentFilter(CameraService.FRONT_FRAME_EVENT));
        lbm.registerReceiver(statusReceiver, new IntentFilter(CameraService.STATUS_EVENT));
        registered = true;
        refreshInfo();
    }

    @Override protected void onStop() {
        if (registered) {
            LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(this);
            lbm.unregisterReceiver(receiver);
            lbm.unregisterReceiver(statusReceiver);
            registered = false;
        }
        fullscreen.hide();
        super.onStop();
    }

    private void serviceAction(String action) {
        if (!CameraService.isRunning()) {
            Toast.makeText(this, "Pehle START BROADCAST dabao", Toast.LENGTH_SHORT).show();
            return;
        }
        startService(new Intent(this, CameraService.class).setAction(action));
    }

    private void refreshInfo() {
        if (CameraService.isRunning()) {
            applyState("LIVE", 0, "", micOn, recOn, dualOn, camFacing);
        } else {
            applyState("OFFLINE", 0, "", micOn, false, false, camFacing);
        }
    }

    private void toggle() {
        if (CameraService.isRunning()) {
            Intent i = new Intent(this, CameraService.class).setAction(CameraService.ACTION_STOP);
            startService(i);
            previewImage.setImageBitmap(null);
            camBackImage.setImageBitmap(null);
            camFrontImage.setImageBitmap(null);
            previewImage.setVisibility(View.VISIBLE);
            dualPreview.setVisibility(View.GONE);
            previewPlaceholder.setVisibility(View.VISIBLE);
            recBadge.setVisibility(View.GONE);
            recOn = false;
            recPending = null;
            dualOn = false;
            fullscreen.hide();
            applyState("OFFLINE", 0, "Broadcast band ho gaya", micOn, false, false, camFacing);
        } else {
            if (!hasPermissions()) {
                requestPerms();
                return;
            }
            Intent i = new Intent(this, CameraService.class).setAction(CameraService.ACTION_START);
            ContextCompat.startForegroundService(this, i);
            previewPlaceholder.setVisibility(View.VISIBLE);
            previewPlaceholder.setText("Camera aur mic shuru ho rahe hain…");
            applyState("STARTING", 0, "Camera shuru ho rahi hai…", micOn, false, false, camFacing);
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

    private void applyState(String state, int fps, String msg, boolean mic, boolean rec,
                            boolean dual, String cam) {
        if (msg != null && (msg.startsWith("SAVED:") || msg.startsWith("SAVE FAIL"))) {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        }
        micOn = mic;
        recOn = rec;
        dualOn = dual;
        if (cam != null) camFacing = cam;
        fpsText.setText(fps + " fps");
        btnMic.setText(micOn ? "MIC ON" : "MIC OFF");
        btnRecord.setText(recPending != null ? recPending : (recOn ? "STOP REC" : "RECORD"));
        btnDual.setText(dualOn ? "SINGLE" : "DUAL");
        if (dualOn) {
            dualPreview.setVisibility(View.VISIBLE);
            previewImage.setVisibility(View.GONE);
        } else {
            dualPreview.setVisibility(View.GONE);
            previewImage.setVisibility(View.VISIBLE);
        }
        camInfoText.setText((dualOn ? "FRONT + BACK" : camFacing + " CAM")
                + " · MIC " + (micOn ? "ON" : "OFF") + " · REC " + (recOn ? "ON" : "OFF"));

        if ("LIVE".equals(state)) {
            statusText.setText(dualOn ? "DUAL LIVE" : "STREAMING");
            statusText.setTextColor(ContextCompat.getColor(this, R.color.accent));
            recDot.setText("● LIVE");
            recDot.setTextColor(ContextCompat.getColor(this, R.color.rec));
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

    /** Small helper avoids an extra class for grabbing the currently shown frame. */
    static final class CamBitmap {
        static Bitmap capture(ImageView iv) {
            if (iv == null || iv.getDrawable() == null) return null;
            android.graphics.drawable.Drawable d = iv.getDrawable();
            if (!(d instanceof android.graphics.drawable.BitmapDrawable)) return null;
            return ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
        }
    }
}
