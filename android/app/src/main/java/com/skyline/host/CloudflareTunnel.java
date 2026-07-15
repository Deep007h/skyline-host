package com.skyline.host;

import android.content.Context;
import android.util.Log;
import java.io.*;

public class CloudflareTunnel {
    private final Context context;
    private final int port;
    private Process process;
    private String tunnelUrl = null;
    private boolean isRunning = false;
    private final TunnelListener listener;

    public interface TunnelListener {
        void onTunnelStarted(String url);
        void onTunnelError(String error);
    }

    public CloudflareTunnel(Context context, int port, TunnelListener listener) {
        this.context = context;
        this.port = port;
        this.listener = listener;
    }

    public void start() {
        isRunning = true;
        new Thread(() -> {
            try {
                // Resolve the pre-packaged binary from the native library directory
                File binaryFile = new File(context.getApplicationInfo().nativeLibraryDir, "libcloudflared.so");
                if (!binaryFile.exists()) {
                    Log.e("CloudflareTunnel", "Binary not found in native library directory: " + binaryFile.getAbsolutePath());
                    if (listener != null) {
                        listener.onTunnelError("Pre-packaged Cloudflare binary not found. Please compile with native jniLibs.");
                    }
                    return;
                }

                Log.d("CloudflareTunnel", "Launching tunnel from: " + binaryFile.getAbsolutePath());

                // Launch tunnel command
                String[] cmd = {binaryFile.getAbsolutePath(), "tunnel", "--url", "http://localhost:" + port};
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                process = pb.start();

                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line;
                while (isRunning && (line = reader.readLine()) != null) {
                    Log.d("CloudflareTunnel", line);
                    // Search for trycloudflare URL in logs
                    if (line.contains(".trycloudflare.com")) {
                        String[] words = line.split("\\s+");
                        for (String word : words) {
                            if (word.startsWith("https://") && word.contains(".trycloudflare.com")) {
                                tunnelUrl = word.trim();
                                if (listener != null) {
                                    listener.onTunnelStarted(tunnelUrl);
                                }
                                break;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.e("CloudflareTunnel", "Error running tunnel", e);
                if (listener != null) {
                    listener.onTunnelError(e.getMessage());
                }
            }
        }).start();
    }

    public void stop() {
        isRunning = false;
        if (process != null) {
            process.destroy();
            process = null;
        }
    }
}
