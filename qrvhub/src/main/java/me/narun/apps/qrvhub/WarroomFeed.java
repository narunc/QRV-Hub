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

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayDeque;
import java.util.Random;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import se.lublin.humla.audio.AudioOutput;
import se.lublin.humla.audio.javacpp.Opus;
import se.lublin.humla.exception.NativeAudioException;

/**
 * The Warroom audio feed (https://feed.example.org): an extra source mixed into
 * QRV-Hub's audio output next to the Mumble voices.
 *
 * Protocol (the website's own, undocumented): a WebSocket that first sends a text
 * {"type":"audio_hello",...}, then binary messages "W" | version 1 | generation |
 * frame count | uint32 sequence | uint32 send time | (uint16 length, Opus frame)*.
 * Frames are Opus, 48 kHz, mono, 20 ms. The feed is continuous; silence is sent as
 * 1-byte Opus frames, so a frame carries audio only when it is longer than that and
 * its decoded level is above a small floor. Only those stretches are played, so the
 * speaker stays off between voices. The server pings; we answer with pongs.
 */
final class WarroomFeed implements AudioOutput.ExternalSource {
    private static final String TAG = "QRV-Hub";

    interface Listener {
        /** The feed started or stopped carrying a voice (called on the main thread). */
        void onFeedVoice(boolean voice);
    }

    private static final int FRAME = 960;              // 20 ms at 48 kHz
    // The audio thread takes 120 ms (6 frames) at a time, so keep twice that buffered
    // before playing, or network jitter empties the buffer mid-voice and it stutters.
    private static final int START_FRAMES = 12;        // buffer 240 ms before playing
    private static final int MAX_FRAMES = 50;          // never more than 1 s behind
    private static final float LEVEL_FLOOR = 0.002f;   // RMS below this counts as silence
    private static final int HANG_FRAMES = 20;         // keep playing 400 ms after the last voice
    private static final long PTT_TAIL_MS = 2000;      // stay cut 2 s after PTT release
    private static final long RETRY_MIN_MS = 1000, RETRY_MAX_MS = 20000;
    private static final long FULL_RETRY_MS = 30000;

    private final Context mContext;
    private final String mUrl;
    private final String mOrigin;
    private final Listener mListener;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Random mRandom = new Random();

    private final Object mLock = new Object();
    private final ArrayDeque<float[]> mQueue = new ArrayDeque<>();
    private boolean mPlaying;          // buffered enough and currently playing
    private int mHang;                 // frames of hang time left after the last voiced frame
    private boolean mVoice;            // last voice state reported to the listener

    private volatile boolean mRunning;
    private volatile boolean mPtt;
    private volatile long mPttReleasedAt;
    private volatile Socket mSocket;
    private Thread mThread;

    WarroomFeed(Context context, String url, String origin, Listener listener) {
        mContext = context.getApplicationContext();
        mUrl = url;
        mOrigin = origin;
        mListener = listener;
    }

    boolean isRunning() {
        return mRunning;
    }

    private static final long HEALTHY_MS = 3000;
    private volatile long mLastFrameAt;

    /** Connected and receiving: frames (voice or silence) arrived within the last 3 s. */
    boolean isHealthy() {
        return mRunning && SystemClock.elapsedRealtime() - mLastFrameAt < HEALTHY_MS;
    }

    boolean isVoice() {
        synchronized (mLock) {
            return mVoice;
        }
    }

    synchronized void start() {
        if (mRunning) return;
        mRunning = true;
        mThread = new Thread(this::run, "QRV-Hub-warroom");
        mThread.setDaemon(true);
        mThread.start();
        AudioOutput.setExternalSource(this);
        Log.i(TAG, "warroom feed: start");
    }

    synchronized void stop() {
        if (!mRunning) return;
        mRunning = false;
        AudioOutput.setExternalSource(null);
        final Socket s = mSocket;
        if (s != null) {
            // Closing TLS sends a close message: network I/O, which Android forbids on
            // the main thread (stop() is called from it), so close on a short thread.
            new Thread(() -> {
                try { s.close(); } catch (IOException ignored) { }
            }, "QRV-Hub-warroom-close").start();
        }
        if (mThread != null) mThread.interrupt();
        mThread = null;
        clear();
        Log.i(TAG, "warroom feed: stop");
    }

