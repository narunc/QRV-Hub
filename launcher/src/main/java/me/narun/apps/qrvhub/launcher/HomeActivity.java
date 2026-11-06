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

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * Key-driven chooser for a 2-inch, touch-less handset. Three modes drawn with
 * the same big rows: APPS (Spender Network / QRV-Hub), MENU (About / Release / Back) and
 * TEXT (full-screen text, any key returns). No dialogs: they are unreadable
 * at this size and steal key focus from the activity.
 *
 * Every time this window gains focus (boot, Home key, Back out of an app, app
 * crash) both managed apps are stopped, except during the short window right
 * after we started one ourselves.
 */
public class HomeActivity extends Activity {
    private static final String TAG = "QRVHubLauncher";
    private static final long LAUNCH_GRACE_MS = 4000;
    private enum Mode { APPS, MENU, TEXT, CONFIRM_EXIT }

    private AppController mCtl;
    private TextView mStatus;
    private View[] mRows;
    private Mode mMode = Mode.APPS;
    private boolean mReleaseArmed;
    private long mLaunchedAt;
    private String mConfirmPkg;
    private final Handler mHandler = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);
        mCtl = new AppController(this);
        mStatus = findViewById(R.id.status);
        mRows = new View[] { findViewById(R.id.row1), findViewById(R.id.row2), findViewById(R.id.row3) };
        mCtl.ensureHome();
        showApps();
    }

    // ---- modes -------------------------------------------------------------

    private void showApps() {
        mMode = Mode.APPS;
        mReleaseArmed = false;
        bindRow(0, AppController.PKG_SPENDER);
        bindRow(1, AppController.PKG_QRVHUB);
        mRows[2].setVisibility(View.GONE);
        mStatus.setText(mCtl.isOwner() ? R.string.status_idle : R.string.status_not_owner);
        mRows[AppController.PKG_QRVHUB.equals(mCtl.lastChoice()) ? 1 : 0].requestFocus();
    }

    private void showMenu() {
        mMode = Mode.MENU;
        mReleaseArmed = false;
        bindRow(0, getString(R.string.menu_about), v -> showText(aboutText()));
        bindRow(1, getString(R.string.menu_release), v -> onReleasePressed());
        bindRow(2, getString(R.string.menu_back), v -> showApps());
        mRows[2].setVisibility(View.VISIBLE);
        mStatus.setText(R.string.status_menu);
        mRows[0].requestFocus();
    }

    private void showText(CharSequence text) {
        mMode = Mode.TEXT;
        for (View r : mRows) r.setVisibility(View.GONE);
        mStatus.setMaxLines(12);
        mStatus.setText(text);
    }

    private void leaveText() {
        mStatus.setMaxLines(2);
        showMenu();
    }

    private void bindRow(int i, final String pkg) {
        View row = mRows[i];
        row.setVisibility(View.VISIBLE);
        ((TextView) row.findViewById(R.id.label)).setText(mCtl.label(pkg));
        ImageView icon = row.findViewById(R.id.icon);
        icon.setImageDrawable(mCtl.icon(pkg));
        icon.setVisibility(View.VISIBLE);
        row.setEnabled(mCtl.isInstalled(pkg));
        row.setOnClickListener(v -> start(pkg));
    }

    private void bindRow(int i, CharSequence label, View.OnClickListener l) {
        View row = mRows[i];
        row.setVisibility(View.VISIBLE);
        ((TextView) row.findViewById(R.id.label)).setText(label);
        row.findViewById(R.id.icon).setVisibility(View.GONE);
        row.setEnabled(true);
        row.setOnClickListener(l);
    }

    private String aboutText() {
        String ver = "?";
        try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) {}
        return getString(R.string.about_text, ver, mCtl.isOwner(), mCtl.isHomeOverridden());
    }

    private void onReleasePressed() {
        if (!mReleaseArmed) {
            mReleaseArmed = true;
            ((TextView) mRows[1].findViewById(R.id.label)).setText(R.string.menu_release_confirm);
            return;
        }
        mCtl.release();
        showText(getString(R.string.released));
    }

    // ---- launching / stopping --------------------------------------------

    private void start(String pkg) {
        if (!mCtl.isInstalled(pkg)) {
            mStatus.setText(getString(R.string.status_missing, pkg));
            return;
        }
        mLaunchedAt = SystemClock.elapsedRealtime();
        if (!mCtl.launch(pkg)) {
            mLaunchedAt = 0;
            mStatus.setText(getString(R.string.status_launch_failed, mCtl.label(pkg)));
        }
    }

    /**
     * Home pressed while the chooser is already in front: no focus change happens,
     * so re-check installed apps and re-apply isolation here (e.g. after an app
     * was installed or removed over adb while the chooser was showing).
     */
    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        if (hasWindowFocus() && mMode != Mode.CONFIRM_EXIT) stopAllAndRefresh();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) return;
        long since = SystemClock.elapsedRealtime() - mLaunchedAt;
        if (mLaunchedAt != 0 && since < LAUNCH_GRACE_MS) {
            // We just started an app and it has not taken the screen yet.
            // Re-check once the grace period is over in case it never does.
            mHandler.postDelayed(() -> { if (hasWindowFocus()) onBackInFront(); }, LAUNCH_GRACE_MS - since);
            return;
        }
        onBackInFront();
    }

    /**
     * The chooser came to the front (boot, Home, app closed or crashed). If one of
     * the managed apps is still running (not hidden), Home was pressed inside it:
     * ask before stopping it, so nothing is closed by accident. Otherwise stop
     * everything at once, as before.
     */
    private void onBackInFront() {
        if (mMode == Mode.CONFIRM_EXIT) return;
        // After a reboot nothing is running, whatever the saved hidden flags say
        // (they survive the reboot, so the app would look "running" and the
        // close prompt would appear). Start the default app, QRV-Hub, instead.
        if (mCtl.firstFrontThisBoot() && mCtl.isInstalled(AppController.PKG_QRVHUB)) {
            Log.i(TAG, "first time in front since boot: starting QRV-Hub");
            start(AppController.PKG_QRVHUB);
            return;
        }
        String running = runningApp();
        if (running != null) {
            showExitConfirm(running);
        } else {
            stopAllAndRefresh();
        }
    }

    /** The managed app that is currently unhidden, i.e. was started and not yet stopped. */
    private String runningApp() {
        if (!mCtl.isOwner()) return null;
        for (String pkg : new String[] { AppController.PKG_QRVHUB, AppController.PKG_SPENDER }) {
            if (mCtl.isInstalled(pkg) && !mCtl.isHidden(pkg)) return pkg;
        }
        return null;
    }

    private void showExitConfirm(String pkg) {
        mMode = Mode.CONFIRM_EXIT;
        mConfirmPkg = pkg;
        mLaunchedAt = 0;
        ((ImageView) findViewById(R.id.confirm_icon)).setImageDrawable(mCtl.icon(pkg));
        ((TextView) findViewById(R.id.confirm_name)).setText(mCtl.label(pkg));
        findViewById(R.id.confirm_panel).setVisibility(View.VISIBLE);
        Log.i(TAG, "Home pressed in " + pkg + ": asking before stopping it");
    }

    private void leaveExitConfirm() {
        findViewById(R.id.confirm_panel).setVisibility(View.GONE);
        mMode = Mode.APPS;
    }

    private void stopAllAndRefresh() {
        mLaunchedAt = 0;
        // Idempotent; re-asserted here so a cleared override (e.g. after a
        // release + re-provision) is restored without recreating the activity.
        mCtl.ensureHome();
        mCtl.ensureScreenTimeout();
        boolean isolated = mCtl.stopAll();
        Log.i(TAG, "launcher in front: stopAll -> isolated=" + isolated);
        if (mMode == Mode.APPS) showApps();
    }

    // ---- keys ---------------------------------------------------------------
    // Physical keys on the TC-7G: D-pad (DPAD_*), OK (DPAD_CENTER), MENU (F1),
    // Back, Home, PTT (261), SOS, VB, rotary (F11/F12), volume.

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean up = event.getAction() == KeyEvent.ACTION_UP;
        if (mMode == Mode.TEXT) {
            if (up) leaveText();
            return true;
        }
        if (mMode == Mode.CONFIRM_EXIT) {
            if (up) {
                if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                    leaveExitConfirm();
                    stopAllAndRefresh();
                } else if (code == KeyEvent.KEYCODE_BACK) {
                    leaveExitConfirm();
                    showApps();
                    start(mConfirmPkg); // brings the running app back unchanged
                }
            }
            return true; // everything else ignored while asking
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_TAB:
                return super.dispatchKeyEvent(event);
            case KeyEvent.KEYCODE_F1:
            case KeyEvent.KEYCODE_MENU:
                if (up) { if (mMode == Mode.APPS) showMenu(); else showApps(); }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (up && mMode == Mode.MENU) showApps();
                return true;
            default:
                // PTT, SOS, VB, rotary, volume, anything else: swallowed so nothing
                // reacts while the chooser is in front.
                return true;
        }
    }
}
