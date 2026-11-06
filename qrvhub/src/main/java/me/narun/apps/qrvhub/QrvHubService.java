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

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.BatteryManager;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.UnknownFieldSet;

import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import se.lublin.humla.Constants;
import se.lublin.humla.HumlaService;
import se.lublin.humla.model.IChannel;
import se.lublin.humla.model.Server;
import se.lublin.humla.net.HumlaConnection;
import se.lublin.humla.net.HumlaTCPMessageType;
import se.lublin.humla.protocol.HumlaTCPMessageListener;
import se.lublin.humla.protobuf.Mumble;
import se.lublin.humla.model.IUser;
import se.lublin.humla.model.TalkState;
import se.lublin.humla.util.HumlaException;
import se.lublin.humla.audio.AudioOutput;
import se.lublin.humla.util.HumlaObserver;

/**
 * Humla service fixed to one server, push-to-talk only, loudspeaker/headset output.
 *
 * Hardware keys are taken from the broadcasts the TC-7G framework (log tag
 * "UniproKey") sends for every press regardless of window focus or screen
 * state, so they work while the UI is not in front:
 *   unipro.hotkey.ptt.*   PTT key and the headset-jack PTT button (also sent
 *                         while the display is off)
 *   unipro.hotkey.power.*  the physical power button (key 140; also sent while
 *                         the display is off): double press mutes/restores
 * The two side buttons are different: the framework only broadcasts them while
 * the display is on, but it always dispatches their key events (260 upper,
 * 267 lower) to the focused window, so the activity forwards those to
 * {@link #onSideKey}.
 *
 * Volume: the knob changes QRV-Hub's own 0..30 level (1 dB per step for the four
 * quietest from -54 dB, then 2 dB up to full; applied as a
 * gain on the decoded voice), because the firmware only offers 7 coarse steps.
 * The firmware beeps on every knob click at the system media volume, so that
 * volume is held at 0 while nothing plays (the beep is silent) and, while voice
 * plays, set to the lowest of its steps that is at least the chosen level, with
 * the gain making up the rest (a click then beeps no louder than the voice). QRV-Hub Launcher puts the system volume back
 * when the chooser returns. Knob clicks can arrive three ways (the
 * unipro.hotkey.volume* broadcast, the key event in the focused activity, and a
 * system volume change when the firmware handles the key itself with the screen
 * off); {@link #onKnob} counts each click once.
 */
public class QrvHubService extends HumlaService {
    private static final String TAG = "QRV-Hub";
    private static final String KEY_PREFIX = "unipro.hotkey.";
    private static final int NOTIFICATION_ID = 1;
    private static final long HOLD_MS = 1000;
    private static final int RECONNECT_DELAY_MS = 10000;
    // Mumble 1.4 UserState fields, not in the library's protocol definition;
    // sent and read through the message's unknown-field set.
    private static final int FIELD_LISTEN_ADD = 21;
    private static final int FIELD_LISTEN_REMOVE = 22;
    // Mumble 1.4 PermissionDenied.type values for listener limits.
    private static final int DENY_CHANNEL_LISTENER_LIMIT = 12;
    private static final int DENY_USER_LISTENER_LIMIT = 13;
    private static final long LISTEN_CONFIRM_MS = 4000;

    // Icon kinds for feedback cards (mapped to drawables by the activity).
    static final int FB_INFO = 0;
    static final int FB_STAR = 1;
    static final int FB_LISTEN = 2;
    static final int FB_LISTEN_OFF = 3;
    static final int FB_HEADSET = 4;
    static final int FB_SPEAKER = 5;
    static final int FB_WARN = 6;
    static final int FB_LOCK = 7;
    static final int FB_UNLOCK = 8;
    static final int FB_MUTE = 9;
    static final int FB_UNMUTE = 10;

    /** Levels 1..FINE_LEVELS rise 1 dB from LEVEL1_DB, then 2 dB per level up to 0 dB. */
    private static final float LEVEL1_DB = -54f;
    private static final int FINE_LEVELS = 4;
    /** 30: the 2 dB climb from -51 dB ends with one 1 dB step to 0 dB. */
    static final int VOLUME_MAX = FINE_LEVELS + (int) Math.ceil(-(LEVEL1_DB + FINE_LEVELS - 1) / 2f);
    // Level of system media steps 1..7 in dB: the platform's speaker and headset
    // curves on this firmware (measured on the speaker: step 1 -40.9, step 6 -4.1).
    private static final float[] SPEAKER_STEP_DB = {-40.6f, -29.1f, -20.9f, -12.6f, -7.9f, -3.9f, 0f};
    private static final float[] HEADSET_STEP_DB = {-45.4f, -35.1f, -26.9f, -18.6f, -12.1f, -6.1f, 0f};
    /** The same click reported by two sources arrives within this window. */
    private static final long KNOB_DEDUP_MS = 400;

