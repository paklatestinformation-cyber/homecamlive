package com.kamran.homecamlive;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** MP4 recorder: reliable temp write, then publish to Gallery with fallbacks. */
public class Mp4Recorder {
    private static final String TAG = "Mp4Recorder";

    private MediaCodec videoCodec, audioCodec;
    private MediaMuxer muxer;
    private FileOutputStream tempOut;
    private File tempFile;
    private Context appCtx;
    private int videoTrack = -1, audioTrack = -1;
    private int videoColorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar;
    private boolean muxerStarted = false;
    private volatile boolean recording = false;
    private long startNs;
    private String outputName;
    private String savedPath = "";

    private final MediaCodec.BufferInfo vInfo = new MediaCodec.BufferInfo();
    private final MediaCodec.BufferInfo aInfo = new MediaCodec.BufferInfo();

    public boolean isRecording() { return recording; }

    /** Returns the final save description, e.g. OK:DCIM/Camera/file.mp4. */
    public synchronized String getSavedPath() { return savedPath; }

    /** Returns the output file name; MP4 is published on stop(). */
    public synchronized String start(Context ctx, int w, int h) throws Exception {
        if (recording) return null;
        if (w <= 0 || h <= 0) throw new IOException("Invalid video size " + w + "x" + h);

        appCtx = ctx.getApplicationContext();
        startNs = System.nanoTime();
        videoTrack = -1;
        audioTrack = -1;
        muxerStarted = false;
        savedPath = "";
        outputName = "PersonalCCTV_" +
                new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4";

        try {
            File dir = appCtx.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
            if (dir == null) dir = appCtx.getFilesDir();
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            tempFile = new File(dir, "tmp_" + outputName);
            tempOut = new FileOutputStream(tempFile);
            muxer = new MediaMuxer(tempOut.getFD(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            MediaFormat vf = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h);
            vf.setInteger(MediaFormat.KEY_BIT_RATE, 1500000);
            vf.setInteger(MediaFormat.KEY_FRAME_RATE, 15);
            vf.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
            videoCodec = createVideoEncoder(vf);
            videoCodec.start();

            try {
                MediaFormat af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 16000, 1);
                af.setInteger(MediaFormat.KEY_BIT_RATE, 32000);
                audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
                audioCodec.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                audioCodec.start();
            } catch (Throwable e) {
                Log.w(TAG, "audio encoder unavailable; recording video-only", e);
                if (audioCodec != null) {
                    try { audioCodec.release(); } catch (Exception ignored) { }
                }
                audioCodec = null;
            }

            primeBlankFrame(w, h);
            if (audioCodec != null) primeSilence();

            long deadline = System.currentTimeMillis() + 1500;
            while (!muxerStarted && System.currentTimeMillis() < deadline) {
                drain(videoCodec, vInfo, true, 60000);
                if (audioCodec != null) drain(audioCodec, aInfo, false, 60000);
            }
            if (!muxerStarted && videoTrack >= 0) {
                // Some devices never report an AAC output format. Keep video instead of failing.
                Log.w(TAG, "audio track not ready; starting video-only MP4");
                try { if (audioCodec != null) { audioCodec.stop(); audioCodec.release(); } }
                catch (Exception ignored) { }
                audioCodec = null;
                audioTrack = -1;
                muxer.start();
                muxerStarted = true;
            }
            if (!muxerStarted) throw new IOException("Video encoder track not ready");

            recording = true;
            Log.i(TAG, "start " + outputName + " " + w + "x" + h + " temp=" + tempFile);
            return outputName;
        } catch (Exception e) {
            Log.e(TAG, "start failed", e);
            cleanupAfterFailure();
            throw e;
        }
    }

