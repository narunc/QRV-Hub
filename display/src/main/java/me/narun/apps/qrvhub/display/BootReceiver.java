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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Hands off immediately to DisplayService.
 *
 * Deliberately does NO waiting here: once onReceive() returns, the process
 * is eligible for death, so any Handler.postDelayed() scheduled from a
 * receiver may never run. The service keeps the process alive instead.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(DisplayUtil.TAG, "boot broadcast: " + intent.getAction());
        context.startService(new Intent(context, DisplayService.class));
    }
}
