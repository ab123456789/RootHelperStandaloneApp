package com.dadatu.roothelper;

import com.topjohnwu.superuser.Shell;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RootCommandServer {

    private static final String DEBUG_LOG = "/data/user/0/com.dadatu.roothelper/files/roothelper-server.log";

    private final EdgeCdpBridgeManager edgeCdpBridgeManager;
    private volatile boolean running;
    private ServerSocket serverSocket;
    private ExecutorService clientPool;
    private Thread acceptThread;

    public RootCommandServer(android.content.Context context) {
        this.edgeCdpBridgeManager = new EdgeCdpBridgeManager(context);
    }

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
            log("requestLine=" + requestLine);
            if (requestLine == null || requestLine.isEmpty()) {
                log("bad_request empty request line");
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
            log("method=" + method + " path=" + path + " contentLength=" + contentLength + " tokenMatch=" + RootHelperConfig.TOKEN.equals(token));

            if ("GET".equals(method) && "/ping".equals(path)) {
                JSONObject obj = new JSONObject();
                obj.put("ok", true);
                obj.put("service", "roothelper");
                obj.put("mode", "standalone-app");
                writeJson(out, 200, obj);
                log("ping ok");
                return;
            }

            if ("GET".equals(method) && "/edge/status".equals(path)) {
                log("edge status start");
                writeJson(out, 200, edgeCdpBridgeManager.getStatus());
                log("edge status ok");
                return;
            }

            if ("POST".equals(method) && "/edge/open".equals(path)) {
                log("edge open start");
                String edgeBody = readRequestBody(reader, contentLength);
                log("edge open body=" + edgeBody);
                JSONObject result = edgeCdpBridgeManager.openEdgeBridge();
                log("edge open result=" + result.toString());
                writeJson(out, 200, result);
                log("edge open response sent");
                return;
            }

            if ("POST".equals(method) && "/exec".equals(path)) {
                String body = readRequestBody(reader, contentLength);
                log("exec body=" + body);
                JSONObject req = new JSONObject(body.isEmpty() ? "{}" : body);
                JSONArray argvJson = req.optJSONArray("argv");
                if (argvJson == null || argvJson.length() == 0) {
                    log("exec invalid argv");
                    writeJson(out, 400, error("argv must be non-empty string list"));
                    return;
                }

                List<String> argv = new ArrayList<>();
                for (int i = 0; i < argvJson.length(); i++) {
                    argv.add(argvJson.getString(i));
                }

                String shellCommand = buildShellCommand(argv);
                String execCommand = buildSuExecCommand(shellCommand);
                log("exec command=" + execCommand);
                Shell.Result result = Shell.cmd(execCommand).exec();
                JSONObject obj = new JSONObject();
                obj.put("ok", true);
                obj.put("argv", new JSONArray(argv));
                obj.put("shellCommand", shellCommand);
                obj.put("execCommand", execCommand);
                obj.put("returncode", result.getCode());
                obj.put("stdout", join(result.getOut()));
                obj.put("stderr", join(result.getErr()));
                writeJson(out, 200, obj);
                log("exec response sent rc=" + result.getCode());
                return;
            }

            log("not found path=" + path);
            writeJson(out, 404, error("not_found"));
        } catch (Throwable e) {
            log("handle exception=" + android.util.Log.getStackTraceString(e));
            try {
                if (!socket.isClosed() && socket.isConnected() && !socket.isOutputShutdown()) {
                    OutputStream out = socket.getOutputStream();
                    JSONObject obj = new JSONObject();
                    obj.put("ok", false);
                    obj.put("error", String.valueOf(e.getMessage()));
                    obj.put("errorType", e.getClass().getName());
                    writeJson(out, 500, obj);
                    log("error response sent");
                } else {
                    log("socket already closed before error response");
                }
            } catch (Exception inner) {
                log("failed to send error response=" + android.util.Log.getStackTraceString(inner));
            }
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

    private String buildSuExecCommand(String shellCommand) {
        return "su -M -c " + shellQuote(shellCommand);
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

    private String readRequestBody(BufferedReader reader, int contentLength) throws Exception {
        char[] bodyChars = new char[Math.max(contentLength, 0)];
        int read = 0;
        while (read < bodyChars.length) {
            int n = reader.read(bodyChars, read, bodyChars.length - read);
            if (n < 0) break;
            read += n;
        }
        return new String(bodyChars, 0, read);
    }

    private void log(String msg) {
        try (FileOutputStream fos = new FileOutputStream(DEBUG_LOG, true)) {
            String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
            String line = ts + " " + msg + "\n";
            fos.write(line.getBytes(StandardCharsets.UTF_8));
            fos.flush();
        } catch (Exception ignored) {
        }
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
