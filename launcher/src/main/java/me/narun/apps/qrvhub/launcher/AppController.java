/*
 * QRV-Hub Launcher - app launcher and isolator for the Spender TC-7G.
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

package me.narun.apps.qrvhub.launcher;

import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Log;

/**
 * Owns the two managed apps. When this app is device owner, "stopped" means
 * hidden: the package is invisible to the user, its process is killed and it
 * receives no broadcasts or key events until it is unhidden again. Without
 * device-owner status the controller degrades to killBackgroundProcesses,
 * which does not isolate anything and is reported as such in the UI.
 */
public class AppController {
    private static final String TAG = "QRVHubLauncher";
    private static final String PREFS = "launcher";
    private static final String PREF_LAST = "last";

    public static final String PKG_SPENDER = "com.corget";
    public static final String PKG_QRVHUB = "me.narun.apps.qrvhub"; // second app slot: QRV-Hub
    private static final ComponentName MAIN_SPENDER = new ComponentName(PKG_SPENDER, "com.corget.MainView");
    private static final ComponentName MAIN_QRVHUB = new ComponentName(PKG_QRVHUB, "me.narun.apps.qrvhub.MainActivity");
    private static final String[] MANAGED = { PKG_SPENDER, PKG_QRVHUB };

    private final Context mCtx;
    private final DevicePolicyManager mDpm;
    private final ComponentName mAdmin;

    public AppController(Context ctx) {
        mCtx = ctx.getApplicationContext();
        mDpm = (DevicePolicyManager) mCtx.getSystemService(Context.DEVICE_POLICY_SERVICE);
        mAdmin = AdminReceiver.component(mCtx);
    }

    public boolean isOwner() {
        return mDpm != null && mDpm.isDeviceOwnerApp(mCtx.getPackageName());
    }

    /** Minimum screen-off timeout; the ROM (or an OEM app) keeps resetting it to 20 s. */
    public static final int MIN_SCREEN_TIMEOUT_MS = 120000;

    /** Raises the screen-off timeout to the minimum if something lowered it. */
    public void ensureScreenTimeout() {
        try {
            android.content.ContentResolver cr = mCtx.getContentResolver();
            int cur = android.provider.Settings.System.getInt(cr,
                    android.provider.Settings.System.SCREEN_OFF_TIMEOUT, MIN_SCREEN_TIMEOUT_MS);
            if (cur < MIN_SCREEN_TIMEOUT_MS) {
                android.provider.Settings.System.putInt(cr,
                        android.provider.Settings.System.SCREEN_OFF_TIMEOUT, MIN_SCREEN_TIMEOUT_MS);
                Log.i(TAG, "screen timeout raised from " + cur + " to " + MIN_SCREEN_TIMEOUT_MS);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "ensureScreenTimeout failed", e);
        }
    }

    /** Make this app the home app with no chooser. Idempotent. Owner only. */
    public boolean ensureHome() {
        if (!isOwner()) return false;
        try {
            IntentFilter home = new IntentFilter(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.addCategory(Intent.CATEGORY_DEFAULT);
            mDpm.addPersistentPreferredActivity(mAdmin, home,
                    new ComponentName(mCtx, HomeActivity.class));
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "ensureHome failed", e);
            return false;
        }
    }

