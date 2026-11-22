package com.kamran.homecamlive;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;

public final class Prefs {
    private static final String P = "homecamlive";
    private static final String K_ROOM = "room_id";
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Prefs() {}

    public static String getRoom(Context c) {
        SharedPreferences sp = c.getSharedPreferences(P, Context.MODE_PRIVATE);
        String room = sp.getString(K_ROOM, "");
        return room.isEmpty() ? randomRoom() : room;
    }

    public static void setRoom(Context c, String room) {
        c.getSharedPreferences(P, Context.MODE_PRIVATE).edit().putString(K_ROOM, room).apply();
    }

    public static String randomRoom() {
        StringBuilder sb = new StringBuilder("HOME-");
        for (int i = 0; i < 4; i++) sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        return sb.toString();
    }

    /** MQTT topics cannot contain + or #, and spaces are troublesome too. */
    public static String sanitize(String room) {
        if (room == null) return randomRoom();
        String s = room.trim().toUpperCase().replaceAll("[^A-Z0-9_-]", "-");
        if (s.isEmpty()) return randomRoom();
        return s;
    }
}