    /** Pick an AVC encoder that accepts a byte-buffer YUV format we can feed. */
    private MediaCodec createVideoEncoder(MediaFormat format) throws IOException {
        Exception last = null;
        int[] candidates = {
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
        };
        MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        for (MediaCodecInfo info : list.getCodecInfos()) {
            if (!info.isEncoder()) continue;
            boolean isAvc = false;
            for (String type : info.getSupportedTypes()) {
                if (MediaFormat.MIMETYPE_VIDEO_AVC.equalsIgnoreCase(type)) { isAvc = true; break; }
            }
            if (!isAvc) continue;

            MediaCodecInfo.CodecCapabilities caps;
            try { caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC); }
            catch (Exception e) { continue; }

            for (int color : candidates) {
                if (!supportsColor(caps, color)) continue;
                MediaCodec codec = null;
                try {
                    codec = MediaCodec.createByCodecName(info.getName());
                    format.setInteger(MediaFormat.KEY_COLOR_FORMAT, color);
                    codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                    videoColorFormat = color;
                    Log.i(TAG, "encoder=" + info.getName() + " color=" + color);
                    return codec;
                } catch (Throwable e) {
                    last = e instanceof Exception ? (Exception) e : new Exception(e);
                    if (codec != null) {
                        try { codec.release(); } catch (Exception ignored) { }
                    }
                }
            }
        }
        throw new IOException("No usable AVC encoder", last);
    }

    private static boolean supportsColor(MediaCodecInfo.CodecCapabilities caps, int color) {
        if (caps.colorFormats == null) return false;
        for (int c : caps.colorFormats) if (c == color) return true;
        return false;
    }

    private void cleanupAfterFailure() {
        try { if (videoCodec != null) { videoCodec.stop(); videoCodec.release(); } } catch (Exception ignored) { }
        try { if (audioCodec != null) { audioCodec.stop(); audioCodec.release(); } } catch (Exception ignored) { }
        try { if (muxer != null) muxer.release(); } catch (Exception ignored) { }
        try { if (tempOut != null) tempOut.close(); } catch (Exception ignored) { }
        if (tempFile != null && tempFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            tempFile.delete();
        }
        videoCodec = null;
        audioCodec = null;
        muxer = null;
        tempOut = null;
        tempFile = null;
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
                    byte[] nv12 = toEncoderInput(yuv);
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
            int idx = videoCodec.dequeueInputBuffer(0);
            if (idx < 0) return;
            byte[] nv12 = toEncoderInput(nv21);
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
            int idx = audioCodec.dequeueInputBuffer(0);
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

    private void drain(MediaCodec codec, MediaCodec.BufferInfo info, boolean video, long timeoutUs) {
        while (true) {
            int idx = codec.dequeueOutputBuffer(info, timeoutUs);
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) break;
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat fmt = codec.getOutputFormat();
                try {
                    if (video) videoTrack = muxer.addTrack(fmt);
                    else audioTrack = muxer.addTrack(fmt);
                    if (videoTrack >= 0 && (audioCodec == null || audioTrack >= 0) && !muxerStarted) {
                        muxer.start();
                        muxerStarted = true;
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
                    if (videoTrack >= 0 && (audioCodec == null || audioTrack >= 0) && !muxerStarted) {
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
            long tempBytes = src.length();
            savedPath = publish(src, name);
            if (savedPath.startsWith("OK:")) {
                //noinspection ResultOfMethodCallIgnored
                src.delete();
            }
            Log.i(TAG, "stop " + name + " -> " + savedPath + " tempBytes=" + tempBytes);
        } else {
            savedPath = "FAIL:empty or missing temp MP4";
            Log.e(TAG, savedPath);
            if (src != null) {
                //noinspection ResultOfMethodCallIgnored
                src.delete();
            }
        }

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

    /** Try several public locations; keep the temp file if none succeed. */
    private String publish(File src, String name) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            String[] relPaths = {
                    Environment.DIRECTORY_DCIM + "/Camera",
                    Environment.DIRECTORY_DOWNLOADS,
                    Environment.DIRECTORY_MOVIES
            };
            for (String rel : relPaths) {
                Uri uri = null;
                boolean pending = false;
                try {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Video.Media.DISPLAY_NAME, name);
                    cv.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                    cv.put(MediaStore.Video.Media.RELATIVE_PATH, rel);
                    cv.put(MediaStore.Video.Media.IS_PENDING, 1);
                    pending = true;
                    uri = appCtx.getContentResolver().insert(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
                    if (uri == null) throw new IOException("insert failed");
                    OutputStream os = appCtx.getContentResolver().openOutputStream(uri, "w");
                    if (os == null) throw new IOException("output stream null");
                    copy(src, os);
                    os.flush();
                    os.close();
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.Video.Media.IS_PENDING, 0);
                    appCtx.getContentResolver().update(uri, done, null, null);
                    pending = false;
                    long size = querySize(uri);
                    if (size <= 0) throw new IOException("published size 0");
                    Log.i(TAG, "published " + rel + " bytes=" + size + " uri=" + uri);
                    return "OK:" + rel + "/" + name;
                } catch (Exception e) {
                    Log.w(TAG, "publish " + rel + " failed", e);
                    if (pending && uri != null) {
                        try {
                            ContentValues done = new ContentValues();
                            done.put(MediaStore.Video.Media.IS_PENDING, 0);
                            appCtx.getContentResolver().update(uri, done, null, null);
                        } catch (Exception ignored) { }
                    }
                    if (uri != null) {
                        try { appCtx.getContentResolver().delete(uri, null, null); } catch (Exception ignored) { }
                    }
                }
            }
            return "KEEP:" + src.getAbsolutePath();
        }

        String[] dirs = {
                Environment.DIRECTORY_DCIM + "/Camera",
                Environment.DIRECTORY_DOWNLOADS,
                Environment.DIRECTORY_MOVIES + "/HomeCamLive"
        };
        for (String rel : dirs) {
            try {
                String rootName = rel.contains("/") ? rel.substring(0, rel.indexOf('/')) : rel;
                String sub = rel.contains("/") ? rel.substring(rel.indexOf('/') + 1) : "";
                File root = Environment.getExternalStoragePublicDirectory(rootName);
                File dir = sub.isEmpty() ? root : new File(root, sub);
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                File dst = new File(dir, name);
                copyFile(src, dst);
                MediaScannerConnection.scanFile(appCtx, new String[]{dst.getAbsolutePath()},
                        new String[]{"video/mp4"}, null);
                return "OK:" + rel + "/" + name;
            } catch (Exception e) {
                Log.w(TAG, "direct publish " + rel + " failed", e);
            }
        }
        return "KEEP:" + src.getAbsolutePath();
    }

    private static void copy(File src, OutputStream dst) throws IOException {
        InputStream in = new FileInputStream(src);
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) dst.write(buf, 0, n);
        in.close();
    }

    private static void copyFile(File src, File dst) throws IOException {
        OutputStream os = new FileOutputStream(dst);
        try { copy(src, os); } finally { os.close(); }
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

    private byte[] toEncoderInput(byte[] nv21) {
        if (videoColorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
            return toI420(nv21);
        }
        return toNv12(nv21);
    }

    /** NV21 -> I420 (Y plane, then U plane, then V plane). */
    private static byte[] toI420(byte[] nv21) {
        byte[] out = new byte[nv21.length];
        int frameSize = nv21.length * 2 / 3;
        System.arraycopy(nv21, 0, out, 0, frameSize);
        int quarter = frameSize / 4;
        for (int i = 0; i < quarter; i++) {
            out[frameSize + i] = nv21[frameSize + i * 2 + 1];       // U
            out[frameSize + quarter + i] = nv21[frameSize + i * 2]; // V
        }
        return out;
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
