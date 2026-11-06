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

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.PixelFormat;
import android.view.animation.LinearInterpolator;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import se.lublin.humla.HumlaService;
import se.lublin.humla.IHumlaSession;
import se.lublin.humla.model.IChannel;
import se.lublin.humla.model.IUser;
import se.lublin.humla.model.TalkState;
import se.lublin.humla.protobuf.Mumble;
import se.lublin.humla.util.HumlaException;
import se.lublin.humla.util.HumlaObserver;
import se.lublin.humla.util.IHumlaObserver;

/**
 * The only screen: login, status, or the connected view (members page and
 * channels page). Driven entirely by the TC-7G keys:
 *   Up/Down move, OK select/join, Left/Right open the channels page, MENU (F1)
 *   menu, Back goes back one screen and never leaves the app. Leaving is done
 *   with Home, which QRV-Hub Launcher confirms. PTT and the knob are handled by the
 *   system/service; the side buttons are forwarded to the service from here.
 */
public class MainActivity extends Activity implements QrvHubService.UiListener {
    private static final String TAG = "QRV-Hub";
    private static final int KEY_PTT = 261;
    private static final int KEY_SIDE_UPPER = 260;
    private static final int KEY_SIDE_LOWER = 267;
    /** Every card (feedback, favourites, mute, PTT lock) stays up this long. */
    private static final long FEEDBACK_MS = 1500;
    /** Same as the Spender Network app: screen off 20 s after the last key press while QRV-Hub is in front. */
    private static final long SCREEN_TIMEOUT_MS = 20000;


    private enum Screen { LOGIN, STATUS, MAIN }

    private final Handler mHandler = new Handler();
    private QrvHubService mService;
    private Screen mScreen;
    private boolean mChannelsPage;
    private boolean mSelectCurrentChannel;
    private boolean mMenuOpen;
    private boolean mModal;
    private boolean mPreparing;


    private View mLoginPanel, mStatusPanel, mMainPanel;
    private EditText mUser, mPass;
    private TextView mLoginError, mLoginHint;
    private Button mConnect;
    private View mStatusSpinner, mStatusIcon;
    private ObjectAnimator mSpin;
    private TextView mStatusTitle, mStatusDetail, mStatusHint;
    private TextView mHeaderTitle, mHeaderSub;
    private View mHeaderListen, mHeaderHeadset, mHeaderLock, mHeaderMute;
    private TextView mHintLock;
    private View mTxBar, mHintMembers, mHintChannels;
    private View[] mTalkerLines;
    private ListView mList, mMenuList;
    private View mMenuOverlay, mToastOverlay;
    private TextView mMenuTitle, mToastTitle, mToastDetail;
    private ImageView mToastIcon;
    private View mVolumeBar, mVolumeTrack, mVolumeFill;
    private ImageView mVolumeIcon;
    private TextView mVolumeValue;
    private boolean mVolumeShown;
    private ItemAdapter mListAdapter, mMenuAdapter;
    private final List<IChannel> mChannelRows = new ArrayList<>();
    private final List<Runnable> mMenuActions = new ArrayList<>();

    private final Runnable mRefresh = this::refresh;
    private final Runnable mHideFeedback = () -> {
        if (!mModal) mToastOverlay.setVisibility(View.GONE);
    };
    private final Runnable mHideVolume = this::hideVolume;
    /** The firmware's volume popup covers the top 50 px (of 240x200), plus its shadow, for up to 3 s after a click. */
    private static final int VOLUME_STRIP_PX = 58;
    private static final long VOLUME_SHOW_MS = 3200;

    // ---- lifecycle ----------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        applyScreenTimeout(SCREEN_TIMEOUT_MS);

