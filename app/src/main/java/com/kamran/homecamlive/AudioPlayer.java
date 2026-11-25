package com.kamran.homecamlive;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

/** Viewer phone: incoming PCM chunks -> AudioTrack speaker. */
public class AudioPlayer {
    private static final int RATE = 16000;

    private AudioTrack track;
    private volatile boolean running = false;

    public synchronized void start() {
        if (running) return;
        try {
            int min = AudioTrack.getMinBufferSize(RATE,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int buf = Math.max(min > 0 ? min : 4096, RATE); // ~1 second
            track = new AudioTrack(AudioManager.STREAM_MUSIC, RATE,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    buf, AudioTrack.MODE_STREAM);
            track.play();
            running = true;
        } catch (Throwable ignored) {
            stop();
        }
    }

    public void feed(byte[] pcm) {
        AudioTrack t = track;
        if (!running || t == null || pcm == null) return;
        try {
            // Never block the MQTT callback: a full audio buffer drops this chunk.
            t.write(pcm, 0, pcm.length, AudioTrack.WRITE_NON_BLOCKING);
        } catch (Throwable ignored) { }
    }

    public synchronized void stop() {
        running = false;
        if (track != null) {
            try { track.stop(); } catch (Throwable ignored) { }
            try { track.release(); } catch (Throwable ignored) { }
            track = null;
        }
    }
}