    public boolean isHomeOverridden() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        android.content.pm.ResolveInfo ri = mCtx.getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        return ri != null && ri.activityInfo != null
                && mCtx.getPackageName().equals(ri.activityInfo.packageName);
    }

    public boolean isInstalled(String pkg) {
        try {
            // MATCH_UNINSTALLED_PACKAGES (8192) also matches hidden packages.
            mCtx.getPackageManager().getApplicationInfo(pkg, 8192);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    public boolean isHidden(String pkg) {
        return isOwner() && mDpm.isApplicationHidden(mAdmin, pkg);
    }

    /** Hidden == stopped and isolated. Returns true when the requested state holds afterwards. */
    public boolean setStopped(String pkg, boolean stopped) {
        if (!isInstalled(pkg)) return true;
        if (isOwner()) {
            try {
                if (mDpm.isApplicationHidden(mAdmin, pkg) != stopped) {
                    boolean ok = mDpm.setApplicationHidden(mAdmin, pkg, stopped);
                    Log.i(TAG, "setApplicationHidden(" + pkg + ", " + stopped + ") -> " + ok);
                }
                return mDpm.isApplicationHidden(mAdmin, pkg) == stopped;
            } catch (RuntimeException e) {
                Log.w(TAG, "setApplicationHidden failed for " + pkg, e);
                return false;
            }
        }
        if (stopped) {
            ActivityManager am = (ActivityManager) mCtx.getSystemService(Context.ACTIVITY_SERVICE);
            am.killBackgroundProcesses(pkg);
            Log.i(TAG, "degraded: killBackgroundProcesses(" + pkg + ")");
        }
        return false;
    }

    /** Stop (hide) every managed app. Returns true only if all are isolated. */
    public boolean stopAll() {
        boolean all = true;
        for (String p : MANAGED) all &= setStopped(p, true);
        restoreMediaVolume();
        return all;
    }

    // QRV-Hub holds the system media volume at maximum and does its own finer volume;
    // it is hidden (killed) without a chance to undo that, so the chooser does.
    private static final String PREF_MEDIA_VOLUME = "media_volume_before_qrvhub";

    private void saveMediaVolume() {
        if (prefs().contains(PREF_MEDIA_VOLUME)) return; // still holding the original
        android.media.AudioManager am = (android.media.AudioManager) mCtx.getSystemService(Context.AUDIO_SERVICE);
        int v = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
        prefs().edit().putInt(PREF_MEDIA_VOLUME, v).apply();
        Log.i(TAG, "media volume saved: " + v);
    }

    private void restoreMediaVolume() {
        if (!prefs().contains(PREF_MEDIA_VOLUME)) return;
        int v = prefs().getInt(PREF_MEDIA_VOLUME, 0);
        android.media.AudioManager am = (android.media.AudioManager) mCtx.getSystemService(Context.AUDIO_SERVICE);
        am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, v, 0);
        prefs().edit().remove(PREF_MEDIA_VOLUME).apply();
        Log.i(TAG, "media volume restored: " + v);
    }

    public void unhideAll() {
        for (String p : MANAGED) setStopped(p, false);
    }

    /** Stop the other app, unhide the chosen one and start it. */
    public boolean launch(String pkg) {
        for (String p : MANAGED) if (!p.equals(pkg)) setStopped(p, true);
        if (PKG_QRVHUB.equals(pkg)) saveMediaVolume(); else restoreMediaVolume();
        setStopped(pkg, false);
        ComponentName main = PKG_SPENDER.equals(pkg) ? MAIN_SPENDER : MAIN_QRVHUB;
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(main)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            mCtx.startActivity(i);
            prefs().edit().putString(PREF_LAST, pkg).apply();
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "launch failed for " + pkg, e);
            return false;
        }
    }

    /** Undo everything so the device returns to its original state. */
    public void release() {
        unhideAll();
        if (isOwner()) {
            try {
                mDpm.clearPackagePersistentPreferredActivities(mAdmin, mCtx.getPackageName());
            } catch (RuntimeException e) {
                Log.w(TAG, "clear preferred failed", e);
            }
            try {
                mDpm.clearDeviceOwnerApp(mCtx.getPackageName());
            } catch (RuntimeException e) {
                Log.w(TAG, "clearDeviceOwnerApp failed", e);
            }
        }
        // No longer device owner, but still a device administrator: Android refuses
        // to uninstall an active administrator, so give that up as well.
        try {
            if (mDpm != null && mDpm.isAdminActive(mAdmin)) mDpm.removeActiveAdmin(mAdmin);
        } catch (RuntimeException e) {
            Log.w(TAG, "removeActiveAdmin failed", e);
        }
    }

    private static final String PREF_BOOT_SEEN = "boot_seen";

    /** The kernel's random ID for the current boot; different after every reboot. */
    private static String bootId() {
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.FileReader("/proc/sys/kernel/random/boot_id"))) {
            String id = r.readLine();
            return id != null ? id.trim() : "";
        } catch (java.io.IOException e) {
            Log.w(TAG, "boot_id unreadable", e);
            return "";
        }
    }

    /** True only the first time it is asked after each reboot. */
    public boolean firstFrontThisBoot() {
        String id = bootId();
        if (id.isEmpty() || id.equals(prefs().getString(PREF_BOOT_SEEN, ""))) return false;
        prefs().edit().putString(PREF_BOOT_SEEN, id).apply();
        return true;
    }

    public String lastChoice() {
        return prefs().getString(PREF_LAST, PKG_SPENDER);
    }

    public CharSequence label(String pkg) {
        try {
            ApplicationInfo ai = mCtx.getPackageManager().getApplicationInfo(pkg, 8192);
            return mCtx.getPackageManager().getApplicationLabel(ai);
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    public android.graphics.drawable.Drawable icon(String pkg) {
        try {
            ApplicationInfo ai = mCtx.getPackageManager().getApplicationInfo(pkg, 8192);
            return mCtx.getPackageManager().getApplicationIcon(ai);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private SharedPreferences prefs() {
        return mCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
