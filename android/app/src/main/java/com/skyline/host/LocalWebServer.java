package com.skyline.host;

import android.content.Context;
import android.net.Uri;
import androidx.documentfile.provider.DocumentFile;
import java.io.*;
import java.net.*;

public class LocalWebServer {
    private ServerSocket serverSocket;
    private boolean isRunning = false;
    private final Context context;
    private final Uri folderUri;
    private final int port;

    public LocalWebServer(Context context, Uri folderUri, int port) {
        this.context = context;
        this.folderUri = folderUri;
        this.port = port;
    }

    public void start() {
        isRunning = true;
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(port);
                while (isRunning) {
                    try {
                        Socket client = serverSocket.accept();
                        new Thread(() -> handleClient(client)).start();
                    } catch (IOException e) {
                        if (!isRunning) break;
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    public void stop() {
        isRunning = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (Exception e) {}
    }

    private void handleClient(Socket client) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
             OutputStream out = client.getOutputStream()) {

            String line = in.readLine();
            if (line == null) return;
            String[] parts = line.split(" ");
            if (parts.length < 2) return;
            String path = parts[1];
            
            // Read headers to locate Range request metadata
            String rangeHeader = null;
            String headerLine;
            while ((headerLine = in.readLine()) != null && !headerLine.isEmpty()) {
                if (headerLine.toLowerCase().startsWith("range:")) {
                    rangeHeader = headerLine.substring(6).trim();
                }
            }

            try {
                path = URLDecoder.decode(path, "UTF-8");
            } catch (Exception e) {}

            if (path.equals("/")) {
                path = "/index.html";
            }
            if (path.contains("?")) {
                path = path.split("\\?")[0];
            }

            DocumentFile root = DocumentFile.fromTreeUri(context, folderUri);
            String[] segments = path.substring(1).split("/");
            DocumentFile current = root;
            for (String segment : segments) {
                if (segment.isEmpty()) continue;
                current = current.findFile(segment);
                if (current == null) break;
            }

            if (current != null && current.isFile()) {
                String mime = context.getContentResolver().getType(current.getUri());
                if (mime == null) {
                    if (path.endsWith(".html")) mime = "text/html";
                    else if (path.endsWith(".css")) mime = "text/css";
                    else if (path.endsWith(".js")) mime = "application/javascript";
                    else if (path.endsWith(".png")) mime = "image/png";
                    else if (path.endsWith(".jpg") || path.endsWith(".jpeg")) mime = "image/jpeg";
                    else if (path.endsWith(".svg")) mime = "image/svg+xml";
                    else if (path.endsWith(".mp4")) mime = "video/mp4";
                    else if (path.endsWith(".webm")) mime = "video/webm";
                    else if (path.endsWith(".mp3")) mime = "audio/mpeg";
                    else if (path.endsWith(".wav")) mime = "audio/wav";
                    else mime = "application/octet-stream";
                }

                long fileLength = current.length();
                long start = 0;
                long end = fileLength - 1;
                boolean isRange = false;

                if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                    isRange = true;
                    String rangeVal = rangeHeader.substring(6);
                    String[] rangeParts = rangeVal.split("-");
                    try {
                        if (rangeParts.length > 0 && !rangeParts[0].isEmpty()) {
                            start = Long.parseLong(rangeParts[0]);
                        }
                        if (rangeParts.length > 1 && !rangeParts[1].isEmpty()) {
                            end = Long.parseLong(rangeParts[1]);
                        }
                    } catch (NumberFormatException e) {
                        isRange = false;
                    }
                }

                if (isRange) {
                    if (start >= fileLength) {
                        out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */*\r\n\r\n".getBytes());
                        return;
                    }
                    long contentLength = end - start + 1;
                    out.write(("HTTP/1.1 206 Partial Content\r\n" +
                               "Content-Type: " + mime + "\r\n" +
                               "Accept-Ranges: bytes\r\n" +
                               "Content-Range: bytes " + start + "-" + end + "/" + fileLength + "\r\n" +
                               "Content-Length: " + contentLength + "\r\n\r\n").getBytes());

                    try (InputStream fis = context.getContentResolver().openInputStream(current.getUri())) {
                        long skipped = fis.skip(start);
                        long bytesToRead = contentLength;
                        byte[] buffer = new byte[8192];
                        int read;
                        while (bytesToRead > 0 && (read = fis.read(buffer, 0, (int) Math.min(buffer.length, bytesToRead))) != -1) {
                            out.write(buffer, 0, read);
                            bytesToRead -= read;
                        }
                    }
                } else {
                    out.write(("HTTP/1.1 200 OK\r\n" +
                               "Content-Type: " + mime + "\r\n" +
                               "Accept-Ranges: bytes\r\n" +
                               "Content-Length: " + fileLength + "\r\n\r\n").getBytes());

                    try (InputStream fis = context.getContentResolver().openInputStream(current.getUri())) {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = fis.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                        }
                    }
                }
            } else {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Type: text/plain\r\n\r\nFile Not Found".getBytes());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
