package com.kamran.homecamlive;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** MP4 (H.264 + AAC) written straight into MediaStore / public Movies — no temp copy. */
public class Mp4Recorder {
    private static final String TAG = "Mp4Recorder";

    private MediaCodec videoCodec, audioCodec;
    private MediaMuxer muxer;
    private FileOutputStream directOut;
    private ParcelFileDescriptor pfd;
    private Uri storeUri;
    private File directFile;
    private Context appCtx;
    private int videoTrack = -1, audioTrack = -1;
    private boolean muxerStarted = false, recording = false;
    private long startNs;
    private String outputName;

    private final MediaCodec.BufferInfo vInfo = new MediaCodec.BufferInfo();
    private final MediaCodec.BufferInfo aInfo = new MediaCodec.BufferInfo();

    public synchronized boolean isRecording() { return recording; }

    /** Returns the saved file name; file lands in Movies/HomeCamLive. */
    public synchronized String start(Context ctx, int w, int h) throws Exception {
        if (recording) return null;
        if (w <= 0 || h <= 0) throw new IOException("Invalid video size " + w + "x" + h);

        appCtx = ctx.getApplicationContext();
        startNs = System.nanoTime();
        videoTrack = -1;
        audioTrack = -1;
        muxerStarted = false;
        outputName = "PersonalCCTV_" +
                new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4";

        try {
            MediaFormat vf = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h);
            vf.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar);
            vf.setInteger(MediaFormat.KEY_BIT_RATE, 1500000);
            vf.setInteger(MediaFormat.KEY_FRAME_RATE, 15);
            vf.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
            videoCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            videoCodec.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            videoCodec.start();