    /** PTT held: the feed is cut, and stays cut PTT_TAIL_MS after release (no echo of ourselves). */
    void setPtt(boolean held) {
        if (mRunning) Log.v(TAG, held ? "warroom feed: cut (PTT)" : "warroom feed: PTT released, back in 2 s");
        mPtt = held;
        if (held) clear(); else mPttReleasedAt = SystemClock.elapsedRealtime();
    }

    private boolean cut() {
        return mPtt || SystemClock.elapsedRealtime() - mPttReleasedAt < PTT_TAIL_MS;
    }

    private void clear() {
        synchronized (mLock) {
            mQueue.clear();
            mPlaying = false;
            mHang = 0;
        }
        reportVoice(false);
    }

    // ---- audio thread side ----------------------------------------------------

    @Override
    public boolean read(float[] out, int n) {
        if (!mRunning || cut()) return false;
        synchronized (mLock) {
            if (!mPlaying) {
                if (mQueue.size() < START_FRAMES) return false;
                mPlaying = true;
                Log.v(TAG, "warroom feed: playing");
            }
            int pos = 0;
            while (pos < n) {
                float[] f = mQueue.poll();
                if (f == null) {
                    mPlaying = false; // underrun: rebuffer before playing on
                    break;
                }
                int len = Math.min(FRAME, n - pos);
                System.arraycopy(f, 0, out, pos, len);
                pos += len;
            }
            return pos > 0;
        }
    }

    // ---- network side -----------------------------------------------------------