        mLoginPanel = findViewById(R.id.login_panel);
        mStatusPanel = findViewById(R.id.status_panel);
        mMainPanel = findViewById(R.id.main_panel);
        mUser = findViewById(R.id.user);
        mPass = findViewById(R.id.pass);
        mLoginError = findViewById(R.id.login_error);
        mLoginHint = findViewById(R.id.login_hint);
        mConnect = findViewById(R.id.connect);
        mStatusSpinner = findViewById(R.id.status_spinner);
        mStatusIcon = findViewById(R.id.status_icon);
        mStatusTitle = findViewById(R.id.status_title);
        mStatusDetail = findViewById(R.id.status_detail);
        mStatusHint = findViewById(R.id.status_hint);
        mHeaderTitle = findViewById(R.id.header_title);
        mHeaderSub = findViewById(R.id.header_sub);
        mHeaderListen = findViewById(R.id.header_listen);
        mHeaderHeadset = findViewById(R.id.header_headset);
        mHeaderLock = findViewById(R.id.header_lock);
        mHeaderMute = findViewById(R.id.header_mute);
        mHintLock = findViewById(R.id.hint_lock);
        mTxBar = findViewById(R.id.tx_bar);
        mHintMembers = findViewById(R.id.hint_members);
        mHintChannels = findViewById(R.id.hint_channels);
        mTalkerLines = new View[] {
                findViewById(R.id.talker1), findViewById(R.id.talker2), findViewById(R.id.talker3) };
        mList = findViewById(R.id.list);
        mMenuList = findViewById(R.id.menu_list);
        mMenuOverlay = findViewById(R.id.menu_overlay);
        mMenuTitle = findViewById(R.id.menu_title);
        mToastOverlay = findViewById(R.id.toast_overlay);
        mToastIcon = findViewById(R.id.toast_icon);
        mToastTitle = findViewById(R.id.toast_title);
        mToastDetail = findViewById(R.id.toast_detail);
        mVolumeBar = getLayoutInflater().inflate(R.layout.volume_overlay, null);
        mVolumeTrack = mVolumeBar.findViewById(R.id.volume_track);
        mVolumeFill = mVolumeBar.findViewById(R.id.volume_fill);
        mVolumeIcon = mVolumeBar.findViewById(R.id.volume_icon);
        mVolumeValue = mVolumeBar.findViewById(R.id.volume_value);
        ((TextView) findViewById(R.id.login_host)).setText(Prefs.host(this));

        mListAdapter = new ItemAdapter(this);
        mList.setAdapter(mListAdapter);
        mList.setOnItemClickListener((p, v, pos, id) -> onListClick(pos));
        mMenuAdapter = new ItemAdapter(this);
        mMenuList.setAdapter(mMenuAdapter);
        mMenuList.setOnItemClickListener((p, v, pos, id) -> {
            closeMenu();
            if (pos < mMenuActions.size()) mMenuActions.get(pos).run();
        });

        mConnect.setOnClickListener(v -> onConnectPressed());
        mPass.setOnEditorActionListener((v, actionId, ev) -> {
            hideKeyboard();
            mConnect.requestFocus();
            return true;
        });
        mUser.setText(Prefs.username(this));

        // First start: create the client identity in the background right away.
        Certs.ensureClientCertificate(this, null);