            MediaFormat af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 16000, 1);
            af.setInteger(MediaFormat.KEY_BIT_RATE, 32000);
            audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            audioCodec.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            audioCodec.start();

            // Open the FINAL destination first. No temp file, no copy at stop().
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Video.Media.DISPLAY_NAME, outputName);
                cv.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                cv.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/HomeCamLive");
                cv.put(MediaStore.Video.Media.IS_PENDING, 1);
                storeUri = appCtx.getContentResolver().insert(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
                if (storeUri == null) throw new IOException("MediaStore insert failed");
                pfd = appCtx.getContentResolver().openFileDescriptor(storeUri, "w");
                if (pfd == null) throw new IOException("MediaStore openFileDescriptor failed");
                muxer = new MediaMuxer(pfd.getFileDescriptor(),
                        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            } else {
                File pub = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_MOVIES), "HomeCamLive");
                //noinspection ResultOfMethodCallIgnored
                pub.mkdirs();
                directFile = new File(pub, outputName);
                directOut = new FileOutputStream(directFile);
                muxer = new MediaMuxer(directOut.getFD(),
                        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            }

            // Prime both codecs so both tracks are added even if one input is late.
            primeBlankFrame(w, h);
            primeSilence();

            long deadline = System.currentTimeMillis() + 1500;
            while (!muxerStarted && System.currentTimeMillis() < deadline) {
                drain(videoCodec, vInfo, true, 60000);
                drain(audioCodec, aInfo, false, 60000);
            }
            if (!muxerStarted) throw new IOException("Encoder tracks not ready");

            recording = true;
            Log.i(TAG, "start " + outputName + " " + w + "x" + h +
                    (storeUri != null ? " uri=" + storeUri : " file=" + directFile));
            return outputName;
        } catch (Exception e) {
            Log.e(TAG, "start failed", e);
            cleanupAfterFailure();
            throw e;
        }
    }

    private void cleanupAfterFailure() {
        try { if (videoCodec != null) { videoCodec.stop(); videoCodec.release(); } } catch (Exception ignored) { }
        try { if (audioCodec != null) { audioCodec.stop(); audioCodec.release(); } } catch (Exception ignored) { }
        try { if (muxer != null) muxer.release(); } catch (Exception ignored) { }
        try { if (pfd != null) pfd.close(); } catch (Exception ignored) { }
        try { if (directOut != null) directOut.close(); } catch (Exception ignored) { }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && storeUri != null && appCtx != null) {
            try {
                ContentValues done = new ContentValues();
                done.put(MediaStore.Video.Media.IS_PENDING, 0);
                appCtx.getContentResolver().update(storeUri, done, null, null);
                appCtx.getContentResolver().delete(storeUri, null, null);
            } catch (Exception ignored) { }
        }
        videoCodec = null;
        audioCodec = null;
        muxer = null;
        pfd = null;
        directOut = null;
        storeUri = null;
        directFile = null;
        muxerStarted = false;
        videoTrack = -1;
        audioTrack = -1;
    }

    private void primeBlankFrame(int w, int h) {
        try {
            byte[] yuv = new byte[w * h * 3 / 2];
            int yLen = w * h;
            for (int i = 0; i < yLen; i++) yuv[i] = (byte) 16;
            for (int i = yLen; i < yuv.length; i++) yuv[i] = (byte) 128;
            int idx = videoCodec.dequeueInputBuffer(100000);
            if (idx >= 0) {
                ByteBuffer bb = videoCodec.getInputBuffer(idx);
                if (bb != null) {
                    byte[] nv12 = toNv12(yuv);
                    bb.clear();
                    bb.put(nv12);
                    videoCodec.queueInputBuffer(idx, 0, nv12.length, 0, 0);
                    drain(videoCodec, vInfo, true, 100000);
                }
            }
        } catch (Exception e) { Log.w(TAG, "primeBlankFrame", e); }
    }

    private void primeSilence() {
        try {
            int idx = audioCodec.dequeueInputBuffer(100000);
            if (idx >= 0) {
                ByteBuffer bb = audioCodec.getInputBuffer(idx);
                if (bb != null) {
                    byte[] silence = new byte[1280];
                    bb.clear();
                    bb.put(silence);
                    audioCodec.queueInputBuffer(idx, 0, silence.length, 0, 0);
                    drain(audioCodec, aInfo, false, 100000);
                }
            }
        } catch (Exception e) { Log.w(TAG, "primeSilence", e); }
    }

    public synchronized void feedVideo(byte[] nv21) {
        if (!recording || videoCodec == null || nv21 == null) return;
        try {
            int idx = videoCodec.dequeueInputBuffer(40000);
            if (idx < 0) return;
            byte[] nv12 = toNv12(nv21);
            ByteBuffer bb = videoCodec.getInputBuffer(idx);
            if (bb == null) return;
            bb.clear();
            bb.put(nv12);
            long pts = (System.nanoTime() - startNs) / 1000L;
            videoCodec.queueInputBuffer(idx, 0, nv12.length, pts, 0);
            drain(videoCodec, vInfo, true, 0);
        } catch (Exception e) { Log.w(TAG, "feedVideo", e); }
    }

    public synchronized void feedAudio(byte[] pcm) {
        if (!recording || audioCodec == null || pcm == null) return;
        try {
            int idx = audioCodec.dequeueInputBuffer(40000);
            if (idx < 0) return;
            ByteBuffer bb = audioCodec.getInputBuffer(idx);
            if (bb == null) return;
            bb.clear();
            bb.put(pcm);
            long pts = (System.nanoTime() - startNs) / 1000L;
            audioCodec.queueInputBuffer(idx, 0, pcm.length, pts, 0);
            drain(audioCodec, aInfo, false, 0);
        } catch (Exception e) { Log.w(TAG, "feedAudio", e); }
    }

    private void drain(MediaCodec codec, MediaCodec.BufferInfo info, boolean video) {
        drain(codec, info, video, 0);
    }

    private void drain(MediaCodec codec, MediaCodec.BufferInfo info, boolean video, long timeoutUs) {
        while (true) {
            int idx = codec.dequeueOutputBuffer(info, timeoutUs);
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) break;
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat fmt = codec.getOutputFormat();
                try {
                    if (video) videoTrack = muxer.addTrack(fmt);
                    else audioTrack = muxer.addTrack(fmt);
                    if (videoTrack >= 0 && audioTrack >= 0 && !muxerStarted) {
                        muxer.start();
                        muxerStarted = true;
                        Log.i(TAG, "muxer started, tracks v=" + videoTrack + " a=" + audioTrack);
                    }
                } catch (Exception e) { Log.w(TAG, "addTrack/start", e); }
                timeoutUs = 0;
                continue;
            }
            if (idx < 0) { timeoutUs = 0; continue; }
            writeSample(codec, idx, info, video);
            codec.releaseOutputBuffer(idx, false);
            timeoutUs = 0;
        }
    }

    private void writeSample(MediaCodec codec, int idx, MediaCodec.BufferInfo info, boolean video) {
        if (!muxerStarted || muxer == null) return;
        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
        if (info.size <= 0) return;
        ByteBuffer bb = codec.getOutputBuffer(idx);
        if (bb == null) return;
        bb.position(info.offset);
        bb.limit(info.offset + info.size);
        try { muxer.writeSampleData(video ? videoTrack : audioTrack, bb, info); }
        catch (Exception e) { Log.w(TAG, "writeSample", e); }
    }

    private static void signalEos(MediaCodec codec) {
        if (codec == null) return;
        try {
            int idx = codec.dequeueInputBuffer(150000);
            if (idx >= 0) codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
        } catch (Exception ignored) { }
    }

    private void drainEos(MediaCodec codec, MediaCodec.BufferInfo info, boolean video) {
        if (codec == null) return;
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            try {
                int idx = codec.dequeueOutputBuffer(info, 30000);
                if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) continue;
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat fmt = codec.getOutputFormat();
                    if (video) videoTrack = muxer.addTrack(fmt);
                    else audioTrack = muxer.addTrack(fmt);
                    if (videoTrack >= 0 && audioTrack >= 0 && !muxerStarted) {
                        muxer.start();
                        muxerStarted = true;
                    }
                    continue;
                }
                if (idx < 0) continue;
                writeSample(codec, idx, info, video);
                boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                codec.releaseOutputBuffer(idx, false);
                if (eos) break;
            } catch (Exception e) { break; }
        }
    }

    public synchronized String stop() {
        if (!recording) return null;
        recording = false;
        String name = outputName;
        try {
            signalEos(videoCodec);
            signalEos(audioCodec);
            drainEos(videoCodec, vInfo, true);
            drainEos(audioCodec, aInfo, false);
            if (muxerStarted && muxer != null) muxer.stop();
        } catch (Exception e) {
            Log.e(TAG, "muxer stop failed", e);
        } finally {
            try { if (videoCodec != null) { videoCodec.stop(); videoCodec.release(); } } catch (Exception ignored) { }
            try { if (audioCodec != null) { audioCodec.stop(); audioCodec.release(); } } catch (Exception ignored) { }
            try { if (muxer != null) muxer.release(); } catch (Exception ignored) { }
            try { if (pfd != null) pfd.close(); } catch (Exception ignored) { }
            try { if (directOut != null) directOut.close(); } catch (Exception ignored) { }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && storeUri != null && appCtx != null) {
            try {
                ContentValues done = new ContentValues();
                done.put(MediaStore.Video.Media.IS_PENDING, 0);
                appCtx.getContentResolver().update(storeUri, done, null, null);
                long size = querySize(storeUri);
                Log.i(TAG, "saved " + name + " uri=" + storeUri + " bytes=" + size);
                if (size <= 0) Log.e(TAG, "saved file is EMPTY — check encoder input");
            } catch (Exception e) {
                Log.e(TAG, "finalize MediaStore failed", e);
            }
        } else if (directFile != null) {
            MediaScannerConnection.scanFile(appCtx, new String[]{directFile.getAbsolutePath()},
                    new String[]{"video/mp4"}, null);
            Log.i(TAG, "saved " + name + " file=" + directFile + " bytes=" + directFile.length());
        }

        videoCodec = null;
        audioCodec = null;
        muxer = null;
        pfd = null;
        directOut = null;
        storeUri = null;
        directFile = null;
        muxerStarted = false;
        videoTrack = -1;
        audioTrack = -1;
        return name;
    }

    private long querySize(Uri uri) {
        Cursor c = null;
        try {
            c = appCtx.getContentResolver().query(uri,
                    new String[]{OpenableColumns.SIZE}, null, null, null);
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.SIZE);
                if (i >= 0) return c.getLong(i);
            }
        } catch (Exception ignored) { }
        finally { if (c != null) { try { c.close(); } catch (Exception ignored) { } } }
        return 0;
    }

    /** NV21 -> NV12 (swap U/V bytes); input array is not modified. */
    private static byte[] toNv12(byte[] nv21) {
        byte[] out = nv21.clone();
        int base = nv21.length * 2 / 3;
        for (int i = 0; i < base; i += 2) {
            byte t = out[base + i];
            out[base + i] = out[base + i + 1];
            out[base + i + 1] = t;
        }
        return out;
    }

    /** Bitmap ARGB -> NV21, for frames received over the relay. */
    public static byte[] bitmapToNv21(Bitmap bitmap) {
        int w = bitmap.getWidth(), h = bitmap.getHeight();
        int[] px = new int[w * h];
        bitmap.getPixels(px, 0, w, 0, 0, w, h);
        byte[] out = new byte[w * h * 3 / 2];
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                int c = px[j * w + i];
                int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
                out[j * w + i] = (byte) clamp(((66 * r + 129 * g + 25 * b + 128) >> 8) + 16);
            }
        }
        int base = w * h, k = 0;
        for (int j = 0; j < h / 2; j++) {
            for (int i = 0; i < w / 2; i++) {
                int c = px[(2 * j) * w + 2 * i];
                int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
                out[base + k++] = (byte) clamp(((112 * r - 94 * g - 18 * b + 128) >> 8) + 128);
                out[base + k++] = (byte) clamp(((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128);
            }
        }
        return out;
    }

    private static int clamp(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }
}
