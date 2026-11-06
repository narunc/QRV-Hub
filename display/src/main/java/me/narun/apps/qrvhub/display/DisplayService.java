/*
 * QRV-Hub Display - keeps the Spender TC-7G's 240x200 display size across reboots.
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

package me.narun.apps.qrvhub.display;

import android.app.IntentService;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

/**
 * Makes sure the panel really shows the forced 240x200 frame after boot.
 *
 * After a reboot WindowManager already reports the saved size, but the panel is
 * not rescaled (it shows the unscaled top-left 160x128), and a fix applied too
 * early in boot does not take. So instead of fixed delays this watches the real
 * state in SurfaceFlinger every CHECK_MS from boot and re-applies the size
 * whenever it is wrong; it stops once the display has stayed correct for
 * STABLE_MS (to catch a late reset by the firmware), or after WATCH_MS.
 * Without the DUMP grant the state cannot be read, and it falls back to three
 * timed attempts.
 *
 * Runs on IntentService's worker thread, so sleeping here is safe and the
 * service keeps the process alive for the duration.
 */
public class DisplayService extends IntentService {

    private static final long CHECK_MS  = 2000L;
    private static final long STABLE_MS = 20000L;
    private static final long WATCH_MS  = 90000L;
    /** Fallback without DUMP: the original timed attempts. */
    private static final long FALLBACK_DELAY_MS = 15000L;
    private static final int  FALLBACK_ATTEMPTS = 3;

    public DisplayService() {
        super("DisplayService");
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        final int w = DisplayUtil.WIDTH, h = DisplayUtil.HEIGHT;
        long start = SystemClock.elapsedRealtime();
        long okSince = -1;
        int fixes = 0;
        while (SystemClock.elapsedRealtime() - start < WATCH_MS) {
            Boolean scaled = DisplayUtil.isScaled(w, h);
            long t = SystemClock.elapsedRealtime() - start;
            if (scaled == null) {
                log("display state unreadable (no DUMP grant?); timed attempts");
                timedAttempts();
                return;
            }
            if (scaled) {
                if (okSince < 0) {
                    okSince = t;
                    log("scaled correctly at +" + t / 1000 + " s (uptime "
                            + SystemClock.elapsedRealtime() / 1000 + " s), fixes " + fixes);
                } else if (t - okSince >= STABLE_MS) {
                    log("stable; done");
                    return;
                }
            } else {
                okSince = -1;
                fixes++;
                boolean ok = DisplayUtil.applyForcedSize(w, h);
                log("not scaled at +" + t / 1000 + " s (uptime "
                        + SystemClock.elapsedRealtime() / 1000 + " s): fix " + fixes + " -> " + ok);
            }
            sleepQuietly(CHECK_MS);
        }
        log("gave up watching after " + WATCH_MS / 1000 + " s");
    }

    private void timedAttempts() {
        for (int i = 1; i <= FALLBACK_ATTEMPTS; i++) {
            sleepQuietly(FALLBACK_DELAY_MS);
            boolean ok = DisplayUtil.applyForcedSize(DisplayUtil.WIDTH, DisplayUtil.HEIGHT);
            log("attempt " + i + "/" + FALLBACK_ATTEMPTS + " -> " + ok);
        }
    }

    /** Logcat plus boot.log in the app's files (read with run-as), which outlives the log buffer. */
    private void log(String msg) {
        Log.i(DisplayUtil.TAG, msg);
        try (java.io.FileWriter f = new java.io.FileWriter(new java.io.File(getFilesDir(), "boot.log"), true)) {
            f.write(new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(new java.util.Date()) + " " + msg + "\n");
        } catch (java.io.IOException ignored) {
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