        Intent svc = new Intent(this, QrvHubService.class);
        startService(svc);
        bindService(svc, mConnection, BIND_AUTO_CREATE);
        refresh();
    }

    @Override
    protected void onDestroy() {
        hideVolume();
        mHandler.removeCallbacksAndMessages(null);
        if (mService != null) {
            mService.unregisterObserver(mObserver);
            mService.setUiListener(null);
        }
        unbindService(mConnection);
        super.onDestroy();
    }

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            mService = (QrvHubService) ((HumlaService.HumlaBinder) binder).getService();
            mService.registerObserver(mObserver);
            mService.setUiListener(MainActivity.this);
            if (Prefs.isLoggedIn(MainActivity.this) && !mService.isSessionActive()) {
                startConnect();
            }
            refresh();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mService = null;
        }
    };

    private final IHumlaObserver mObserver = new HumlaObserver() {
        @Override public void onConnected() { scheduleRefresh(); }
        @Override public void onConnecting() { scheduleRefresh(); }
        @Override public void onDisconnected(HumlaException e) { scheduleRefresh(); }
        @Override public void onTLSHandshakeFailed(X509Certificate[] chain) { scheduleRefresh(); }
        @Override public void onChannelAdded(IChannel channel) { scheduleRefresh(); }
        @Override public void onChannelStateUpdated(IChannel channel) { scheduleRefresh(); }
        @Override public void onChannelRemoved(IChannel channel) { scheduleRefresh(); }
        @Override public void onUserConnected(IUser user) { scheduleRefresh(); }
        @Override public void onUserStateUpdated(IUser user) { scheduleRefresh(); }
        @Override public void onUserTalkStateUpdated(IUser user) { scheduleRefresh(); }
        @Override public void onUserJoinedChannel(IUser user, IChannel newChannel, IChannel oldChannel) { scheduleRefresh(); }
        @Override public void onUserRemoved(IUser user, String reason) { scheduleRefresh(); }
        @Override public void onPermissionDenied(String reason) {
            mHandler.post(() -> showFeedback(QrvHubService.FB_WARN, getString(R.string.fb_denied), reason));
        }
    };

    /** Coalesces bursts of model events (the hub has dozens of users). */
    private void scheduleRefresh() {
        mHandler.removeCallbacks(mRefresh);
        mHandler.postDelayed(mRefresh, 80);
    }

    // ---- UiListener (service -> UI) -----------------------------------------

    @Override
    public void onFeedback(int icon, String title, String detail) {
        mHandler.post(() -> showFeedback(icon, title, detail));
    }

    @Override
    public void onHeadsetChanged(boolean plugged) {
        scheduleRefresh();
    }

    @Override
    public void onListeningChanged() {
        scheduleRefresh();
    }

    @Override
    public void onMuteChanged() {
        scheduleRefresh();
    }

    @Override
    public void onVolumeChanged(int level) {
        mHandler.post(() -> showVolume(level));
    }



    // ---- connecting ---------------------------------------------------------

    private void onConnectPressed() {
        String user = mUser.getText().toString().trim();
        String pass = mPass.getText().toString();
        if (user.isEmpty()) {
            setLoginError(getString(R.string.err_username));
            mUser.requestFocus();
            return;
        }
        hideKeyboard();
        setLoginError(null);
        Prefs.saveCredentials(this, user, pass);
        startConnect();
    }

    /** The error replaces the key hint on the login screen. */
    private void setLoginError(String error) {
        boolean has = error != null && !error.isEmpty();
        mLoginError.setText(has ? error : "");
        mLoginError.setVisibility(has ? View.VISIBLE : View.GONE);
        mLoginHint.setVisibility(has ? View.GONE : View.VISIBLE);
    }

    private void startConnect() {
        if (mService == null) return; // onServiceConnected will call again
        mPreparing = true;
        refresh();
        Certs.ensureClientCertificate(this, () -> {
            mPreparing = false;
            if (mService != null && Prefs.isLoggedIn(this)) {
                try {
                    mService.startSession();
                } catch (Exception e) {
                    Log.e(TAG, "startSession failed", e);
                    showFeedback(QrvHubService.FB_WARN, getString(R.string.fb_error), String.valueOf(e.getMessage()));
                }
            }
            refresh();
        });
    }

    // ---- rendering ----------------------------------------------------------

    private void refresh() {
        mHandler.removeCallbacks(mRefresh);
        if (!Prefs.isLoggedIn(this)) {
            show(Screen.LOGIN);
            return;
        }
        if (mService == null) {
            showStatus(getString(R.string.st_starting), null, R.string.hint_options, true);
            return;
        }
        if (mPreparing) {
            // Only the very first start has to generate the client identity.
            if (Certs.clientCertificate(this) == null) {
                showStatus(getString(R.string.st_preparing), getString(R.string.st_preparing_detail), R.string.hint_options, true);
            } else {
                showStatus(getString(R.string.st_connecting), hostLine(), R.string.hint_options, true);
            }
            return;
        }
        if (mService.isSynchronized()) {
            show(Screen.MAIN);
            renderMain();
            return;
        }
        X509Certificate[] chain = mService.rejectedChain();
        if (chain != null && chain.length > 0) {
            showStatus(getString(R.string.st_cert_changed), shortFingerprint(chain[0]), R.string.hint_trust, false);
            return;
        }
        HumlaService.ConnectionState state = mService.getConnectionState();
        if (state == HumlaService.ConnectionState.CONNECTING) {
            showStatus(getString(R.string.st_connecting), hostLine(), R.string.hint_options, true);
            return;
        }
        HumlaException err = mService.getConnectionError();
        if (err != null && err.getReason() == HumlaException.HumlaDisconnectReason.REJECT
                && err.getReject() != null) {
            Mumble.Reject.RejectType type = err.getReject().getType();
            int loginError = 0;
            if (type == Mumble.Reject.RejectType.WrongUserPW) loginError = R.string.err_wrong_user_pw;
            else if (type == Mumble.Reject.RejectType.WrongServerPW) loginError = R.string.err_wrong_server_pw;
            else if (type == Mumble.Reject.RejectType.InvalidUsername) loginError = R.string.err_invalid_username;
            if (loginError != 0) {
                // Back to the login screen with the username kept.
                mService.endSession();
                Prefs.logOut(this);
                mPass.setText("");
                setLoginError(getString(loginError));
                show(Screen.LOGIN);
                return;
            }
            if (type == Mumble.Reject.RejectType.UsernameInUse) {
                showStatus(getString(R.string.st_in_use), getString(R.string.st_in_use_detail), R.string.hint_retry, true);
            } else {
                showStatus(getString(R.string.st_rejected), err.getReject().getReason(), R.string.hint_retry, false);
            }
            return;
        }
        if (mService.isReconnecting() || err != null) {
            showStatus(getString(R.string.st_lost), getString(R.string.st_lost_detail), R.string.hint_retry, true);
            return;
        }
        showStatus(getString(R.string.st_disconnected), hostLine(), R.string.hint_retry, false);
    }

    private void show(Screen s) {
        if (mScreen == s) return;
        mScreen = s;
        closeMenu();
        mLoginPanel.setVisibility(s == Screen.LOGIN ? View.VISIBLE : View.GONE);
        mStatusPanel.setVisibility(s == Screen.STATUS ? View.VISIBLE : View.GONE);
        mMainPanel.setVisibility(s == Screen.MAIN ? View.VISIBLE : View.GONE);
        switch (s) {
            case LOGIN:
                if (mUser.getText().length() == 0) mUser.requestFocus(); else mPass.requestFocus();
                break;
            case STATUS:
                mStatusPanel.requestFocus();
                break;
            case MAIN:
                mChannelsPage = false;
                applyPageMode();
                mList.setSelection(0);
                break;
        }
    }

    /** Spinner while waiting (busy), warning icon otherwise; MENU hint text at the bottom. */
    private void showStatus(String title, String detail, int hint, boolean busy) {
        show(Screen.STATUS);
        mStatusSpinner.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (busy) {
            if (mSpin == null) {
                // Own spinner: a rotating refresh icon (the system one does not draw on this ROM).
                mSpin = ObjectAnimator.ofFloat(mStatusSpinner, "rotation", 0f, 360f);
                mSpin.setDuration(1000);
                mSpin.setRepeatCount(ValueAnimator.INFINITE);
                mSpin.setInterpolator(new LinearInterpolator());
            }
            if (!mSpin.isStarted()) mSpin.start();
        } else if (mSpin != null) {
            mSpin.cancel();
        }
        mStatusIcon.setVisibility(busy ? View.GONE : View.VISIBLE);
        mStatusTitle.setText(title);
        mStatusDetail.setText(detail != null ? detail : "");
        mStatusDetail.setVisibility(detail != null && !detail.isEmpty() ? View.VISIBLE : View.GONE);
        mStatusHint.setText(hint);
    }

    private String hostLine() {
        return Prefs.host(this) + (Prefs.isTestServer(this) ? " · " + getString(R.string.test_prefix) : "");
    }

    private void renderMain() {
        IHumlaSession s;
        IChannel current;
        int self;
        try {
            s = mService.HumlaSession();
            current = s.getSessionChannel();
            self = s.getSessionId();
        } catch (RuntimeException e) {
            return;
        }
        boolean tx = s.isTalking();
        Set<Integer> listening = mService.listeningChannels();
        int others = 0;
        for (int id : listening) if (id != current.getId()) others++;

        List<IUser> users = new ArrayList<>(current.getUsers());
        Collections.sort(users, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));

        // Header: title, facts in words, status icons.
        StringBuilder sub = new StringBuilder();
        if (Prefs.isTestServer(this)) sub.append(getString(R.string.test_prefix)).append(" · ");
        List<ItemAdapter.Item> rows = new ArrayList<>();
        if (!mChannelsPage) {
            mHeaderTitle.setText(current.getName());
            sub.append(getResources().getQuantityString(R.plurals.members_count, users.size(), users.size()));
        } else {
            mChannelRows.clear();
            addChannels(s.getRootChannel(), 0, current.getId(), listening, rows);
            mHeaderTitle.setText(R.string.channels_title);
            sub.append(getResources().getQuantityString(R.plurals.channels_count, mChannelRows.size(), mChannelRows.size()))
                    .append(" · ").append(getString(R.string.you_are_in, current.getName()));
        }
        if (!mChannelsPage && others > 0) sub.append(" · ").append(getString(R.string.listening_to, others));
        mHeaderSub.setText(sub);
        mHeaderListen.setVisibility(others > 0 ? View.VISIBLE : View.GONE);
        mHeaderHeadset.setVisibility(mService.isHeadsetPlugged() ? View.VISIBLE : View.GONE);
        boolean locked = Prefs.pttLocked(this);
        mHeaderLock.setVisibility(locked ? View.VISIBLE : View.GONE);
        mHeaderMute.setVisibility(mService.isMuted() ? View.VISIBLE : View.GONE);
        mHintLock.setText(locked ? R.string.hint_unlock : R.string.hint_lock);

        // Talkers: own channel first (no tag), then listened channels (tagged).
        List<String[]> talkers = new ArrayList<>();
        for (IUser u : users) {
            if (u.getSession() != self && isTalking(u)) talkers.add(new String[] { u.getName(), null });
        }
        for (int id : listening) {
            if (id == current.getId()) continue;
            IChannel lc;
            try {
                lc = s.getChannel(id);
            } catch (RuntimeException e) {
                continue;
            }
            if (lc == null) continue;
            for (IUser u : lc.getUsers()) {
                if (u.getSession() != self && isTalking(u)) talkers.add(new String[] { u.getName(), lc.getName() });
            }
        }

        if (!mChannelsPage) {
            for (IUser u : users) {
                boolean me = u.getSession() == self;
                rows.add(ItemAdapter.Item.member(u.getName(), me, me ? tx : isTalking(u)));
            }
        }
        mListAdapter.set(rows);
        if (mChannelsPage && mSelectCurrentChannel) {
            mSelectCurrentChannel = false;
            for (int i = 0; i < mChannelRows.size(); i++) {
                if (mChannelRows.get(i).getId() == current.getId()) {
                    mList.setSelection(i);
                    break;
                }
            }
        }

        // Bottom: red TX bar, one green line per speaker, otherwise the key hints.
        mTxBar.setVisibility(tx ? View.VISIBLE : View.GONE);
        boolean hints = !tx && talkers.isEmpty();
        mHintMembers.setVisibility(hints && !mChannelsPage ? View.VISIBLE : View.GONE);
        mHintChannels.setVisibility(hints && mChannelsPage ? View.VISIBLE : View.GONE);
        for (int i = 0; i < mTalkerLines.length; i++) {
            View line = mTalkerLines[i];
            if (i >= talkers.size()) {
                line.setVisibility(View.GONE);
                continue;
            }
            String name;
            String tag;
            if (i == mTalkerLines.length - 1 && talkers.size() > mTalkerLines.length) {
                // Fold the remaining speakers into the last line.
                List<String> rest = new ArrayList<>();
                for (String[] t : talkers.subList(i, talkers.size())) rest.add(t[0]);
                name = android.text.TextUtils.join(", ", rest);
                tag = null;
            } else {
                name = talkers.get(i)[0];
                tag = talkers.get(i)[1];
            }
            TextView nv = line.findViewById(R.id.talker_name);
            // Re-setting identical text would restart the scrolling.
            if (!name.contentEquals(nv.getText())) nv.setText(name);
            nv.setSelected(true); // marquee scrolls only while selected
            TextView tv = line.findViewById(R.id.talker_tag);
            tv.setVisibility(tag != null ? View.VISIBLE : View.GONE);
            if (tag != null) tv.setText(tag);
            line.setVisibility(View.VISIBLE);
        }
    }

    private void addChannels(IChannel ch, int depth, int currentId, Set<Integer> listening,
                             List<ItemAdapter.Item> rows) {
        if (ch == null) return;
        mChannelRows.add(ch);
        rows.add(ItemAdapter.Item.channel(ch.getName(), depth, ch.getUsers().size(),
                ch.getId() == currentId, listening.contains(ch.getId())));
        List<IChannel> subs = new ArrayList<>(ch.getSubchannels());
        Collections.sort(subs, (a, b) -> a.getPosition() != b.getPosition()
                ? Integer.compare(a.getPosition(), b.getPosition())
                : a.getName().compareToIgnoreCase(b.getName()));
        for (IChannel sub : subs) addChannels(sub, depth + 1, currentId, listening, rows);
    }

    /**
     * Per-window screen timeout. WindowManager.LayoutParams.userActivityTimeout is
     * a hidden field (API 17+); while this window has focus the system uses the
     * shorter of it and the global setting, and it stops applying when QRV-Hub is
     * not in front, so the chooser keeps its own timeout.
     */
    private void applyScreenTimeout(long ms) {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        try {
            WindowManager.LayoutParams.class.getField("userActivityTimeout").setLong(lp, ms);
            getWindow().setAttributes(lp);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "per-window screen timeout not available", e);
        }
    }

    private static boolean isTalking(IUser u) {
        TalkState t = u.getTalkState();
        return t == TalkState.TALKING || t == TalkState.SHOUTING || t == TalkState.WHISPERING;
    }

    private static String shortFingerprint(X509Certificate cert) {
        String fp = Certs.sha256(cert);
        return fp.length() > 23 ? fp.substring(0, 23) + "…" : fp;
    }

    // ---- actions ------------------------------------------------------------

    private void onListClick(int pos) {
        if (mScreen != Screen.MAIN || !mChannelsPage || pos >= mChannelRows.size()) return;
        IChannel ch = mChannelRows.get(pos);
        try {
            IHumlaSession s = mService.HumlaSession();
            if (ch.getId() != s.getSessionChannel().getId()) {
                s.joinChannel(ch.getId());
                showFeedback(QrvHubService.FB_INFO, getString(R.string.fb_join), ch.getName());
            }
        } catch (RuntimeException e) {
            return;
        }
        mChannelsPage = false;
        applyPageMode();
        mList.setSelection(0);
        refresh();
    }

    private void showChannelsPage() {
        if (mChannelsPage) return;
        mChannelsPage = true;
        mSelectCurrentChannel = true;
        applyPageMode();
        refresh();
    }

    private void showMembersPage() {
        mChannelsPage = false;
        applyPageMode();
        refresh();
        mList.setSelection(0);
    }

    /**
     * Channels page: a selectable list with the amber cursor. Members page: no
     * cursor at all (selecting a member does nothing); Up/Down scroll instead,
     * and the list is not focusable so refreshes keep the scroll position.
     */
    private void applyPageMode() {
        mList.setSelector(mChannelsPage ? getResources().getDrawable(R.drawable.sel_row)
                : new android.graphics.drawable.ColorDrawable(0));
        mList.setFocusable(mChannelsPage);
        if (mChannelsPage) mList.requestFocus();
        else mList.clearFocus();
    }

    /** One member row per press. */
    private void scrollMembers(int direction) {
        int row = Math.round(37 * getResources().getDisplayMetrics().density);
        mList.smoothScrollBy(direction * row, 120);
    }

    private IChannel currentChannel() {
        try {
            return mService.HumlaSession().getSessionChannel();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void openMenu() {
        if (mScreen == null || mScreen == Screen.LOGIN || mService == null) return;
        List<ItemAdapter.Item> rows = new ArrayList<>();
        mMenuActions.clear();
        // Icons in the text colour so they stay visible on the blue selection bar.
        int blue = getResources().getColor(R.color.text);
        int sub = getResources().getColor(R.color.text);
        String title = getString(R.string.menu_title_app);
        if (mScreen == Screen.MAIN) {
            IChannel target = null;
            if (mChannelsPage) {
                int pos = mList.getSelectedItemPosition();
                if (pos >= 0 && pos < mChannelRows.size()) target = mChannelRows.get(pos);
            } else {
                target = currentChannel();
            }
            if (target != null) {
                title = target.getName();
                final int id = target.getId();
                final boolean on = mService.isListening(id);
                addMenu(rows, getString(on ? R.string.menu_unlisten : R.string.menu_listen),
                        on ? R.drawable.ic_speaker_off : R.drawable.ic_speaker, blue, () -> {
                            mService.setListening(id, !on);
                            refresh();
                        });
            }
            if (mChannelsPage && !mService.listeningChannels().isEmpty()) {
                addMenu(rows, getString(R.string.menu_unlisten_all), R.drawable.ic_speaker_off, sub, () -> {
                    mService.stopListeningAll();
                    refresh();
                });
            }
        }
        if (mScreen == Screen.STATUS) {
            if (mService.rejectedChain() != null) {
                addMenu(rows, getString(R.string.menu_trust), R.drawable.ic_shield,
                        getResources().getColor(R.color.amber), () -> {
                            try {
                                mService.trustRejectedAndReconnect();
                            } catch (Exception e) {
                                showFeedback(QrvHubService.FB_WARN, getString(R.string.fb_error), String.valueOf(e.getMessage()));
                            }
                            refresh();
                        });
            }
            addMenu(rows, getString(R.string.menu_retry), R.drawable.ic_refresh, sub, () -> { mService.retryNow(); refresh(); });
        }
        if (mScreen == Screen.MAIN) {
            final boolean scr = Prefs.screenOnPtt(this);
            addMenu(rows, getString(scr ? R.string.menu_screen_ptt_on : R.string.menu_screen_ptt_off),
                    R.drawable.ic_screen, sub, () -> {
                        Prefs.setScreenOnPtt(this, !scr);
                        mService.updateScreen();
                        showFeedback(QrvHubService.FB_INFO, getString(R.string.fb_screen_ptt),
                                getString(!scr ? R.string.on : R.string.off));
                    });
            final boolean rx = Prefs.screenOnReceive(this);
            addMenu(rows, getString(rx ? R.string.menu_screen_rx_on : R.string.menu_screen_rx_off),
                    R.drawable.ic_screen, sub, () -> {
                        Prefs.setScreenOnReceive(this, !rx);
                        mService.updateScreen();
                        showFeedback(QrvHubService.FB_INFO, getString(R.string.fb_screen_rx),
                                getString(!rx ? R.string.on : R.string.off));
                    });
        }
        addMenu(rows, getString(R.string.menu_about), R.drawable.ic_info, sub, this::showAbout);
        addMenu(rows, getString(R.string.menu_logout), R.drawable.ic_power, sub, this::logOut);
        mMenuTitle.setText(title);
        mMenuAdapter.set(rows);
        mMenuOverlay.setVisibility(View.VISIBLE);
        mMenuOpen = true;
        mMenuList.requestFocus();
        mMenuList.setSelection(0);
    }

    private void addMenu(List<ItemAdapter.Item> rows, String label, int icon, int tint, Runnable action) {
        rows.add(ItemAdapter.Item.menu(label, icon, tint));
        mMenuActions.add(action);
    }

    private void closeMenu() {
        if (!mMenuOpen) return;
        mMenuOpen = false;
        mMenuOverlay.setVisibility(View.GONE);
        if (mScreen == Screen.MAIN && mChannelsPage) mList.requestFocus();
        else if (mScreen == Screen.STATUS) mStatusPanel.requestFocus();
    }

    private void logOut() {
        if (mService != null) mService.endSession();
        Prefs.logOut(this);
        mPass.setText("");
        setLoginError(null);
        refresh();
    }

    private void showAbout() {
        String server = getString(R.string.about_not_connected);
        if (mService != null && mService.isSynchronized()) {
            try {
                server = getString(R.string.about_server, mService.HumlaSession().getServerRelease());
            } catch (RuntimeException ignored) {
            }
        }
        showModal(getString(R.string.about_title, BuildConfig.VERSION_NAME),
                getString(R.string.about_detail, hostLine(), Prefs.username(this), server,
                        getString(mService != null && mService.isHeadsetPlugged() ? R.string.yes : R.string.no)));
    }

    // ---- feedback card --------------------------------------------------------

    private void setCard(int icon, String title, String detail, float detailSp) {
        int res, tint;
        switch (icon) {
            case QrvHubService.FB_STAR: res = R.drawable.ic_star; tint = R.color.amber; break;
            case QrvHubService.FB_LISTEN: res = R.drawable.ic_speaker; tint = R.color.blue; break;
            case QrvHubService.FB_LISTEN_OFF: res = R.drawable.ic_speaker_off; tint = R.color.sub; break;
            case QrvHubService.FB_HEADSET: res = R.drawable.ic_headset; tint = R.color.blue; break;
            case QrvHubService.FB_SPEAKER: res = R.drawable.ic_speaker; tint = R.color.blue; break;
            case QrvHubService.FB_WARN: res = R.drawable.ic_warning; tint = R.color.amber; break;
            case QrvHubService.FB_LOCK: res = R.drawable.ic_lock; tint = R.color.amber; break;
            case QrvHubService.FB_UNLOCK: res = R.drawable.ic_lock_open; tint = R.color.blue; break;
            case QrvHubService.FB_MUTE: res = R.drawable.ic_speaker_off; tint = R.color.amber; break;
            case QrvHubService.FB_UNMUTE: res = R.drawable.ic_speaker; tint = R.color.blue; break;
            default: res = R.drawable.ic_info; tint = R.color.blue; break;
        }
        mToastIcon.setImageResource(res);
        mToastIcon.setColorFilter(getResources().getColor(tint));
        mToastTitle.setText(title);
        mToastDetail.setTextSize(detailSp);
        mToastDetail.setText(detail != null ? detail : "");
        mToastDetail.setVisibility(detail != null && !detail.isEmpty() ? View.VISIBLE : View.GONE);
        mToastOverlay.setVisibility(View.VISIBLE);
    }

    private void showFeedback(int icon, String title, String detail) {
        if (mModal) return;
        setCard(icon, title, detail, 18);
        mHandler.removeCallbacks(mHideFeedback);
        mHandler.postDelayed(mHideFeedback, FEEDBACK_MS);
    }

    /**
     * Slider across the top: speaker icon, fill for level/VOLUME_MAX, and the number.
     * Drawn in a system overlay window (above the status-bar panel layer) so it hides
     * the firmware's own volume popup, which apps cannot switch off.
     */
    private void showVolume(int level) {
        if (!mVolumeShown) {
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, VOLUME_STRIP_PX,
                    WindowManager.LayoutParams.TYPE_SYSTEM_ERROR,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.OPAQUE);
            lp.gravity = Gravity.TOP;
            lp.setTitle("QRV-Hub volume");
            try {
                getWindowManager().addView(mVolumeBar, lp);
                mVolumeShown = true;
            } catch (RuntimeException e) {
                Log.w(TAG, "volume overlay failed", e);
                return;
            }
        }
        mVolumeIcon.setImageResource(level > 0 ? R.drawable.ic_speaker : R.drawable.ic_speaker_off);
        mVolumeIcon.setColorFilter(getResources().getColor(level > 0 ? R.color.blue : R.color.sub));
        mVolumeValue.setText(String.valueOf(level));
        mVolumeTrack.post(() -> {
            ViewGroup.LayoutParams lp = mVolumeFill.getLayoutParams();
            lp.width = mVolumeTrack.getWidth() * level / QrvHubService.VOLUME_MAX;
            mVolumeFill.setLayoutParams(lp);
        });
        mHandler.removeCallbacks(mHideVolume);
        mHandler.postDelayed(mHideVolume, VOLUME_SHOW_MS);
    }

    private void hideVolume() {
        mHandler.removeCallbacks(mHideVolume);
        if (!mVolumeShown) return;
        mVolumeShown = false;
        try {
            getWindowManager().removeView(mVolumeBar);
        } catch (RuntimeException ignored) {
        }
    }

    /** Stays until any key is pressed. */
    private void showModal(String title, String detail) {
        mModal = true;
        mHandler.removeCallbacks(mHideFeedback);
        setCard(QrvHubService.FB_INFO, title, detail, 15);
    }

    private void dismissOverlay() {
        mModal = false;
        mToastOverlay.setVisibility(View.GONE);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        View f = getCurrentFocus();
        if (imm != null && f != null) imm.hideSoftInputFromWindow(f.getWindowToken(), 0);
    }

    // ---- keys -------------------------------------------------------------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean up = e.getAction() == KeyEvent.ACTION_UP;
        boolean firstDown = e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0;

        // Side buttons: key events arrive with the display on or off.
        if (k == KEY_SIDE_UPPER || k == KEY_SIDE_LOWER) {
            if (mService != null && e.getRepeatCount() == 0) {
                mService.onSideKey(k == KEY_SIDE_UPPER ? 1 : 2, e.getAction() == KeyEvent.ACTION_DOWN);
            }
            return true;
        }
        // PTT and the knob are handled by the service from the framework broadcasts.
        if (k == KEY_PTT || k == KeyEvent.KEYCODE_F11 || k == KeyEvent.KEYCODE_F12) {
            return true;
        }
        // Standard volume keys (the TC-7G knob itself arrives as F11/F12 above and
        // reaches the service as a unipro.hotkey broadcast): QRV-Hub's own volume.
        // Consumed so the system volume and its popup stay out.
        if (k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (mService == null) return super.dispatchKeyEvent(e);
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                mService.onKnob(k == KeyEvent.KEYCODE_VOLUME_UP ? 1 : -1, "key");
            }
            return true;
        }
        // Any other key means the user is using the screen: cancel the post-PTT screen-off.
        if (mService != null) mService.cancelScreenOff();
        // With the display off the framework still delivers D-pad/OK/Back here;
        // ignore them so pocket presses cannot join channels or exit unseen.
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null && !pm.isInteractive()) {
            return true;
        }
        if (mModal) {
            if (up) dismissOverlay();
            return true;
        }
        if (k == KeyEvent.KEYCODE_F1 || k == KeyEvent.KEYCODE_MENU) {
            if (up) {
                if (mMenuOpen) closeMenu(); else openMenu();
            }
            return true;
        }
        if (mMenuOpen) {
            if (k == KeyEvent.KEYCODE_BACK) {
                if (up) closeMenu();
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
        // Back switches between the members and channels pages and never leaves
        // the app (Home does, via QRV-Hub Launcher). Elsewhere it does nothing.
        if (k == KeyEvent.KEYCODE_BACK) {
            if (up && mScreen == Screen.MAIN) {
                if (mChannelsPage) showMembersPage(); else showChannelsPage();
            }
            return true;
        }
        // Members page: Up/Down scroll the list; there is no cursor.
        if (mScreen == Screen.MAIN && !mChannelsPage
                && (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN)) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) scrollMembers(k == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
            return true;
        }
        // Left/Right toggle the PTT lock on both pages (Back switches pages).
        if (mScreen == Screen.MAIN
                && (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            if (firstDown && mService != null) {
                boolean locked = mService.togglePttLock();
                showFeedback(locked ? QrvHubService.FB_LOCK : QrvHubService.FB_UNLOCK,
                        getString(locked ? R.string.fb_ptt_locked : R.string.fb_ptt_unlocked),
                        locked ? getString(R.string.fb_ptt_locked_detail) : null);
                refresh();
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }
}
