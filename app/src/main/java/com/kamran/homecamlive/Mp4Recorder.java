package com.kamran.homecamlive;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Local MP4 (H.264 + AAC) written to a real file, then published to MediaStore. */
public class Mp4Recorder {
    private static final String TAG = "Mp4Recorder";

    private MediaCodec videoCodec, audioCodec;
    private MediaMuxer muxer;
    private FileOutputStream tempOut;
    private File tempFile;
    private Context appCtx;
    private int videoTrack = -1, audioTrack = -1;
    private boolean muxerStarted = false, recording = false;
    private long startNs;
    private String outputName;

    private final MediaCodec.BufferInfo vInfo = new MediaCodec.BufferInfo();
    private final MediaCodec.BufferInfo aInfo = new MediaCodec.BufferInfo();

    public synchronized boolean isRecording() { return recording; }

    /** Returns the saved file name; file ends in Movies/HomeCamLive. */
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

        File dir = appCtx.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (dir == null) dir = appCtx.getFilesDir();
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        tempFile = new File(dir, "tmp_" + outputName);
        tempOut = new FileOutputStream(tempFile);

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

        muxer = new MediaMuxer(tempOut.getFD(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        // Force both tracks to exist: ek bhi frame/audio na aaye tab bhi
        // muxer.start() chal jaye aur MP4 valid bane.
        primeBlankFrame(w, h);
        primeSilence();

        recording = true;
        Log.i(TAG, "start " + outputName + " " + w + "x" + h);
        return outputName;
    }

    private void primeBlankFrame(int w, int h) {
        try {
            byte[] yuv = new byte[w * h * 3 / 2];
            int yLen = w * h;
            for (int i = 0; i < yLen; i++) yuv[i] = 16;
            for (int i = yLen; i < yuv.length; i++) yuv[i] = 128;
            int idx = videoCodec.dequeueInputBuffer(100000);
            if (idx >= 0) {
                ByteBuffer bb = videoCodec.getInputBuffer(idx);
                if (bb != null) {
                    byte[] nv12 = toNv12(yuv);
                    bb.clear();
                    bb.put(nv12);
                    videoCodec.queueInputBuffer(idx, 0, nv12.length, 0, 0);
                    drain(videoCodec, vInfo, true);
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
                    drain(audioCodec, aInfo, false);
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
            drain(videoCodec, vInfo, true);
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
            drain(audioCodec, aInfo, false);
        } catch (Exception e) { Log.w(TAG, "feedAudio", e); }
    }

    private void drain(MediaCodec codec, MediaCodec.BufferInfo info, boolean video) {
        while (true) {
            int idx = codec.dequeueOutputBuffer(info, 0);
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) break;
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat fmt = codec.getOutputFormat();
                try {
                    if (video) videoTrack = muxer.addTrack(fmt);
                    else audioTrack = muxer.addTrack(fmt);
                    if (videoTrack >= 0 && audioTrack >= 0 && !muxerStarted) {
                        muxer.start();
                        muxerStarted = true;
                    }
                } catch (Exception e) { Log.w(TAG, "addTrack/start", e); }
                continue;
            }
            if (idx < 0) continue;
            writeSample(codec, idx, info, video);
            codec.releaseOutputBuffer(idx, false);
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
            try { if (tempOut != null) tempOut.close(); } catch (Exception ignored) { }
        }

        File src = tempFile;
        if (src != null && src.exists() && src.length() > 1024) {
            publishToGallery(src, name);
        } else {
            Log.e(TAG, "temp MP4 missing/empty: " + (src == null ? "null" : src.length()));
        }
        //noinspection ResultOfMethodCallIgnored
        if (src != null) src.delete();

        videoCodec = null;
        audioCodec = null;
        muxer = null;
        tempOut = null;
        tempFile = null;
        muxerStarted = false;
        videoTrack = -1;
        audioTrack = -1;
        return name;
    }

    private void publishToGallery(File src, String name) {
        Uri uri = null;
        boolean pending = false;
        try {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Video.Media.DISPLAY_NAME, name);
            cv.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cv.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/HomeCamLive");
                cv.put(MediaStore.Video.Media.IS_PENDING, 1);
                pending = true;
            } else {
                File pub = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_MOVIES), "HomeCamLive");
                //noinspection ResultOfMethodCallIgnored
                pub.mkdirs();
                cv.put(MediaStore.Video.Media.DATA, new File(pub, name).getAbsolutePath());
            }
            uri = appCtx.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new IOException("MediaStore insert failed");
            OutputStream os = appCtx.getContentResolver().openOutputStream(uri);
            if (os == null) throw new IOException("MediaStore output null");
            copy(src, os);
            os.close();
            if (pending) {
                ContentValues done = new ContentValues();
                done.put(MediaStore.Video.Media.IS_PENDING, 0);
                appCtx.getContentResolver().update(uri, done, null, null);
                pending = false;
            }
            MediaScannerConnection.scanFile(appCtx, new String[]{src.getAbsolutePath()},
                    new String[]{"video/mp4"}, null);
            Log.i(TAG, "saved " + name + " bytes=" + src.length());
        } catch (Exception e) {
            Log.e(TAG, "publish failed", e);
            if (pending && uri != null) {
                try {
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.Video.Media.IS_PENDING, 0);
                    appCtx.getContentResolver().update(uri, done, null, null);
                } catch (Exception ignored) { }
            }
        }
    }

    private static void copy(File src, OutputStream dst) throws IOException {
        InputStream in = new java.io.FileInputStream(src);
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) dst.write(buf, 0, n);
        in.close();
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