    private void run() {
        long retry = RETRY_MIN_MS;
        Opus.OpusDecoder decoder = null;
        try {
            decoder = new Opus.OpusDecoder(48000, 1);
        } catch (NativeAudioException e) {
            Log.w(TAG, "warroom feed: no Opus decoder", e);
            mRunning = false;
            return;
        }
        try {
            while (mRunning) {
                int code = 0;
                try {
                    code = session(decoder);
                    retry = RETRY_MIN_MS;
                } catch (IOException e) {
                    if (mRunning) Log.i(TAG, "warroom feed: " + e.getMessage());
                }
                clear();
                if (!mRunning) break;
                long wait = code == 1013 ? FULL_RETRY_MS : jitter(retry);
                if (code != 1013) retry = Math.min(RETRY_MAX_MS, retry * 2);
                Log.i(TAG, "warroom feed: reconnect in " + wait + " ms" + (code == 1013 ? " (server full)" : ""));
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    break;
                }
            }
        } finally {
            decoder.destroy();
        }
    }

    private long jitter(long ms) {
        return Math.round(ms * (0.5 + mRandom.nextDouble() * 0.5));
    }

    /** One connection; returns the server's close code (0 if none). */
    private int session(Opus.OpusDecoder decoder) throws IOException {
        URI uri = URI.create(mUrl);
        String host = uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : 443;
        String path = (uri.getRawPath() == null || uri.getRawPath().isEmpty()) ? "/" : uri.getRawPath();

        Socket socket = new Socket();
        mSocket = socket;
        try {
            socket.connect(new InetSocketAddress(host, port), 10000);
            socket.setSoTimeout(30000);
            // TLS layered on the plain socket with the host name, so SNI (needed by
            // Cloudflare) and the hostname check use it.
            SSLSocket tls = (SSLSocket) sslContext().getSocketFactory().createSocket(socket, host, port, true);
            mSocket = tls;
            // Android 5.1 does not send SNI on a layered socket by itself; without it the
            // server answers with its default (here: an old, expired) certificate.
            try {
                tls.getClass().getMethod("setHostname", String.class).invoke(tls, host);
            } catch (Exception e) {
                Log.w(TAG, "warroom feed: cannot set SNI host name", e);
            }
            tls.startHandshake();
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, tls.getSession())) {
                throw new IOException("certificate does not match " + host);
            }
            OutputStream out = tls.getOutputStream();
            DataInputStream in = new DataInputStream(new BufferedInputStream(tls.getInputStream()));

            byte[] key = new byte[16];
            new SecureRandom().nextBytes(key);
            String req = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + host + "\r\n"
                    + "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + Base64.encodeToString(key, Base64.NO_WRAP) + "\r\n"
                    + "Sec-WebSocket-Version: 13\r\n"
                    + "Origin: " + mOrigin + "\r\n"
                    + "User-Agent: QRV-Hub/" + BuildConfig.VERSION_NAME + "\r\n\r\n";
            out.write(req.getBytes(Charset.forName("US-ASCII")));
            out.flush();
            String status = readLine(in);
            if (status == null || !status.contains(" 101 ")) {
                throw new IOException("handshake refused: " + status);
            }
            while (true) {
                String line = readLine(in);
                if (line == null) throw new IOException("handshake cut");
                if (line.isEmpty()) break;
            }
            Log.i(TAG, "warroom feed: connected");
            return readFrames(in, out, decoder);
        } finally {
            try { socket.close(); } catch (IOException ignored) { }
            mSocket = null;
        }
    }

    private int readFrames(DataInputStream in, OutputStream out, Opus.OpusDecoder decoder) throws IOException {
        ByteBuffer opus = ByteBuffer.allocateDirect(4000).order(ByteOrder.nativeOrder());
        float[] pcm = new float[FRAME * 6];
        byte[] partial = null;
        while (mRunning) {
            int b0 = in.readUnsignedByte(), b1 = in.readUnsignedByte();
            int op = b0 & 0x0F;
            boolean fin = (b0 & 0x80) != 0;
            long len = b1 & 0x7F;
            if (len == 126) len = in.readUnsignedShort();
            else if (len == 127) len = in.readLong();
            if ((b1 & 0x80) != 0) in.readInt(); // servers do not mask, but skip a mask if present
            if (len > (1 << 20)) throw new IOException("frame too large");
            byte[] payload = new byte[(int) len];
            in.readFully(payload);
            switch (op) {
                case 0x9: // ping -> pong with the same payload
                    sendControl(out, 0xA, payload);
                    break;
                case 0x8: { // close
                    int code = payload.length >= 2 ? ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF) : 0;
                    sendControl(out, 0x8, payload.length >= 2 ? new byte[] { payload[0], payload[1] } : new byte[0]);
                    Log.i(TAG, "warroom feed: closed by server, code " + code);
                    return code;
                }
                case 0x1: // text: audio_hello and similar; nothing to do
                    break;
                case 0x2:
                case 0x0:
                    if (!fin || op == 0x0) {
                        partial = append(partial, payload);
                        if (!fin) break;
                        payload = partial;
                        partial = null;
                    }
                    if (op == 0x2 || payload != null) onAudioMessage(payload, opus, pcm, decoder);
                    break;
                default:
                    break;
            }
        }
        return 0;
    }

    private static byte[] append(byte[] a, byte[] b) {
        if (a == null) return b;
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    /** "W" v1 message: decode each Opus frame and queue it. */
    private void onAudioMessage(byte[] m, ByteBuffer opus, float[] pcm, Opus.OpusDecoder decoder) {
        if (m.length < 12 || m[0] != 'W' || m[1] != 1) return;
        mLastFrameAt = SystemClock.elapsedRealtime();
        int count = m[3] & 0xFF;
        int p = 12;
        boolean cut = cut();
        for (int i = 0; i < count && p + 2 <= m.length; i++) {
            int len = ((m[p] & 0xFF) << 8) | (m[p + 1] & 0xFF);
            p += 2;
            if (p + len > m.length) return;
            int samples;
            opus.clear();
            opus.put(m, p, len);
            opus.flip();
            try {
                samples = decoder.decodeFloat(opus, len, pcm, pcm.length);
            } catch (NativeAudioException e) {
                p += len;
                continue;
            }
            p += len;
            if (samples <= 0 || cut) continue;
            float[] frame = new float[FRAME];
            System.arraycopy(pcm, 0, frame, 0, Math.min(FRAME, samples));
            float level = rms(frame);
            boolean voiced = len > 2 && level > LEVEL_FLOOR;
            stats(len, level, voiced);
            queue(frame, voiced);
        }
    }

    // Level statistics in the log every 10 s (to check the voice gate on real traffic).
    private long mStatsAt;
    private int mStatFrames, mStatAudio, mStatVoiced;
    private float mStatMax, mStatMinAudio = 1f;

    private void stats(int len, float level, boolean voiced) {
        mStatFrames++;
        if (len > 2) {
            mStatAudio++;
            mStatMinAudio = Math.min(mStatMinAudio, level);
        }
        if (voiced) mStatVoiced++;
        mStatMax = Math.max(mStatMax, level);
        long now = SystemClock.elapsedRealtime();
        if (now - mStatsAt >= 10000) {
            if (mStatAudio > 0) {
                Log.v(TAG, String.format(java.util.Locale.US,
                        "warroom feed: %d frames, %d with audio, %d voiced, level min %.4f max %.4f",
                        mStatFrames, mStatAudio, mStatVoiced, mStatMinAudio, mStatMax));
            }
            mStatsAt = now;
            mStatFrames = mStatAudio = mStatVoiced = 0;
            mStatMax = 0f;
            mStatMinAudio = 1f;
        }
    }

    private static float rms(float[] f) {
        double s = 0;
        for (float v : f) s += v * v;
        return (float) Math.sqrt(s / f.length);
    }

    private void queue(float[] frame, boolean voiced) {
        boolean wake = false;
        boolean voice;
        synchronized (mLock) {
            if (voiced) {
                mHang = HANG_FRAMES;
            } else if (mHang > 0) {
                mHang--;
            }
            if (mHang > 0) {
                mQueue.add(frame);
                while (mQueue.size() > MAX_FRAMES) mQueue.poll();
                // Wake the audio thread with every frame of a voice: it goes back to sleep
                // when it finds less than START_FRAMES buffered, and nothing else wakes it
                // while Mumble is silent. (A no-op while it is already playing.)
                wake = true;
            } else if (!mPlaying) {
                mQueue.clear(); // silence while idle: keep nothing, stay current
            }
            voice = mHang > 0;
        }
        reportVoice(voice);
        if (wake) AudioOutput.wakeOutput();
    }

    private void reportVoice(boolean voice) {
        synchronized (mLock) {
            if (voice == mVoice) return;
            mVoice = voice;
        }
        mMain.post(() -> mListener.onFeedVoice(voice));
    }

    private static void sendControl(OutputStream out, int op, byte[] payload) throws IOException {
        // Client frames must be masked.
        byte[] mask = new byte[4];
        new SecureRandom().nextBytes(mask);
        byte[] f = new byte[2 + 4 + payload.length];
        f[0] = (byte) (0x80 | op);
        f[1] = (byte) (0x80 | payload.length);
        System.arraycopy(mask, 0, f, 2, 4);
        for (int i = 0; i < payload.length; i++) f[6 + i] = (byte) (payload[i] ^ mask[i % 4]);
        synchronized (out) {
            out.write(f);
            out.flush();
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') return sb.toString().trim();
            if (sb.length() > 8192) throw new IOException("header too long");
            sb.append((char) c);
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    // ---- TLS: the system's roots plus Let's Encrypt's (missing on Android 5.1) ----

    private SSLContext mSsl;

    private SSLContext sslContext() throws IOException {
        if (mSsl != null) return mSsl;
        try {
            TrustManagerFactory sys = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            sys.init((KeyStore) null);
            final X509TrustManager system = (X509TrustManager) sys.getTrustManagers()[0];

            KeyStore extra = KeyStore.getInstance(KeyStore.getDefaultType());
            extra.load(null, null);
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            int[] roots = { R.raw.isrg_root_x1, R.raw.isrg_root_x2 };
            for (int i = 0; i < roots.length; i++) {
                try (InputStream in = mContext.getResources().openRawResource(roots[i])) {
                    extra.setCertificateEntry("isrg" + i, cf.generateCertificate(in));
                }
            }
            TrustManagerFactory ext = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            ext.init(extra);
            final X509TrustManager bundled = (X509TrustManager) ext.getTrustManagers()[0];

            X509TrustManager both = new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    system.checkClientTrusted(chain, authType);
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    try {
                        system.checkServerTrusted(chain, authType);
                    } catch (CertificateException e) {
                        bundled.checkServerTrusted(chain, authType);
                    }
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return system.getAcceptedIssuers();
                }
            };
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[] { both }, null);
            mSsl = ctx;
            return ctx;
        } catch (Exception e) {
            throw new IOException("TLS setup failed: " + e.getMessage());
        }
    }
}
