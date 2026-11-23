package com.kamran.homecamlive;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    private EditText roomInput;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        roomInput = findViewById(R.id.roomInput);
        roomInput.setText(Prefs.getRoom(this));

        findViewById(R.id.cardCamera).setOnClickListener(v -> openCamera());
        findViewById(R.id.cardViewer).setOnClickListener(v -> openViewer());
        findViewById(R.id.navCamera).setOnClickListener(v -> openCamera());
        findViewById(R.id.navViewer).setOnClickListener(v -> openViewer());

        findViewById(R.id.btnRandom).setOnClickListener(v -> {
            String r = Prefs.randomRoom();
            roomInput.setText(r);
            Prefs.setRoom(this, r);
            Toast.makeText(this, "Naya Room ID: " + r, Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.btnCopy).setOnClickListener(v -> {
            String r = room();
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Room ID", r));
            Toast.makeText(this, "Room ID copy ho gaya", Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.btnAbout).setOnClickListener(v -> showAbout());
    }

    @Override protected void onResume() {
        super.onResume();
        String saved = Prefs.getRoom(this);
        if (!roomInput.getText().toString().equals(saved)) roomInput.setText(saved);
    }

    private String room() {
        String r = Prefs.sanitize(roomInput.getText().toString());
        roomInput.setText(r);
        Prefs.setRoom(this, r);
        return r;
    }

    private void openCamera() {
        room();
        startActivity(new Intent(this, CameraActivity.class));
    }

    private void openViewer() {
        room();
        startActivity(new Intent(this, ViewerActivity.class));
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle("Personal CCTV")
                .setMessage("Ek phone ko CCTV banayein aur doosre se live dekhein — kahin se bhi, mobile data par bhi.\n\nDeveloper: Muhammad Kamran Malik\nContact: +923135275771")
                .setPositiveButton("OK", null)
                .show();
    }
}
