package com.kamran.homecamlive;

import java.nio.charset.StandardCharsets;

public final class Control {
    public static final String FLIP = "FLIP";
    public static final String REC_START = "REC_START";
    public static final String REC_STOP = "REC_STOP";
    public static final String CAM_FRONT = "CAM_FRONT";
    public static final String CAM_BACK = "CAM_BACK";
    public static final String MIC_ON = "MIC_ON";
    public static final String MIC_OFF = "MIC_OFF";
    public static final String REC_ON = "REC_ON";
    public static final String REC_OFF = "REC_OFF";
    public static final String DUAL_ON = "DUAL_ON";
    public static final String DUAL_OFF = "DUAL_OFF";
    public static final String DUAL_NO = "DUAL_NO";
    public static final String DUAL_FAIL = "DUAL_FAIL";

    private Control() { }

    public static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    public static String text(byte[] b) {
        return b == null ? "" : new String(b, StandardCharsets.UTF_8).trim();
    }
}
