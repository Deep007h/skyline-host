package com.skyline.host;

import android.util.Log;
import com.jcraft.jsch.*;

import java.io.*;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LocalTunnel - SSH-based reverse tunnel using JSch (pure Java SSH library).
 *
 * Uses localhost.run as the primary provider (ssh -R 80:localhost:PORT nokey@localhost.run)
 * Falls back to serveo.net if localhost.run fails.
 *
 * Unlike the native cloudflared Go binary, JSch uses Android's Java networking
 * stack which properly handles Android's DNS resolution.
 */
public class LocalTunnel {

    private static final String TAG = "LocalTunnel";

    // Tunnel providers - tried in order
    private static final String[][] PROVIDERS = {
        {"nokey", "localhost.run", "22"},
        {"user",  "serveo.net",    "22"},
    };

    // URL patterns to match from SSH session output
    private static final Pattern[] URL_PATTERNS = {
        Pattern.compile("https://[a-zA-Z0-9-]+\\.lhrtunnel\\.link"),
        Pattern.compile("https://[a-zA-Z0-9-]+\\.localhost\\.run"),
        Pattern.compile("https?://[a-zA-Z0-9-]+\\.serveo\\.net"),
        // Generic fallback — any https URL containing these keywords
        Pattern.compile("https://[a-zA-Z0-9._-]+(localhost|serveo|tunnel|lhr)[a-zA-Z0-9._-]*"),
    };

    private final int localPort;
    private final TunnelListener listener;
    private volatile Session activeSession;
    private volatile boolean running = false;

    public interface TunnelListener {
        void onTunnelStarted(String url);
        void onTunnelError(String error);
    }

    public LocalTunnel(int localPort, TunnelListener listener) {
        this.localPort = localPort;
        this.listener = listener;
    }

    // ─── Public API ──────────────────────────────────────────────────────────

    public void start() {
        running = true;
        new Thread(() -> {
            String lastError = "No tunnel providers available";
            for (String[] provider : PROVIDERS) {
                if (!running) break;
                try {
                    Log.d(TAG, "Trying provider: " + provider[1]);
                    lastError = tryProvider(provider[0], provider[1], Integer.parseInt(provider[2]));
                    if (lastError == null) return; // Success
                } catch (Exception e) {
                    lastError = e.getMessage();
                    Log.w(TAG, "Provider " + provider[1] + " failed: " + lastError);
                }
            }
            if (listener != null) {
                listener.onTunnelError("All tunnel providers failed. Last error: " + lastError);
            }
        }).start();
    }

    public void stop() {
        running = false;
        Session s = activeSession;
        if (s != null) {
            s.disconnect();
            activeSession = null;
        }
    }

    // ─── SSH tunnel implementation ───────────────────────────────────────────

    /**
     * Try one SSH tunnel provider.
     * @return null on success, error message on failure.
     */
    private String tryProvider(String user, String host, int port) throws Exception {
        JSch jsch = new JSch();

        Session session = jsch.getSession(user, host, port);

        Properties config = new Properties();
        config.put("StrictHostKeyChecking", "no");
        config.put("PreferredAuthentications", "publickey,keyboard-interactive,password");
        config.put("ServerAliveInterval", "30");
        config.put("ServerAliveCountMax", "5");
        session.setConfig(config);

        // Buffer to accumulate output for URL detection
        StringBuilder outputBuffer = new StringBuilder();
        CountDownLatch urlLatch = new CountDownLatch(1);
        String[] foundUrl = {null};

        session.setUserInfo(new UserInfo() {
            @Override public String getPassphrase() { return null; }
            @Override public String getPassword()   { return ""; }
            @Override public boolean promptPassword(String m)   { return true; }
            @Override public boolean promptPassphrase(String m) { return false; }
            @Override public boolean promptYesNo(String m)      { return true; }
            @Override
            public void showMessage(String message) {
                Log.d(TAG, "[" + host + "] SSH message: " + message);
                checkForUrl(message, foundUrl, urlLatch);
            }
        });

        Log.d(TAG, "Connecting to " + host + ":" + port + " ...");
        session.connect(20_000);
        activeSession = session;
        Log.d(TAG, "Connected. Requesting reverse port forward...");

        // Set up reverse TCP port forwarding:
        // Remote port 80 → our localhost:localPort
        session.setPortForwardingR(80, "localhost", localPort);
        Log.d(TAG, "Port forwarding configured.");

        // Open a shell channel to read the URL that the server prints
        Channel shellChannel = session.openChannel("shell");
        InputStream shellIn = shellChannel.getInputStream();
        ((ChannelShell) shellChannel).setPty(false);
        shellChannel.connect(8000);

        // Read shell output in a background thread
        Thread reader = new Thread(() -> {
            try {
                byte[] buf = new byte[2048];
                int len;
                while (running && !Thread.currentThread().isInterrupted()
                       && (len = shellIn.read(buf)) != -1) {
                    String chunk = new String(buf, 0, len);
                    Log.d(TAG, "[" + host + "] output: " + chunk);
                    synchronized (outputBuffer) {
                        outputBuffer.append(chunk);
                    }
                    checkForUrl(chunk, foundUrl, urlLatch);
                }
            } catch (Exception e) {
                if (running) Log.w(TAG, "Shell reader closed: " + e.getMessage());
            }
        });
        reader.setDaemon(true);
        reader.start();

        // Wait up to 18 s for the URL to appear in output
        boolean got = urlLatch.await(18, TimeUnit.SECONDS);

        if (!got) {
            // Last chance — scan everything we buffered
            synchronized (outputBuffer) {
                checkForUrl(outputBuffer.toString(), foundUrl, urlLatch);
            }
        }

        if (foundUrl[0] == null) {
            session.disconnect();
            activeSession = null;
            String dump = outputBuffer.toString().trim();
            return "Timed out waiting for URL from " + host +
                   (dump.isEmpty() ? "" : ". Output: " + dump.substring(0, Math.min(200, dump.length())));
        }

        // Tunnel is alive — keep session open in background
        new Thread(() -> {
            while (running && session.isConnected()) {
                try { Thread.sleep(5000); } catch (InterruptedException e) { break; }
            }
            session.disconnect();
        }).start();

        return null; // success
    }

    // ─── URL extraction ──────────────────────────────────────────────────────

    private void checkForUrl(String text, String[] foundUrl, CountDownLatch latch) {
        if (foundUrl[0] != null || latch.getCount() == 0) return;
        for (Pattern p : URL_PATTERNS) {
            Matcher m = p.matcher(text);
            if (m.find()) {
                String url = m.group();
                Log.i(TAG, "Tunnel URL found: " + url);
                foundUrl[0] = url;
                if (listener != null) listener.onTunnelStarted(url);
                latch.countDown();
                return;
            }
        }
    }
}
