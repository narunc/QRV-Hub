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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * adb-only switch between e2hub.vra.or.th and a local test server. Protected by
 * android.permission.DUMP in the manifest, so ordinary apps cannot send it.
 * The UI marks the header "[TEST]" while an override is active.
 */
public class DebugReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (!"me.narun.apps.qrvhub.TEST_SERVER".equals(i.getAction())) return;
        String host = i.getStringExtra("host");
        if (host == null || host.isEmpty()) {
            Prefs.clearTestServer(c);
            Certs.resetTrust(c);
            Log.i("QRV-Hub", "test server cleared; trust store reset to the pinned certificate");
        } else {
            Prefs.setTestServer(c, host, i.getIntExtra("port", Prefs.PORT));
            Log.i("QRV-Hub", "test server set to " + host);
        }
    }
}