    /** Implemented by the activity while it is alive. */
    interface UiListener {
        /** Short card: icon kind (FB_*), title, optional detail line. */
        void onFeedback(int icon, String title, String detail);
        void onHeadsetChanged(boolean plugged);
        void onListeningChanged();
        void onMuteChanged();
        void onVolumeChanged(int level);
    }

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private UiListener mUi;
    private PowerManager.WakeLock mKeepAlive;
    private WifiManager.WifiLock mWifiLock;
    private boolean mSessionActive;
    private boolean mForeground;
    private X509Certificate[] mRejectedChain;
    private volatile boolean mHeadset;
    /**
     * Screen on while PTT is held and/or while someone is heard (two options), off
     * 5 s after both end. ON_AFTER_RELEASE restarts the normal timeout at release.
     */
    private PowerManager.WakeLock mEventScreen;
    /** Brief wake-up to show "PTT locked" when PTT is pressed with the screen off (always, whatever the options). */
    private PowerManager.WakeLock mLockNotice;
    private static final long EVENT_SCREEN_OFF_MS = 5000;
    /** Screen off via the Spender firmware (debug.test goToSleep); the normal timeout cannot go below ~10 s. */
    private final Runnable mEventScreenOff = () -> {
        mScreenOffPending = false;
        // Already off (the user turned it off): nothing to do, and no change to mark as ours.
        if (!((PowerManager) getSystemService(POWER_SERVICE)).isInteractive()) return;
        markOwnScreenChange();
        sendBroadcast(new Intent("unipro.screen.sleep"));
    };
    /** mEventScreenOff is queued: an event that starts meanwhile continues the same wake-up. */
    private boolean mScreenOffPending;
    private boolean mPttHeld;
    /** The screen was off when the current event began, so QRV-Hub turns it off again afterwards. */
    private boolean mEventWokeScreen;
    /** Other users whose voice is arriving now (for any reason: own channel, listened, linked, shout, whisper). */
    private final Set<Integer> mTalkers = new HashSet<>();
    /** LED colour last sent: 0 = handed back to the system, 1 = red, 2 = green. */
    private int mLed = -1;
    /** Channels the server has confirmed we listen to (cleared on disconnect). */
    private final Set<Integer> mListening = new LinkedHashSet<>();
    /** Listening states seen before ServerSync tells us our own session. */
    private final Map<Integer, Set<Integer>> mPreSyncListening = new HashMap<>();

    private AudioManager mAudio;
    private int mVolume;
    private long mKnobMs;
    private int mKnobDir;
    private String mKnobSrc;
    private final Runnable mPinVolume = this::applySystemVolume;
    /** True while the voice track is playing (set on the audio thread). */
    private volatile boolean mVoicePlaying;
    /** System volume values QRV-Hub set itself, with the time, to ignore their change broadcasts. */
    private final Map<Integer, Long> mSelfSet = new HashMap<>();
    private static final long SELF_SET_MS = 1500;

    private final boolean[] mSideDown = new boolean[3];
    private final boolean[] mSideHeld = new boolean[3];
    private final Runnable mHold1 = () -> onSideHold(1);
    private final Runnable mHold2 = () -> onSideHold(2);
    private final Runnable mRetry = () -> {
        if (mSessionActive && getConnectionState() != ConnectionState.CONNECTED
                && getConnectionState() != ConnectionState.CONNECTING) {
            Log.i(TAG, "retrying connection");
            connect();
        }
    };

