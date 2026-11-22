package com.kamran.homecamlive;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

/** Camera phone mic -> 16 kHz mono PCM16 chunks. */
public class AudioCapture {
    public interface Listener { void onPcm(byte[] pcm); }

    private static final int RATE = 16000;
    private static final int FRAME_MS = 40;

    private final Listener listener;
    private final int frameBytes = RATE * FRAME_MS / 1000 * 2; // 1280
    private AudioRecord record;
    private Thread thread;
    private volatile boolean running = false;

    public AudioCapture(Listener listener) { this.listener = listener; }

    public synchronized void start() {
        if (running) return;
        int min = AudioRecord.getMinBufferSize(RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) return;
        int buf = Math.max(min, frameBytes * 4);
        try {
            record = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buf);
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                record.release();
                record = null;
                return;
            }
            record.startRecording();
            running = true;
            thread = new Thread(() -> {
                byte[] data = new byte[frameBytes];
                while (running && record != null) {
                    int n = record.read(data, 0, frameBytes);
                    if (n == frameBytes) listener.onPcm(data.clone());
                    else if (n < 0) break;
                }
            }, "homecam-audio-in");
            thread.start();
        } catch (Throwable ignored) {
            stop();
        }
    }

    public synchronized void stop() {
        running = false;
        if (thread != null) {
            try { thread.join(400); } catch (InterruptedException ignored) { }
            thread = null;
        }
        if (record != null) {
            try { record.stop(); } catch (Throwable ignored) { }
            record.release();
            record = null;
        }
    }
}
