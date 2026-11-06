/*
 * QRV-Hub - single-server push-to-talk client for the Spender TC-7G.
 * Copyright (C) 2026 Narun Chanyavilas <opensource@narun.me>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 */

package me.narun.apps.qrvhub;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.LinkedHashMap;
import java.util.Map;

/** App-private persistent state: credentials, favorites, test-server override. */
final class Prefs {
    // Set at build time from gradle.properties (qrvhub.host / qrvhub.port).
    static final String HOST = BuildConfig.SERVER_HOST;
    static final int PORT = BuildConfig.SERVER_PORT;

    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("qrvhub", Context.MODE_PRIVATE);
    }

    // ---- server -------------------------------------------------------------

    static String host(Context c) {
        String test = testHost(c);
        return test != null ? test : HOST;
    }

    static int port(Context c) {
        return testHost(c) != null ? sp(c).getInt("test_port", PORT) : PORT;
    }

    static boolean isTestServer(Context c) {
        return testHost(c) != null;
    }

    private static String testHost(Context c) {
        String h = sp(c).getString("test_host", null);
        return (h == null || h.isEmpty()) ? null : h;
    }

    static void setTestServer(Context c, String host, int port) {
        sp(c).edit().putString("test_host", host).putInt("test_port", port).apply();
    }

    static void clearTestServer(Context c) {
        sp(c).edit().remove("test_host").remove("test_port").apply();
    }

    // ---- credentials --------------------------------------------------------

    /** True after a successful CONNECT press, until Log out or an auth rejection. */
    static boolean isLoggedIn(Context c) {
        return sp(c).getBoolean("logged_in", false);
    }

    static String username(Context c) {
        return sp(c).getString("username", "");
    }

    static String password(Context c) {
        return sp(c).getString("password", "");
    }

    static void saveCredentials(Context c, String user, String pass) {
        sp(c).edit().putString("username", user).putString("password", pass)
                .putBoolean("logged_in", true).apply();
    }

    /** Keeps the username so it is prefilled on the login screen. */
    static void logOut(Context c) {
        sp(c).edit().remove("password").putBoolean("logged_in", false).apply();
    }

    // ---- options ------------------------------------------------------------

    /** Turn the screen on while PTT is held, off 5 s after release. Default on. */
    static boolean screenOnPtt(Context c) {
        return sp(c).getBoolean("screen_on_ptt", true);
    }

    static void setScreenOnPtt(Context c, boolean on) {
        sp(c).edit().putBoolean("screen_on_ptt", on).apply();
    }

    /** PTT lock (DPAD left/right): PTT presses do not transmit. Kept across restarts. */
    static boolean pttLocked(Context c) {
        return sp(c).getBoolean("ptt_locked", false);
    }

    static void setPttLocked(Context c, boolean locked) {
        sp(c).edit().putBoolean("ptt_locked", locked).apply();
    }

    /** Level saved by a double click of the knob, restored by the next one. */
    static int volumeBeforeMute(Context c) {
        return sp(c).getInt("volume_before_mute", 0);
    }

    static void setVolumeBeforeMute(Context c, int level) {
        sp(c).edit().putInt("volume_before_mute", level).apply();
    }

    /** The former separate mute flag (muted is now volume 0); read once and removed. */
    static boolean takeLegacyMuted(Context c) {
        boolean muted = sp(c).getBoolean("muted", false);
        if (sp(c).contains("muted")) sp(c).edit().remove("muted").apply();
        return muted;
    }

    static boolean screenOnReceive(Context c) {
        return sp(c).getBoolean("screen_on_rx", true);
    }

    static void setScreenOnReceive(Context c, boolean on) {
        sp(c).edit().putBoolean("screen_on_rx", on).apply();
    }

    /** QRV-Hub's own output volume, 0..QrvHubService.VOLUME_MAX; -1 until first set. */
    static int volume(Context c) {
        return sp(c).getInt("volume", -1);
    }

    static void setVolume(Context c, int level) {
        sp(c).edit().putInt("volume", level).apply();
    }

    /** Number of steps the saved volume was stored for (15 or 22 on earlier scales). */
    static int volumeScale(Context c) {
        return sp(c).getInt("volume_scale", 15);
    }

    static void setVolumeScale(Context c, int max) {
        sp(c).edit().putInt("volume_scale", max).apply();
    }

    // ---- favorites (per server, so a test server never pollutes the main one) ------

    private static String favKey(Context c, int n, String field) {
        return "fav" + n + "_" + field + "@" + host(c);
    }

    static int favoriteId(Context c, int n) {
        return sp(c).getInt(favKey(c, n, "id"), -1);
    }

    static String favoriteName(Context c, int n) {
        return sp(c).getString(favKey(c, n, "name"), null);
    }

    static void setFavorite(Context c, int n, int channelId, String name) {
        sp(c).edit().putInt(favKey(c, n, "id"), channelId)
                .putString(favKey(c, n, "name"), name).apply();
    }

    // ---- listened channels (what the user asked for; per server) ------------

    /** Channel ID to name, in the order they were added. */
    static LinkedHashMap<Integer, String> listening(Context c) {
        LinkedHashMap<Integer, String> m = new LinkedHashMap<>();
        String raw = sp(c).getString("listen@" + host(c), "");
        for (String line : raw.split("\n")) {
            int tab = line.indexOf('\t');
            if (tab <= 0) continue;
            try {
                m.put(Integer.parseInt(line.substring(0, tab)), line.substring(tab + 1));
            } catch (NumberFormatException ignored) {
            }
        }
        return m;
    }

    static void setListening(Context c, Map<Integer, String> m) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, String> e : m.entrySet()) {
            sb.append(e.getKey()).append('\t')
                    .append(e.getValue().replace('\n', ' ').replace('\t', ' ')).append('\n');
        }
        sp(c).edit().putString("listen@" + host(c), sb.toString()).apply();
    }
}