    // ---- lifecycle ----------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        mKeepAlive = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "QRV-Hub:session");
        mKeepAlive.setReferenceCounted(false);
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        // WIFI_MODE_FULL keeps Wi-Fi connected but allows its normal power saving
        // between packets (HIGH_PERF disabled it for the whole session).
        mWifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL, "QRV-Hub:session");
        mWifiLock.setReferenceCounted(false);
        mEventScreen = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "QRV-Hub:event-screen");
        mEventScreen.setReferenceCounted(false);
        mLockNotice = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "QRV-Hub:lock-notice");
        mLockNotice.setReferenceCounted(false);
        registerObserver(mLedObserver);
        initVolume();

        IntentFilter keys = new IntentFilter();
        keys.addAction(KEY_PREFIX + "ptt.down");
        keys.addAction(KEY_PREFIX + "ptt.up");
        keys.addAction(KEY_PREFIX + "power.down");
        keys.addAction(KEY_PREFIX + "power.up");
        keys.addAction(KEY_PREFIX + "volumeup.down");
        keys.addAction(KEY_PREFIX + "volumedown.down");
        registerReceiver(mKeyReceiver, keys);
        registerReceiver(mVolumeReceiver, new IntentFilter(ACTION_VOLUME_CHANGED));
        IntentFilter screen = new IntentFilter(Intent.ACTION_SCREEN_ON);
        screen.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(mScreenReceiver, screen);
        registerReceiver(mBatteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        registerReceiver(mHeadsetReceiver, new IntentFilter(Intent.ACTION_HEADSET_PLUG));
    }

    @Override
    public void onDestroy() {
        unregisterObserver(mLedObserver);
        AudioOutput.setPlaybackListener(null);
        setLed(0);
        if (mEventScreen != null && mEventScreen.isHeld()) mEventScreen.release();
        if (mLockNotice != null && mLockNotice.isHeld()) mLockNotice.release();
        try { unregisterReceiver(mKeyReceiver); } catch (IllegalArgumentException ignored) {}
        try { unregisterReceiver(mHeadsetReceiver); } catch (IllegalArgumentException ignored) {}
        try { unregisterReceiver(mVolumeReceiver); } catch (IllegalArgumentException ignored) {}
        try { unregisterReceiver(mScreenReceiver); } catch (IllegalArgumentException ignored) {}
        try { unregisterReceiver(mBatteryReceiver); } catch (IllegalArgumentException ignored) {}
        mMain.removeCallbacksAndMessages(null);
        releaseLocks();
        super.onDestroy();
    }

    void setUiListener(UiListener ui) {
        mUi = ui;
    }

    boolean isSessionActive() {
        return mSessionActive;
    }

    boolean isHeadsetPlugged() {
        return mHeadset;
    }

    X509Certificate[] rejectedChain() {
        return mRejectedChain;
    }

    // ---- session control ----------------------------------------------------

    /** Connection settings: fixed server, PTT, loudspeaker stream, pinned trust store. */
    static Bundle connectExtras(Context c, String user, String pass) throws Exception {
        Bundle b = new Bundle();
        b.putParcelable(EXTRAS_SERVER, new Server(-1, "QRV-Hub", Prefs.host(c), Prefs.port(c), user, pass));
        b.putString(EXTRAS_CLIENT_NAME, "QRV-Hub " + BuildConfig.VERSION_NAME);
        b.putInt(EXTRAS_TRANSMIT_MODE, Constants.TRANSMIT_PUSH_TO_TALK);
        b.putFloat(EXTRAS_DETECTION_THRESHOLD, 0.5f);
        b.putFloat(EXTRAS_AMPLITUDE_BOOST, 1.0f);
        b.putBoolean(EXTRAS_AUTO_RECONNECT, true);
        b.putInt(EXTRAS_AUTO_RECONNECT_DELAY, RECONNECT_DELAY_MS);
        b.putBoolean(EXTRAS_USE_OPUS, true);
        b.putInt(EXTRAS_INPUT_RATE, 48000);
        b.putInt(EXTRAS_INPUT_QUALITY, 40000);
        b.putBoolean(EXTRAS_FORCE_TCP, false);
        b.putBoolean(EXTRAS_USE_TOR, false);
        b.putStringArrayList(EXTRAS_ACCESS_TOKENS, new ArrayList<>());
        // MIC selects the headset microphone automatically when one is plugged in.
        b.putInt(EXTRAS_AUDIO_SOURCE, MediaRecorder.AudioSource.MIC);
        // STREAM_MUSIC in MODE_NORMAL plays on the loudspeaker, or the headset when plugged.
        b.putInt(EXTRAS_AUDIO_STREAM, AudioManager.STREAM_MUSIC);
        b.putInt(EXTRAS_FRAMES_PER_PACKET, 2);
        b.putString(EXTRAS_TRUST_STORE, Certs.trustStorePath(c));
        b.putString(EXTRAS_TRUST_STORE_PASSWORD, Certs.TRUST_PASS);
        b.putString(EXTRAS_TRUST_STORE_FORMAT, Certs.TRUST_FORMAT);
        b.putBoolean(EXTRAS_HALF_DUPLEX, false);
        b.putBoolean(EXTRAS_ENABLE_PREPROCESSOR, true);
        // "system" would switch the device to MODE_IN_COMMUNICATION (earpiece routing).
        b.putString(EXTRAS_ECHO_CANCELLATION_METHOD, "none");
        byte[] cert = Certs.clientCertificate(c);
        if (cert != null) b.putByteArray(EXTRAS_CERTIFICATE, cert);
        return b;
    }

    /** Starts (or restarts) the session with the saved credentials. */
    void startSession() throws Exception {
        mMain.removeCallbacks(mRetry);
        if (isReconnecting()) cancelReconnect();
        mRejectedChain = null;
        mSessionActive = true;
        // Keep the service started, not just bound, so it outlives the activity.
        startService(new Intent(this, QrvHubService.class));
        acquireLocks();
        updateNotification(getString(R.string.notif_connecting));
        configureExtras(connectExtras(this, Prefs.username(this), Prefs.password(this)));
        connect();
    }

    /** Disconnects and stops everything; used by Log out and Exit. */
    void endSession() {
        mSessionActive = false;
        mMain.removeCallbacks(mRetry);
        if (isReconnecting()) cancelReconnect();
        disconnect();
        releaseLocks();
        stopForeground(true);
        mForeground = false;
        stopSelf();
    }

    void retryNow() {
        if (!mSessionActive) return;
        ConnectionState st = getConnectionState();
        if (st == ConnectionState.CONNECTED || st == ConnectionState.CONNECTING) return;
        mMain.removeCallbacks(mRetry);
        if (isReconnecting()) cancelReconnect();
        connect();
    }

    /** User accepted the server certificate that failed the handshake. */
    void trustRejectedAndReconnect() throws Exception {
        X509Certificate[] chain = mRejectedChain;
        if (chain == null || chain.length == 0) return;
        Certs.trust(this, chain[0]);
        Log.i(TAG, "trusted new server certificate " + Certs.sha256(chain[0]));
        mRejectedChain = null;
        retryNow();
    }

    // ---- connection callbacks -----------------------------------------------

    @Override
    protected void connect() {
        mListening.clear();
        mPreSyncListening.clear();
        super.connect();
        HumlaConnection c = getConnection();
        if (c != null) c.addTCPMessageHandlers(mListenHandler);
    }

    @Override
    public void onConnectionSynchronized() {
        super.onConnectionSynchronized();
        if (mSessionActive) updateNotification(getString(R.string.notif_connected, Prefs.host(this)));
        Integer self = selfSession();
        if (self != null && mPreSyncListening.containsKey(self)) {
            mListening.addAll(mPreSyncListening.get(self));
        }
        mPreSyncListening.clear();
        reapplyListening();
        mMain.post(this::logOwnChannel);
    }

    @Override
    public void onConnectionHandshakeFailed(X509Certificate[] chain) {
        mRejectedChain = chain;
        Log.w(TAG, "server certificate not trusted: "
                + (chain != null && chain.length > 0 ? Certs.sha256(chain[0]) : "none"));
        super.onConnectionHandshakeFailed(chain);
        // Retrying would only fail the same way until the user decides.
        mMain.post(() -> { if (isReconnecting()) cancelReconnect(); });
    }

    @Override
    public void onConnectionDisconnected(HumlaException e) {
        super.onConnectionDisconnected(e);
        // The server forgets listeners with the session; they are re-applied on reconnect.
        mListening.clear();
        mPreSyncListening.clear();
        if (mUi != null) mUi.onListeningChanged();
        if (!mSessionActive) return;
        if (mRejectedChain != null) {
            if (isReconnecting()) cancelReconnect();
            updateNotification(getString(R.string.notif_cert));
            return;
        }
        boolean inUse = e != null && e.getReason() == HumlaException.HumlaDisconnectReason.REJECT
                && e.getReject() != null
                && e.getReject().getType() == Mumble.Reject.RejectType.UsernameInUse;
        if (inUse) {
            // Typically our own previous session that the server has not dropped yet.
            mMain.removeCallbacks(mRetry);
            mMain.postDelayed(mRetry, RECONNECT_DELAY_MS);
        }
        updateNotification(getString(isReconnecting() || inUse
                ? R.string.notif_reconnecting : R.string.notif_disconnected));
    }

    // ---- hardware keys ------------------------------------------------------

    private final BroadcastReceiver mKeyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (a == null || !a.startsWith(KEY_PREFIX)) return;
            switch (a.substring(KEY_PREFIX.length())) {
                case "ptt.down": talk(true); break;
                case "ptt.up": talk(false); break;
                case "power.down": onPowerKey(true); break;
                case "power.up": onPowerKey(false); break;
                case "volumeup.down": onKnob(1, "hotkey"); break;
                case "volumedown.down": onKnob(-1, "hotkey"); break;
                default:
                    break;
            }
        }
    };

    /** DPAD left/right: toggles the PTT lock; locking while transmitting stops at once. */
    boolean togglePttLock() {
        boolean locked = !Prefs.pttLocked(this);
        Prefs.setPttLocked(this, locked);
        Log.i(TAG, "PTT " + (locked ? "locked" : "unlocked"));
        if (locked && mPttHeld) talk(false);
        return locked;
    }

    private void talk(boolean on) {
        if (on && Prefs.pttLocked(this)) {
            showNotice();
            feedback(FB_LOCK, getString(R.string.fb_ptt_locked), getString(R.string.fb_ptt_locked_press));
            return;
        }
        // A release with nothing transmitting (e.g. the press was blocked by the lock) is ignored.
        if (!on && !mPttHeld) return;
        if (!isSynchronized()) {
            if (on) feedback(FB_WARN, getString(R.string.fb_not_connected), null);
            return;
        }
        // Talking while muted: unmute, so the reply is heard.
        if (on && isMuted()) {
            setVolumeLevel(UNMUTE_LEVEL);
            feedback(FB_UNMUTE, getString(R.string.fb_unmuted), getString(R.string.fb_volume_level, UNMUTE_LEVEL));
        }
        setTalkingState(on);
        mPttHeld = on;
        updateScreen();
        updateLed();
    }

    // ---- screen on while talking / receiving ----------------------------------

    // ---- mute: double press of the power button --------------------------------

    /**
     * The firmware broadcasts unipro.hotkey.power.down/up for every press of the
     * physical power button (key 140, even with the screen off), like PTT. Two
     * key-downs within DOUBLE_PRESS_MS are a double press. Screen on/off changes
     * are not used: the firmware switches the screen by itself, and that gave
     * false mutes.
     */
    private static final long DOUBLE_PRESS_MS = 700;
    private static final long OWN_CHANGE_MS = 1500;
    private long mOwnScreenChange;
    private long mLastPowerDown = -1;
    private boolean mPowerUp = true;

    private void markOwnScreenChange() {
        mOwnScreenChange = SystemClock.elapsedRealtime();
    }

    private void onPowerKey(boolean down) {
        if (!down) {
            mPowerUp = true;
            return;
        }
        if (!mPowerUp) return; // held: not a new press
        mPowerUp = false;
        long now = SystemClock.elapsedRealtime();
        if (mLastPowerDown >= 0 && now - mLastPowerDown <= DOUBLE_PRESS_MS) {
            mLastPowerDown = -1; // a third press starts a new pair
            Log.i(TAG, "power double press");
            toggleMute();
        } else {
            mLastPowerDown = now;
        }
    }

    /** A screen change made by the user cancels a pending "off after 5 s" from QRV-Hub. */
    private final BroadcastReceiver mScreenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (SystemClock.elapsedRealtime() - mOwnScreenChange < OWN_CHANGE_MS) return;
            mMain.removeCallbacks(mEventScreenOff);
            mScreenOffPending = false;
        }
    };

    /** Muted is simply volume 0: nothing is played, the LED shows the muted patterns. */
    boolean isMuted() {
        return mVolume <= 0;
    }

    /** Level PTT sets when pressed at volume 0, and the fallback when nothing was saved. */
    private static final int UNMUTE_LEVEL = 2;

    /**
     * Double press of the power button: save the level
     * and go to 0; at 0, restore the saved level.
     */
    private void toggleMute() {
        boolean mute = !isMuted();
        if (mute) {
            Prefs.setVolumeBeforeMute(this, mVolume);
            setVolumeLevel(0);
        } else {
            int back = Prefs.volumeBeforeMute(this);
            setVolumeLevel(back > 0 ? back : UNMUTE_LEVEL);
        }
        Log.i(TAG, "double click: volume " + mVolume);
        showNotice();
        feedback(mute ? FB_MUTE : FB_UNMUTE, getString(mute ? R.string.fb_muted : R.string.fb_unmuted),
                getString(mute ? R.string.fb_volume_saved : R.string.fb_volume_level,
                        mute ? Prefs.volumeBeforeMute(this) : mVolume));
    }

    // ---- screen on while talking / receiving ----------------------------------

    /**
     * Turn the screen on to show a notice card (PTT locked, muted), off again
     * after 5 s; if the screen is already on and in use, only the card shows.
     */
    private void showNotice() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm.isInteractive() && !mScreenOffPending) return; // already on and in use
        markOwnScreenChange();
        mLockNotice.acquire(1000); // wakes the screen; released on its own
        if (!mEventScreen.isHeld()) {
            mMain.removeCallbacks(mEventScreenOff);
            mMain.postDelayed(mEventScreenOff, EVENT_SCREEN_OFF_MS);
            mScreenOffPending = true;
        }
    }

    /** Called after PTT or the set of talkers changes, and when an option is toggled. */
    void updateScreen() {
        boolean want = (mPttHeld && Prefs.screenOnPtt(this))
                || (!mTalkers.isEmpty() && Prefs.screenOnReceive(this) && !isMuted());
        if (want) {
            // A reply within 5 s of the previous event continues that wake-up.
            boolean continued = mScreenOffPending;
            mMain.removeCallbacks(mEventScreenOff);
            mScreenOffPending = false;
            if (!mEventScreen.isHeld()) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                // Only an event that turned the screen on turns it off again; if the user
                // was already looking at it, the normal timeout applies.
                mEventWokeScreen = continued || !pm.isInteractive();
                if (!pm.isInteractive()) markOwnScreenChange();
                mEventScreen.acquire(10 * 60 * 1000); // safety cap
            }
        } else if (mEventScreen.isHeld()) {
            mEventScreen.release();
            if (mEventWokeScreen) {
                mMain.postDelayed(mEventScreenOff, EVENT_SCREEN_OFF_MS);
                mScreenOffPending = true;
            }
            mEventWokeScreen = false;
        }
    }

    // ---- LED: red while transmitting, green while someone we hear is talking ----

    private final HumlaObserver mLedObserver = new HumlaObserver() {
        @Override public void onUserTalkStateUpdated(IUser user) {
            final int session = user.getSession();
            final TalkState t = user.getTalkState();
            mMain.post(() -> {
                boolean talking = t == TalkState.TALKING || t == TalkState.SHOUTING || t == TalkState.WHISPERING;
                boolean self = isSynchronized() && session == getSessionId();
                if (talking && !self) mTalkers.add(session); else mTalkers.remove(session);
                onTalkersChanged();
            });
        }
        @Override public void onUserJoinedChannel(IUser user, IChannel n, IChannel o) {
            final int session = user.getSession();
            mMain.post(() -> {
                if (isSynchronized() && session == getSessionId()) logOwnChannel();
                updateLed();
            });
        }
        @Override public void onUserRemoved(IUser user, String reason) {
            final int session = user.getSession();
            mMain.post(() -> { mTalkers.remove(session); onTalkersChanged(); });
        }
        @Override public void onDisconnected(HumlaException e) {
            mMain.post(() -> { mTalkers.clear(); mPttHeld = false; onTalkersChanged(); });
        }
    };

    private void onTalkersChanged() {
        updateLed();
        updateScreen();
    }

    /** Diagnostics: own channel and the channels linked to it (their voice is heard too). */
    private void logOwnChannel() {
        try {
            IChannel ch = getSessionChannel();
            StringBuilder links = new StringBuilder();
            for (IChannel l : ch.getLinks()) links.append(l.getName()).append(' ');
            Log.i(TAG, "in channel " + ch.getName() + " links=[" + links.toString().trim() + "]");
        } catch (RuntimeException ignored) {
        }
    }

    /** LED states: 0 system (battery indicator), 1 red, 2 green, 3 yellow, LED_MUTED_IDLE slow yellow flash. */
    private static final int LED_MUTED_IDLE = 4;

    private void updateLed() {
        int want = 0;
        try {
            // Yellow (3) instead of green while muted: someone talks, you do not hear it.
            if (isSynchronized()) want = isTalking() ? 1 : !mTalkers.isEmpty() ? (isMuted() ? 3 : 2) : 0;
        } catch (RuntimeException ignored) {
        }
        // Idle and muted: where the firmware would flash green, flash yellow instead.
        if (want == 0 && isMuted() && systemLedIsGreen()) want = LED_MUTED_IDLE;
        setLed(want);
    }

    /** The Spender firmware drives the LED from this broadcast (same as the Spender Network app on this board). */
    private void setLed(int state) {
        if (state == mLed) return;
        mLed = state;
        Intent i = new Intent("unipro.led.set");
        if (state == LED_MUTED_IDLE) {
            // Same rhythm as the firmware's slow green flash (1 s on, 3 s off).
            i.putExtra("color", 3).putExtra("flicker", true)
                    .putExtra("delay_on", 1000).putExtra("delay_off", 3000);
        } else {
            i.putExtra("color", state);
        }
        sendBroadcast(i);
    }

    // Battery state, to know what the firmware's own LED pattern would be.
    private int mBatteryStatus = -1;
    private int mBatteryLevel = -1;

    /**
     * The firmware's BatteryService flashes green slowly when the battery is full,
     * charging at 90% or more, or discharging above the low-battery level; red while
     * charging below 90%, fast yellow when low. Only the green cases are replaced.
     */
    private boolean systemLedIsGreen() {
        if (mBatteryLevel < 0) return false;
        if (mBatteryStatus == BatteryManager.BATTERY_STATUS_FULL) return true;
        if (mBatteryStatus == BatteryManager.BATTERY_STATUS_CHARGING) return mBatteryLevel >= 90;
        int low = 15;
        int id = Resources.getSystem().getIdentifier("config_lowBatteryWarningLevel", "integer", "android");
        if (id != 0) low = Resources.getSystem().getInteger(id);
        return mBatteryLevel >= low;
    }

    private final BroadcastReceiver mBatteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            if (status == mBatteryStatus && level == mBatteryLevel) return;
            mBatteryStatus = status;
            mBatteryLevel = level;
            updateLed();
        }
    };

    /** Side button key event from the activity: n = 1 upper, 2 lower. */
    void onSideKey(int n, boolean down) {
        if (down) sideDown(n); else sideUp(n);
    }

    /** Any key other than PTT: the user is using the screen, keep it on. */
    /** A key other than PTT: the user is using the screen, so leave it to the normal timeout. */
    void cancelScreenOff() {
        mMain.removeCallbacks(mEventScreenOff);
        mScreenOffPending = false;
        mEventWokeScreen = false;
    }

    private void sideDown(int n) {
        if (mSideDown[n]) return;
        mSideDown[n] = true;
        mSideHeld[n] = false;
        mMain.postDelayed(n == 1 ? mHold1 : mHold2, HOLD_MS);
    }

    private void sideUp(int n) {
        if (!mSideDown[n]) return;
        mSideDown[n] = false;
        mMain.removeCallbacks(n == 1 ? mHold1 : mHold2);
        if (!mSideHeld[n]) jumpToFavorite(n);
    }

    private void onSideHold(int n) {
        if (!mSideDown[n]) return;
        mSideHeld[n] = true;
        saveFavorite(n);
    }

    private void jumpToFavorite(int n) {
        if (!isSynchronized()) {
            feedback(FB_WARN, getString(R.string.fb_not_connected), null);
            return;
        }
        int id = Prefs.favoriteId(this, n);
        String name = Prefs.favoriteName(this, n);
        if (id < 0 && name == null) {
            feedback(FB_STAR, getString(R.string.fb_fav_title, n), getString(R.string.fb_fav_unset_detail));
            return;
        }
        IChannel target = findChannel(id, name);
        if (target == null) {
            feedback(FB_WARN, getString(R.string.fb_fav_title, n), getString(R.string.fb_fav_missing_detail, name));
            return;
        }
        IChannel current = getSessionChannel();
        if (current != null && current.getId() == target.getId()) {
            feedback(FB_STAR, getString(R.string.fb_fav_title, n), getString(R.string.fb_fav_already_detail, target.getName()));
            return;
        }
        joinChannel(target.getId());
        feedback(FB_STAR, getString(R.string.fb_fav_title, n), target.getName());
    }

    private void saveFavorite(int n) {
        if (!isSynchronized()) {
            feedback(FB_WARN, getString(R.string.fb_not_connected), null);
            return;
        }
        IChannel ch = getSessionChannel();
        Prefs.setFavorite(this, n, ch.getId(), ch.getName());
        feedback(FB_STAR, getString(R.string.fb_fav_saved_title, n), ch.getName());
    }

    /** By ID if the name still matches, else by name, else by ID alone (channel renamed). */
    private IChannel findChannel(int id, String name) {
        IChannel byId = null;
        try {
            if (id >= 0) byId = getChannel(id);
        } catch (RuntimeException ignored) {
        }
        if (byId != null && (name == null || name.equals(byId.getName()))) return byId;
        if (name != null) {
            IChannel byName = findByName(getRootChannel(), name);
            if (byName != null) return byName;
        }
        return byId;
    }

    private static IChannel findByName(IChannel ch, String name) {
        if (ch == null) return null;
        if (name.equals(ch.getName())) return ch;
        List<? extends IChannel> subs = ch.getSubchannels();
        for (IChannel sub : subs) {
            IChannel hit = findByName(sub, name);
            if (hit != null) return hit;
        }
        return null;
    }


    // ---- headset ------------------------------------------------------------

    private final BroadcastReceiver mHeadsetReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            boolean plugged = i.getIntExtra("state", 0) == 1;
            boolean changed = plugged != mHeadset;
            mHeadset = plugged;
            Log.i(TAG, "headset " + (plugged ? "plugged" : "unplugged")
                    + " mic=" + i.getIntExtra("microphone", 0));
            if (changed && !isInitialStickyBroadcast()) {
                feedback(plugged ? FB_HEADSET : FB_SPEAKER,
                        getString(plugged ? R.string.fb_headset : R.string.fb_speaker),
                        getString(plugged ? R.string.fb_headset_detail : R.string.fb_speaker_detail));
            }
            if (mUi != null) mUi.onHeadsetChanged(plugged);
            // The headset has its own system volume and curve; set both once routing settles.
            applyVolume();
            mMain.removeCallbacks(mPinVolume);
            mMain.postDelayed(mPinVolume, 1000);
        }
    };

    // ---- volume -------------------------------------------------------------

    private static final String ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION";

    private void initVolume() {
        mAudio = (AudioManager) getSystemService(AUDIO_SERVICE);
        int level = Prefs.volume(this);
        int scale = Prefs.volumeScale(this);
        if (level > 0 && scale != VOLUME_MAX) {
            // Saved on an earlier scale: pick the level with the nearest loudness,
            // except that the quietest level stays the quietest.
            float oldDb = scale == 15 ? (level - 15) * 3f
                    : scale == 22 ? (level - 22) * 2f
                    : levelDb(level, -48f); // 27 levels from -48 dB
            int best = 1;
            for (int l = 1; l <= VOLUME_MAX; l++) {
                if (Math.abs(levelDb(l) - oldDb) < Math.abs(levelDb(best) - oldDb)) best = l;
            }
            level = level == 1 ? 1 : best;
            Prefs.setVolume(this, level);
        }
        Prefs.setVolumeScale(this, VOLUME_MAX);
        if (level < 0) {
            // First run: carry over the system level (step 1 -> 1, top step -> VOLUME_MAX).
            int sys = mAudio.getStreamVolume(AudioManager.STREAM_MUSIC);
            int max = mAudio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            level = sys <= 0 ? 0 : max <= 1 ? VOLUME_MAX
                    : Math.round(1 + (sys - 1) * (VOLUME_MAX - 1f) / (max - 1));
            Prefs.setVolume(this, level);
        }
        if (Prefs.takeLegacyMuted(this)) { // the old mute flag: muted is now volume 0
            Prefs.setVolumeBeforeMute(this, level);
            level = 0;
            Prefs.setVolume(this, 0);
        }
        mVolume = level;
        applyVolume();
        AudioOutput.setPlaybackListener(mPlayback);
        applySystemVolume();
    }

    private final AudioOutput.PlaybackListener mPlayback = new AudioOutput.PlaybackListener() {
        @Override public void onPlaybackStarting() {
            mVoicePlaying = true;
            applySystemVolume();
        }
        @Override public void onPlaybackStopped() {
            mVoicePlaying = false;
            applySystemVolume();
        }
    };

    int volume() {
        return mVolume;
    }

    /** One knob click from {@code src}; the same click seen from another source is ignored. */
    void onKnob(int dir, String src) {
        long now = SystemClock.uptimeMillis();
        if (dir == mKnobDir && !src.equals(mKnobSrc) && now - mKnobMs < KNOB_DEDUP_MS) {
            Log.v(TAG, "knob " + dir + " from " + src + " (same click as " + mKnobSrc + ")");
            return;
        }
        mKnobMs = now;
        mKnobDir = dir;
        mKnobSrc = src;
        int level = Math.max(0, Math.min(VOLUME_MAX, mVolume + dir));
        Log.i(TAG, "volume " + mVolume + " -> " + level + " (" + src + ")");
        if (level != mVolume) setVolumeLevel(level);
        if (mUi != null) mUi.onVolumeChanged(mVolume);
    }

    private void setVolumeLevel(int level) {
        mVolume = level;
        Prefs.setVolume(this, level);
        applyVolume();
        applySystemVolume();
        updateLed(); // volume 0 is muted: muted LED patterns
        updateScreen();
        if (mUi != null) mUi.onMuteChanged();
    }

    private float levelDb() {
        return levelDb(mVolume);
    }

    private static float levelDb(int level) {
        return levelDb(level, LEVEL1_DB);
    }

    private static float levelDb(int level, float level1Db) {
        return level <= FINE_LEVELS ? level1Db + (level - 1)
                : Math.min(0f, level1Db + (FINE_LEVELS - 1) + (level - FINE_LEVELS) * 2f);
    }

    private float[] stepTable() {
        float[] t = mHeadset ? HEADSET_STEP_DB : SPEAKER_STEP_DB;
        return mAudio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) == t.length ? t : null;
    }

    /** Lowest system step at or above the chosen level (the top step if the curve is unknown). */
    private int systemStep() {
        int max = mAudio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        float[] t = stepTable();
        if (t == null) return max;
        float db = levelDb();
        for (int k = 1; k <= max; k++) if (t[k - 1] >= db - 0.05f) return k;
        return max;
    }

    /** Gain for the rest of the way from the system step down to the chosen level. */
    private void applyVolume() {
        if (mVolume <= 0) {
            AudioOutput.setGain(0f);
            return;
        }
        float[] t = stepTable();
        float stepDb = t != null ? t[systemStep() - 1] : 0f;
        AudioOutput.setGain((float) Math.pow(10, (levelDb() - stepDb) / 20.0));
    }

    private int systemVolumeTarget() {
        return mVoicePlaying && mVolume > 0 ? systemStep() : 0;
    }

    /** The step for the chosen level while voice plays, 0 otherwise. Called from the main and the audio thread. */
    private void applySystemVolume() {
        int target = systemVolumeTarget();
        if (mAudio.getStreamVolume(AudioManager.STREAM_MUSIC) != target) {
            synchronized (mSelfSet) {
                mSelfSet.put(target, SystemClock.uptimeMillis());
            }
            mAudio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
        }
    }

    private boolean isSelfSet(int value) {
        synchronized (mSelfSet) {
            Long t = mSelfSet.get(value);
            return t != null && SystemClock.uptimeMillis() - t < SELF_SET_MS;
        }
    }

    /** With the screen off the firmware may adjust the system volume itself: count it, then undo it. */
    private final BroadcastReceiver mVolumeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            // This firmware links media, ring, system and call into one volume and
            // reports a knob change as the ring stream, so check the media level itself.
            int now = i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1);
            int prev = i.getIntExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", -1);
            if (mAudio.getStreamVolume(AudioManager.STREAM_MUSIC) == systemVolumeTarget()) return;
            if (now == systemVolumeTarget() || isSelfSet(now)) return;
            if (now != prev && prev >= 0) onKnob(now < prev ? -1 : 1, "system");
            applySystemVolume();
        }
    };

    // ---- channel listening (Mumble 1.4) ---------------------------------------

    boolean isListening(int channelId) {
        return mListening.contains(channelId);
    }

    Set<Integer> listeningChannels() {
        return new LinkedHashSet<>(mListening);
    }

    /** User action: start or stop listening to a channel; remembered for reconnects. */
    void setListening(final int channelId, boolean on) {
        if (!isSynchronized()) {
            feedback(FB_WARN, getString(R.string.fb_not_connected), null);
            return;
        }
        final IChannel ch;
        try {
            ch = getChannel(channelId);
        } catch (RuntimeException e) {
            return;
        }
        if (ch == null) return;
        LinkedHashMap<Integer, String> want = Prefs.listening(this);
        if (on) want.put(channelId, ch.getName()); else want.remove(channelId);
        Prefs.setListening(this, want);
        sendListen(channelId, on);
        feedback(on ? FB_LISTEN : FB_LISTEN_OFF, getString(on ? R.string.fb_listen_on : R.string.fb_listen_off), ch.getName());
        if (on) {
            // No confirmation from the server means it refused (permission or limit).
            mMain.postDelayed(() -> {
                if (!isSynchronized() || mListening.contains(channelId)) return;
                LinkedHashMap<Integer, String> w = Prefs.listening(this);
                if (w.remove(channelId) != null) Prefs.setListening(this, w);
                feedback(FB_WARN, getString(R.string.fb_listen_failed), ch.getName());
            }, LISTEN_CONFIRM_MS);
        }
    }

    /** User action: stop every listener and forget them, so none come back on reconnect. */
    void stopListeningAll() {
        Set<Integer> ids = new LinkedHashSet<>(mListening);
        ids.addAll(Prefs.listening(this).keySet());
        Prefs.setListening(this, new LinkedHashMap<>());
        if (isSynchronized()) {
            for (int id : ids) sendListen(id, false);
        }
        feedback(FB_LISTEN_OFF, getString(R.string.fb_listen_off), getString(R.string.fb_listen_all_detail));
    }

    /** After (re)connecting: ask again for every saved channel; forget channels that are gone. */
    private void reapplyListening() {
        LinkedHashMap<Integer, String> want = Prefs.listening(this);
        LinkedHashMap<Integer, String> fresh = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> e : want.entrySet()) {
            IChannel ch = findChannel(e.getKey(), e.getValue());
            if (ch == null) {
                Log.i(TAG, "listened channel gone: " + e.getValue());
                continue;
            }
            fresh.put(ch.getId(), ch.getName());
            if (!mListening.contains(ch.getId())) sendListen(ch.getId(), true);
        }
        if (!fresh.equals(want)) Prefs.setListening(this, fresh);
    }

    private void sendListen(int channelId, boolean on) {
        HumlaConnection c = getConnection();
        Integer self = selfSession();
        if (c == null || self == null) return;
        UnknownFieldSet fields = UnknownFieldSet.newBuilder()
                .addField(on ? FIELD_LISTEN_ADD : FIELD_LISTEN_REMOVE,
                        UnknownFieldSet.Field.newBuilder().addVarint(channelId).build())
                .build();
        Mumble.UserState msg = Mumble.UserState.newBuilder()
                .setSession(self)
                .setUnknownFields(fields)
                .build();
        c.sendTCPMessage(msg, HumlaTCPMessageType.UserState);
        Log.i(TAG, (on ? "listen add " : "listen remove ") + channelId);
    }

    private Integer selfSession() {
        if (!isSynchronized()) return null;
        try {
            return getSessionId();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Reads the server's listening updates (for our own session) and listener-limit refusals. */
    private final HumlaTCPMessageListener mListenHandler = new HumlaTCPMessageListener.Stub() {
        @Override
        public void messageUserState(Mumble.UserState msg) {
            if (!msg.hasSession()) return;
            final int session = msg.getSession();
            final List<Integer> add = varints(msg.getUnknownFields(), FIELD_LISTEN_ADD);
            final List<Integer> remove = varints(msg.getUnknownFields(), FIELD_LISTEN_REMOVE);
            if (add.isEmpty() && remove.isEmpty()) return;
            mMain.post(() -> onListeningState(session, add, remove));
        }

        @Override
        public void messagePermissionDenied(Mumble.PermissionDenied msg) {
            List<Integer> type = varints(msg.getUnknownFields(), 5);
            if (!type.isEmpty() && (type.get(0) == DENY_CHANNEL_LISTENER_LIMIT
                    || type.get(0) == DENY_USER_LISTENER_LIMIT)) {
                mMain.post(() -> feedback(FB_WARN, getString(R.string.fb_listen_limit), null));
            }
        }
    };

    private void onListeningState(int session, List<Integer> add, List<Integer> remove) {
        Integer self = selfSession();
        if (self == null) {
            Set<Integer> s = mPreSyncListening.get(session);
            if (s == null) {
                s = new HashSet<>();
                mPreSyncListening.put(session, s);
            }
            s.addAll(add);
            s.removeAll(remove);
            return;
        }
        if (session != self) return;
        mListening.addAll(add);
        mListening.removeAll(remove);
        Log.i(TAG, "listening confirmed: " + mListening);
        if (mUi != null) mUi.onListeningChanged();
    }

    /** repeated uint32 from the unknown-field set, packed or not. */
    private static List<Integer> varints(UnknownFieldSet set, int field) {
        List<Integer> out = new ArrayList<>();
        if (!set.hasField(field)) return out;
        UnknownFieldSet.Field f = set.getField(field);
        for (Long v : f.getVarintList()) out.add(v.intValue());
        for (ByteString packed : f.getLengthDelimitedList()) {
            CodedInputStream in = packed.newCodedInput();
            try {
                while (!in.isAtEnd()) out.add(in.readUInt32());
            } catch (IOException ignored) {
            }
        }
        return out;
    }

    // ---- feedback, notification, locks --------------------------------------

    private void feedback(int icon, String title, String detail) {
        Log.i(TAG, "feedback: " + title + (detail != null ? " / " + detail : ""));
        if (mUi != null) {
            mUi.onFeedback(icon, title, detail);
        } else {
            Toast.makeText(this, detail != null ? title + " " + detail : title, Toast.LENGTH_SHORT).show();
        }
    }

    @SuppressWarnings("deprecation")
    private void updateNotification(String text) {
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this)
                .setSmallIcon(R.drawable.ic_stat_qrvhub)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(pi)
                .setPriority(Notification.PRIORITY_LOW)
                .build();
        if (!mForeground) {
            startForeground(NOTIFICATION_ID, n);
            mForeground = true;
        } else {
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, n);
        }
    }

    private void acquireLocks() {
        if (!mKeepAlive.isHeld()) mKeepAlive.acquire();
        if (!mWifiLock.isHeld()) mWifiLock.acquire();
    }

    private void releaseLocks() {
        if (mKeepAlive != null && mKeepAlive.isHeld()) mKeepAlive.release();
        if (mWifiLock != null && mWifiLock.isHeld()) mWifiLock.release();
    }
}
