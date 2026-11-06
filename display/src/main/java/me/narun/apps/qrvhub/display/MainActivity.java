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

import android.app.Activity;
import android.os.Bundle;

/**
 * Exists for two reasons only:
 *
 *  1. Android puts a freshly installed package into the "stopped" state,
 *     and stopped packages do NOT receive BOOT_COMPLETED. Launching an
 *     activity once clears that state. After installing, run:
 *
 *       adb -s <serial> shell am start -n me.narun.apps.qrvhub.display/.MainActivity
 *
 *     Reinstalling re-sets the stopped flag, so always launch AFTER install.
 *
 *  2. It gives you a way to apply the size on demand, and to test the
 *     reflection call without waiting for a boot.
 *
 * It draws nothing and finishes immediately -- important, because a real
 * UI would be unusable on a 160x128 panel.
 */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DisplayUtil.applyForcedSize(DisplayUtil.WIDTH, DisplayUtil.HEIGHT);
        finish();
    }
}
