package com.skyline.host;

import android.util.Log;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * LocalTunnel - Pure Java reverse tunnel using localtunnel.me protocol.
 * Unlike the native cloudflared binary, this uses Android's Java networking
 * stack which properly handles Android DNS resolution.
 *
 * Protocol:
 *  1. GET https://localtunnel.me/?new → get tunnel URL + remote port
 *  2. Open N TCP sockets to localtunnel.me:remotePort
 *  3. Each socket receives an HTTP request, forwards to localhost:localPort, 
 *     sends the response back through the same socket.
 */
public class LocalTunnel {

    private static final String TAG = "LocalTunnel";
    private static final String LT_HOST = "localtunnel.me";
    private static final int DEFAULT_CONN_COUNT = 6;

    private final int localPort;
    private volatile boolean running = false;
    private final List<Thread> workers = new CopyOnWriteArrayList<>();

    public interface TunnelListener {
        void onTunnelStarted(String url);
        void onTunnelError(String error);
    }

    private final TunnelListener listener;

    public LocalTunnel(int localPort, TunnelListener listener) {
        this.localPort = localPort;
        this.listener = listener;
    }

    public void start() {
        running = true;
        new Thread(() -> {
            try {
                Log.d(TAG, "Requesting new tunnel from localtunnel.me...");
                TunnelInfo info = requestTunnel();
                Log.d(TAG, "Got tunnel: " + info.url + " port=" + info.port);

                if (listener != null) {
                    listener.onTunnelStarted(info.url);
                }

                // Spawn worker threads to forward traffic
                int connCount = Math.max(info.maxConnCount, DEFAULT_CONN_COUNT);
                for (int i = 0; i < connCount; i++) {
                    final int idx = i;
                    Thread t = new Thread(() -> runWorker(LT_HOST, info.port, idx));
                    t.setDaemon(true);
                    t.start();
                    workers.add(t);
                }

            } catch (Exception e) {
                Log.e(TAG, "Tunnel start failed", e);
                if (listener != null) {
                    listener.onTunnelError("Tunnel failed: " + e.getMessage());
                }
            }
        }).start();
    }

    public void stop() {
        running = false;
        for (Thread t : workers) {
            t.interrupt();
        }
        workers.clear();
    }

    // ─── Tunnel request ───────────────────────────────────────────────────────

    private static class TunnelInfo {
        String url;
        int port;
        int maxConnCount;
    }

    private TunnelInfo requestTunnel() throws Exception {
        URL url = new URL("https://" + LT_HOST + "/?new");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/json");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);

        int code = conn.getResponseCode();
        if (code != 200) {
            throw new IOException("localtunnel.me returned HTTP " + code);
        }

        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }

        JSONObject json = new JSONObject(sb.toString());
        TunnelInfo info = new TunnelInfo();
        info.url = json.getString("url");
        info.port = json.getInt("port");
        info.maxConnCount = json.optInt("max_conn_count", DEFAULT_CONN_COUNT);
        return info;
    }

    // ─── Proxy worker ─────────────────────────────────────────────────────────

    private void runWorker(String remoteHost, int remotePort, int idx) {
        Log.d(TAG, "Worker " + idx + " started → " + remoteHost + ":" + remotePort);
        while (running && !Thread.currentThread().isInterrupted()) {
            try (Socket remote = new Socket()) {
                remote.connect(new InetSocketAddress(remoteHost, remotePort), 10000);
                remote.setSoTimeout(60000);

                InputStream remoteIn = remote.getInputStream();
                OutputStream remoteOut = remote.getOutputStream();

                // Read full HTTP request from localtunnel server
                byte[] requestData = readHttpRequest(remoteIn);
                if (requestData == null) {
                    continue; // Empty / connection closed — reconnect
                }

                // Forward to local server and get response
                byte[] responseData = forwardToLocal(requestData);

                // Send response back through the tunnel socket
                remoteOut.write(responseData);
                remoteOut.flush();

            } catch (InterruptedIOException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                if (!running) break;
                Log.w(TAG, "Worker " + idx + " error (will retry): " + e.getMessage());
                try { Thread.sleep(1000); } catch (InterruptedException ie) { break; }
            }
        }
        Log.d(TAG, "Worker " + idx + " stopped.");
    }

    /**
     * Read a complete HTTP request (headers + body) from a stream.
     */
    private byte[] readHttpRequest(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[4096];

        // Read headers
        int contentLength = -1;
        boolean headersDone = false;
        StringBuilder headerText = new StringBuilder();

        // Read byte-by-byte until we see \r\n\r\n
        int b;
        while ((b = in.read()) != -1) {
            buf.write(b);
            headerText.append((char) b);
            String h = headerText.toString();
            if (h.contains("\r\n\r\n") || h.contains("\n\n")) {
                headersDone = true;
                // Parse Content-Length
                String lower = h.toLowerCase();
                int clIdx = lower.indexOf("content-length:");
                if (clIdx >= 0) {
                    int end = lower.indexOf('\n', clIdx);
                    if (end < 0) end = lower.length();
                    String clVal = lower.substring(clIdx + 15, end).trim();
                    try { contentLength = Integer.parseInt(clVal); } catch (NumberFormatException ignored) {}
                }
                break;
            }
            if (buf.size() > 65536) break; // Safety cap
        }

        if (!headersDone && buf.size() == 0) return null;

        // Read body if Content-Length specified
        if (contentLength > 0) {
            int remaining = contentLength;
            while (remaining > 0) {
                int chunk = Math.min(tmp.length, remaining);
                int read = in.read(tmp, 0, chunk);
                if (read < 0) break;
                buf.write(tmp, 0, read);
                remaining -= read;
            }
        }

        return buf.toByteArray();
    }

    /**
     * Forward raw HTTP request bytes to local server and return full raw response.
     */
    private byte[] forwardToLocal(byte[] request) throws IOException {
        try (Socket local = new Socket("127.0.0.1", localPort)) {
            local.setSoTimeout(30000);
            local.getOutputStream().write(request);
            local.getOutputStream().flush();

            ByteArrayOutputStream response = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int read;
            // Read until local server closes the connection
            try {
                while ((read = local.getInputStream().read(buf)) != -1) {
                    response.write(buf, 0, read);
                }
            } catch (SocketTimeoutException ste) {
                // Timeout means server is done sending
            }
            return response.toByteArray();
        }
    }
}
