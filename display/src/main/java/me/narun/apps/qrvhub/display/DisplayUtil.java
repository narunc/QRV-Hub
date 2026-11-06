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

import android.os.IBinder;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * Applies a forced display size via IWindowManager.
 *
 * Device: Spender TC-7G, physical panel 160x128 @ density 120.
 * Target override 240x200 -> 320 x 266dp.
 *
 * Requires android.permission.WRITE_SECURE_SETTINGS, which is
 * protectionLevel signature|privileged|development -- so it cannot be
 * granted at install time, but CAN be granted from adb:
 *
 *   adb -s <serial> shell pm grant me.narun.apps.qrvhub.display android.permission.WRITE_SECURE_SETTINGS
 */
public final class DisplayUtil {

    public static final String TAG = "QRVHubDisplay";

    // Edit these two if you change your target resolution.
    public static final int WIDTH  = 240;
    public static final int HEIGHT = 200;

    private DisplayUtil() { }

    /**
     * Whether SurfaceFlinger really scales the WIDTH x HEIGHT frame onto the panel
     * (its display viewport, "v:[0,0,W,H]"). After a reboot WindowManager already
     * reports the forced size while the panel shows the unscaled top-left corner
     * ("v:[0,0,160,128]"). Returns null if SurfaceFlinger cannot be read (no DUMP grant).
     */
    public static Boolean isScaled(int width, int height) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[] { "dumpsys", "SurfaceFlinger" });
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) {
                int i = line.indexOf("v:[");
                if (i < 0) continue;
                int j = line.indexOf(']', i);
                String[] v = line.substring(i + 3, j).split(",");
                return v.length == 4 && Integer.parseInt(v[2].trim()) == width
                        && Integer.parseInt(v[3].trim()) == height;
            }
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "cannot read SurfaceFlinger", t);
            return null;
        } finally {
            if (p != null) p.destroy();
        }
    }

    public static boolean applyForcedSize(int width, int height) {
        try {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            IBinder binder = (IBinder) serviceManager
                    .getMethod("getService", String.class)
                    .invoke(null, "window");

            if (binder == null) {
                Log.e(TAG, "window service binder was null -- too early in boot?");
                return false;
            }

            Class<?> stub = Class.forName("android.view.IWindowManager$Stub");
            Object wm = stub.getMethod("asInterface", IBinder.class).invoke(null, binder);

            // Clear first. After a reboot the saved size is already "set" in
            // WindowManager, so setting the same size again is a no-op, yet the
            // panel was sometimes not rescaled (the screen showed only the
            // top-left 160x128 of the 240x200 frame). A real change fixes that.
            try {
                wm.getClass().getMethod("clearForcedDisplaySize", int.class).invoke(wm, 0);
            } catch (NoSuchMethodException nsme) {
                wm.getClass().getMethod("clearForcedDisplaySize").invoke(wm);
            }
            // Let the clear reach the display before setting again.
            try { Thread.sleep(300); } catch (InterruptedException ignored) { }
            // Signature differs across releases. Newer builds take a displayId
            // first; older ones take width/height only. Try both.
            try {
                Method m = wm.getClass().getMethod(
                        "setForcedDisplaySize", int.class, int.class, int.class);
                m.invoke(wm, 0 /* Display.DEFAULT_DISPLAY */, width, height);
            } catch (NoSuchMethodException nsme) {
                Method m = wm.getClass().getMethod(
                        "setForcedDisplaySize", int.class, int.class);
                m.invoke(wm, width, height);
            }

            Log.i(TAG, "forced display size applied: " + width + "x" + height);
            return true;

        } catch (Throwable t) {
            Log.e(TAG, "failed to apply forced display size", t);
            return false;
        }
    }
}
