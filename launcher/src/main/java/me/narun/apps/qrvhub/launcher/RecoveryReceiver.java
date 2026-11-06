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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** adb-only escape hatch (receiver is protected by android.permission.DUMP). */
public class RecoveryReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        AppController c = new AppController(context);
        String a = intent.getAction();
        if ("me.narun.apps.qrvhub.launcher.UNHIDE_ALL".equals(a)) {
            c.unhideAll();
            Log.i("QRVHubLauncher", "recovery: unhid all");
        } else if ("me.narun.apps.qrvhub.launcher.RELEASE".equals(a)) {
            c.release();
            Log.i("QRVHubLauncher", "recovery: released control");
        }
    }
}
