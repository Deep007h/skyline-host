package com.skyline.host;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import androidx.activity.result.ActivityResult;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.ActivityCallback;

@CapacitorPlugin(name = "FolderPicker")
public class FolderPickerPlugin extends Plugin {
    private LocalWebServer localServer;
    private LocalTunnel localTunnel;
    private String selectedUriString = null;

    @PluginMethod
    public void selectFolder(PluginCall call) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        startActivityForResult(call, intent, "pickFolderResult");
    }

    @ActivityCallback
    private void pickFolderResult(PluginCall call, ActivityResult result) {
        if (result.getResultCode() == Activity.RESULT_OK) {
            Intent data = result.getData();
            if (data != null) {
                Uri uri = data.getData();
                if (uri != null) {
                    try {
                        int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
                        getContext().getContentResolver().takePersistableUriPermission(uri, takeFlags);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    selectedUriString = uri.toString();
                    JSObject ret = new JSObject();
                    ret.put("path", uri.toString());
                    call.resolve(ret);
                    return;
                }
            }
        }
        call.reject("User cancelled folder selection");
    }

    @PluginMethod
    public void startLocalServer(PluginCall call) {
        String uriStr = call.getString("uri");
        if (uriStr == null) uriStr = selectedUriString;
        Integer portVal = call.getInt("port", 9090);

        if (uriStr == null) {
            call.reject("No folder selected or URI provided");
            return;
        }

        try {
            // Stop any existing server/tunnel
            if (localServer != null) { localServer.stop(); localServer = null; }
            if (localTunnel != null) { localTunnel.stop(); localTunnel = null; }

            // Start local static file server
            Uri uri = Uri.parse(uriStr);
            localServer = new LocalWebServer(getContext(), uri, portVal);
            localServer.start();

            // Start the pure-Java localtunnel.me reverse tunnel
            final String[] resolvedUrl = {null};
            final String[] errorMsg = {null};
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

            final int finalPort = portVal;
            localTunnel = new LocalTunnel(finalPort, new LocalTunnel.TunnelListener() {
                @Override
                public void onTunnelStarted(String url) {
                    resolvedUrl[0] = url;
                    latch.countDown();
                }
                @Override
                public void onTunnelError(String error) {
                    errorMsg[0] = error;
                    latch.countDown();
                }
            });
            localTunnel.start();

            // Wait up to 20 seconds for tunnel URL
            boolean completed = latch.await(20, java.util.concurrent.TimeUnit.SECONDS);

            if (!completed) {
                if (localTunnel != null) localTunnel.stop();
                call.reject("Tunnel timed out. Check your internet connection and try again.");
                return;
            }

            if (errorMsg[0] != null) {
                if (localTunnel != null) localTunnel.stop();
                call.reject("Tunnel error: " + errorMsg[0]);
                return;
            }

            JSObject ret = new JSObject();
            ret.put("status", "running");
            ret.put("port", portVal);
            ret.put("url", resolvedUrl[0]);
            call.resolve(ret);

        } catch (Exception e) {
            call.reject("Failed to start: " + e.getMessage());
        }
    }

    @PluginMethod
    public void stopLocalServer(PluginCall call) {
        try {
            if (localServer != null) { localServer.stop(); localServer = null; }
            if (localTunnel != null) { localTunnel.stop(); localTunnel = null; }
            JSObject ret = new JSObject();
            ret.put("status", "stopped");
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("Failed to stop: " + e.getMessage());
        }
    }
}
