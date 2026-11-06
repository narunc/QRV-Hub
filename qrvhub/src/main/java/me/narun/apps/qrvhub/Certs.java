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
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import se.lublin.humla.net.HumlaCertificateGenerator;

/**
 * Server trust (pinning) and the client identity certificate.
 *
 * The trust store starts with exactly one entry, the e2hub.vra.or.th server
 * certificate shipped in res/raw. Humla accepts a server whose certificate is
 * in this store (or chains to a system CA); anything else fails the handshake
 * and the user has to trust it explicitly from the menu.
 */
final class Certs {
    private static final String TAG = "QRV-Hub";
    static final String TRUST_FILE = "qrvhub-trust.bks";
    static final String TRUST_PASS = "";
    static final String TRUST_FORMAT = "BKS";
    private static final String CLIENT_FILE = "client-identity.p12";

    private static final List<Runnable> sWaiting = new ArrayList<>();
    private static boolean sGenerating;

    private Certs() {}

    /** Creates the pinned trust store on first use; returns its absolute path. */
    static synchronized String trustStorePath(Context c) throws Exception {
        File f = new File(c.getFilesDir(), TRUST_FILE);
        if (!f.exists()) {
            KeyStore ks = KeyStore.getInstance(TRUST_FORMAT);
            ks.load(null, null);
            ks.setCertificateEntry("qrvhub-pinned", pinnedCertificate(c));
            save(c, ks);
        }
        return f.getAbsolutePath();
    }

    static X509Certificate pinnedCertificate(Context c) throws Exception {
        try (InputStream in = c.getResources().openRawResource(R.raw.server_cert)) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
    }

    /** Adds a certificate the user chose to trust (server certificate changed, or test server). */
    static synchronized void trust(Context c, X509Certificate cert) throws Exception {
        trustStorePath(c);
        KeyStore ks = KeyStore.getInstance(TRUST_FORMAT);
        try (FileInputStream in = c.openFileInput(TRUST_FILE)) {
            ks.load(in, TRUST_PASS.toCharArray());
        }
        ks.setCertificateEntry("trusted-" + sha256(cert).replace(":", ""), cert);
        save(c, ks);
    }

    /** Back to only the pinned server certificate. */
    static synchronized void resetTrust(Context c) {
        c.deleteFile(TRUST_FILE);
    }

    private static void save(Context c, KeyStore ks) throws Exception {
        try (FileOutputStream out = c.openFileOutput(TRUST_FILE, Context.MODE_PRIVATE)) {
            ks.store(out, TRUST_PASS.toCharArray());
        }
    }

    static String sha256(X509Certificate cert) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < d.length; i++) {
                if (i > 0) sb.append(':');
                sb.append(String.format("%02X", d[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return "?";
        }
    }

    // ---- client identity ----------------------------------------------------

    /** PKCS#12 bytes of this device's client certificate, or null if not generated yet. */
    static byte[] clientCertificate(Context c) {
        File f = new File(c.getFilesDir(), CLIENT_FILE);
        if (!f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "reading client certificate failed", e);
            return null;
        }
    }

    /**
     * Generates the client certificate once (a few seconds on this device) and
     * then runs {@code done} on the main thread. Runs {@code done} at once if
     * it already exists. Failure is logged; connecting then proceeds without a
     * certificate, which Mumble servers accept for password logins.
     */
    static void ensureClientCertificate(Context context, Runnable done) {
        final Context c = context.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        synchronized (Certs.class) {
            if (new File(c.getFilesDir(), CLIENT_FILE).exists()) {
                if (done != null) main.post(done);
                return;
            }
            if (done != null) sWaiting.add(done);
            if (sGenerating) return;
            sGenerating = true;
        }
        new Thread(() -> {
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                HumlaCertificateGenerator.generateCertificate(baos);
                File tmp = new File(c.getFilesDir(), CLIENT_FILE + ".tmp");
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    out.write(baos.toByteArray());
                }
                if (!tmp.renameTo(new File(c.getFilesDir(), CLIENT_FILE))) {
                    Log.e(TAG, "could not store client certificate");
                }
                Log.i(TAG, "client certificate generated");
            } catch (Exception e) {
                Log.e(TAG, "client certificate generation failed", e);
            }
            List<Runnable> run;
            synchronized (Certs.class) {
                sGenerating = false;
                run = new ArrayList<>(sWaiting);
                sWaiting.clear();
            }
            for (Runnable r : run) main.post(r);
        }, "qrvhub-certgen").start();
    }
}
