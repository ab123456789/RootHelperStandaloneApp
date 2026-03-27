package com.dadatu.roothelper;

import com.topjohnwu.superuser.Shell;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RootCommandServer {

    private volatile boolean running;
    private ServerSocket serverSocket;
    private ExecutorService clientPool;
    private Thread acceptThread;

    public synchronized void start() throws Exception {
        if (running) return;
        running = true;
        clientPool = Executors.newCachedThreadPool();
        serverSocket = new ServerSocket(RootHelperConfig.PORT, 50);
        serverSocket.setReuseAddress(true);
        acceptThread = new Thread(this::acceptLoop, "roothelper-accept");
        acceptThread.start();
    }

    public synchronized void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception ignored) {}
        if (clientPool != null) {
            clientPool.shutdownNow();
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                clientPool.execute(() -> handle(socket));
            } catch (Exception e) {
                if (!running) return;
            }
        }
    }

    private void handle(Socket socket) {
        try (Socket s = socket) {
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));

            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.isEmpty()) {
                writeJson(out, 400, error("bad_request"));
                return;
            }

            String method = requestLine.split(" ")[0].trim().toUpperCase(Locale.ROOT);
            String path = requestLine.split(" ").length > 1 ? requestLine.split(" ")[1].trim() : "/";

            int contentLength = 0;
            String token = "";
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                String lower = line.toLowerCase(Locale.ROOT);
                if (lower.startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                } else if (lower.startsWith("x-token:")) {
                    token = line.substring(line.indexOf(':') + 1).trim();
                }
            }

            if ("GET".equals(method) && "/ping".equals(path)) {
                JSONObject obj = new JSONObject();
                obj.put("ok", true);
                obj.put("service", "roothelper");
                obj.put("mode", "standalone-app");
                writeJson(out, 200, obj);
                return;
            }

            if ("POST".equals(method) && "/exec".equals(path)) {
                if (!RootHelperConfig.TOKEN.equals(token)) {
                    writeJson(out, 403, error("forbidden"));
                    return;
                }

                char[] bodyChars = new char[Math.max(contentLength, 0)];
                int read = 0;
                while (read < bodyChars.length) {
                    int n = reader.read(bodyChars, read, bodyChars.length - read);
                    if (n < 0) break;
                    read += n;
                }
                String body = new String(bodyChars, 0, read);
                JSONObject req = new JSONObject(body.isEmpty() ? "{}" : body);
                JSONArray argvJson = req.optJSONArray("argv");
                if (argvJson == null || argvJson.length() == 0) {
                    writeJson(out, 400, error("argv must be non-empty string list"));
                    return;
                }

                List<String> argv = new ArrayList<>();
                for (int i = 0; i < argvJson.length(); i++) {
                    argv.add(argvJson.getString(i));
                }

                String shellCommand = buildShellCommand(argv);
                Shell.Result result = Shell.cmd("sh", "-c", shellCommand).exec();
                JSONObject obj = new JSONObject();
                obj.put("ok", true);
                obj.put("argv", new JSONArray(argv));
                obj.put("shellCommand", shellCommand);
                obj.put("returncode", result.getCode());
                obj.put("stdout", join(result.getOut()));
                obj.put("stderr", join(result.getErr()));
                writeJson(out, 200, obj);
                return;
            }

            writeJson(out, 404, error("not_found"));
        } catch (Exception e) {
            try {
                OutputStream out = socket.getOutputStream();
                JSONObject obj = new JSONObject();
                obj.put("ok", false);
                obj.put("error", String.valueOf(e.getMessage()));
                writeJson(out, 500, obj);
            } catch (Exception ignored) {}
        }
    }

    private JSONObject error(String msg) throws Exception {
        JSONObject obj = new JSONObject();
        obj.put("ok", false);
        obj.put("error", msg);
        return obj;
    }

    private String buildShellCommand(List<String> argv) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < argv.size(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(shellQuote(argv.get(i)));
        }
        return sb.toString();
    }

    private String shellQuote(String s) {
        if (s == null || s.isEmpty()) return "''";
        return "'" + s.replace("'", "'\"'\"'") + "'";
    }

    private String join(List<String> lines) {
        if (lines == null || lines.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private void writeJson(OutputStream out, int code, JSONObject obj) throws Exception {
        byte[] body = obj.toString().getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        header.write(("HTTP/1.1 " + code + " OK\r\n").getBytes(StandardCharsets.UTF_8));
        header.write("Content-Type: application/json; charset=utf-8\r\n".getBytes(StandardCharsets.UTF_8));
        header.write(("Content-Length: " + body.length + "\r\n").getBytes(StandardCharsets.UTF_8));
        header.write("Connection: close\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        out.write(header.toByteArray());
        out.write(body);
        out.flush();
    }
}
