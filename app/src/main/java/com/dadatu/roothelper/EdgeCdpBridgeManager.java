package com.dadatu.roothelper;

import android.content.Context;
import android.content.pm.ApplicationInfo;

import com.topjohnwu.superuser.Shell;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class EdgeCdpBridgeManager {

    private static final String DEFAULT_PACKAGE = "com.microsoft.emmx";
    private static final int DEFAULT_PORT = 19222;
    private static final String BRIDGE_LOG = "/data/local/tmp/root-edge-bridge.log";
    private static final String BRIDGE_STDOUT_LOG = "/data/local/tmp/root-edge-bridge.stdout.log";

    private final Context appContext;
    private final Object lock = new Object();
    private volatile String forwardedSocketName;

    public EdgeCdpBridgeManager(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public JSONObject openEdgeBridge() throws Exception {
        synchronized (lock) {
            EdgeInfo info = resolveEdgeInfo();
            boolean reused = info.socketName.equals(forwardedSocketName) && isBridgeHealthy(DEFAULT_PORT, DEFAULT_PACKAGE);
            if (!reused) {
                startRootBridge(DEFAULT_PORT, info.socketName);
                forwardedSocketName = info.socketName;
            }
            return bridgeInfo(reused, DEFAULT_PACKAGE, info.pid, info.socketName, DEFAULT_PORT, true);
        }
    }

    public JSONObject getStatus() throws Exception {
        synchronized (lock) {
            JSONObject obj = new JSONObject();
            obj.put("ok", true);
            obj.put("package", DEFAULT_PACKAGE);
            boolean running = forwardedSocketName != null && isBridgeHealthy(DEFAULT_PORT, DEFAULT_PACKAGE);
            obj.put("running", running);
            if (forwardedSocketName != null) {
                obj.put("socketName", forwardedSocketName);
                obj.put("localPort", DEFAULT_PORT);
                obj.put("versionUrl", "http://127.0.0.1:" + DEFAULT_PORT + "/json/version");
                obj.put("listUrl", "http://127.0.0.1:" + DEFAULT_PORT + "/json/list");
            }
            String logTail = tailLog(BRIDGE_STDOUT_LOG);
            if (!logTail.isEmpty()) {
                obj.put("bridgeLogTail", logTail);
            }
            return obj;
        }
    }

    private JSONObject bridgeInfo(boolean reused, String pkg, String pid, String socketName, int port, boolean running) throws Exception {
        JSONObject obj = new JSONObject();
        obj.put("ok", true);
        obj.put("package", pkg);
        obj.put("pid", pid);
        obj.put("socketName", socketName);
        obj.put("localPort", port);
        obj.put("reused", reused);
        obj.put("running", running);
        obj.put("versionUrl", "http://127.0.0.1:" + port + "/json/version");
        obj.put("listUrl", "http://127.0.0.1:" + port + "/json/list");
        String logTail = tailLog(BRIDGE_STDOUT_LOG);
        if (!logTail.isEmpty()) {
            obj.put("bridgeLogTail", logTail);
        }
        return obj;
    }

    private EdgeInfo resolveEdgeInfo() throws Exception {
        synchronized (lock) {
            ensureEdgeRunning(DEFAULT_PACKAGE);
            String pid = findPackagePid(DEFAULT_PACKAGE);
            if (pid == null || pid.isEmpty()) {
                throw new IllegalStateException("edge pid not found");
            }
            String socketName = findDevtoolsSocket(pid);
            if (socketName == null || socketName.isEmpty()) {
                throw new IllegalStateException("edge devtools socket not found");
            }
            return new EdgeInfo(pid, socketName);
        }
    }

    private boolean isLocalPortOpen(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isBridgeHealthy(int port, String expectedPackage) {
        if (!isLocalPortOpen(port)) return false;
        try {
            String body = httpGet("http://127.0.0.1:" + port + "/json/version", 1500, 2500);
            return body.contains(expectedPackage) || body.contains("EdgA/") || body.contains("webSocketDebuggerUrl");
        } catch (Exception ignored) {
            return false;
        }
    }

    private void startRootBridge(int localPort, String socketName) throws Exception {
        ApplicationInfo ai = appContext.getApplicationInfo();
        String apkPath = ai.sourceDir;

        execRoot("pkill -f com.dadatu.roothelper.RootEdgeBridgeMain 2>/dev/null || true");
        execRoot("rm -f " + shellQuote(BRIDGE_LOG) + " " + shellQuote(BRIDGE_STDOUT_LOG));

        String bridgeCmd = "export CLASSPATH=" + shellQuote(apkPath)
            + "; nohup app_process /system/bin com.dadatu.roothelper.RootEdgeBridgeMain "
            + localPort + " " + shellQuote(socketName)
            + " > " + shellQuote(BRIDGE_STDOUT_LOG)
            + " 2>&1 < /dev/null & echo started > " + shellQuote(BRIDGE_LOG);
        execRoot(bridgeCmd);

        Exception lastError = null;
        for (int i = 0; i < 20; i++) {
            Thread.sleep(300);
            if (isBridgeHealthy(localPort, DEFAULT_PACKAGE)) {
                return;
            }
            try {
                String body = httpGet("http://127.0.0.1:" + localPort + "/json/version", 800, 1200);
                if (body.contains(DEFAULT_PACKAGE) || body.contains("EdgA/") || body.contains("webSocketDebuggerUrl")) {
                    return;
                }
            } catch (Exception e) {
                lastError = e;
            }
        }

        String stdout = tailLog(BRIDGE_STDOUT_LOG);
        String marker = execRoot("cat " + shellQuote(BRIDGE_LOG) + " 2>/dev/null || true").trim();
        throw new IllegalStateException(
            "edge bridge failed to come up; marker=" + marker + "; log=" + stdout + (lastError == null ? "" : "; lastError=" + lastError.getMessage())
        );
    }

    private void ensureEdgeRunning(String pkg) throws Exception {
        String pid = findPackagePid(pkg);
        if (pid != null && !pid.isEmpty()) return;

        execRoot("monkey -p " + shellQuote(pkg) + " -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true");
        for (int i = 0; i < 10; i++) {
            Thread.sleep(500);
            pid = findPackagePid(pkg);
            if (pid != null && !pid.isEmpty()) return;
        }
        throw new IllegalStateException("failed to start edge");
    }

    private String findPackagePid(String pkg) throws Exception {
        String out = execRoot("pidof " + shellQuote(pkg) + " || true").trim();
        if (out.isEmpty()) return null;
        return out.split("\\s+")[0].trim();
    }

    private String findDevtoolsSocket(String pid) throws Exception {
        String exact = execRoot("cat /proc/net/unix | grep -o '@webview_devtools_remote_" + pid + "' | head -n 1 || true").trim();
        if (!exact.isEmpty()) {
            return stripAbstractPrefix(exact);
        }

        String fallback = execRoot("cat /proc/net/unix | grep -o '@webview_devtools_remote_[0-9]\\+' | head -n 1 || true").trim();
        if (!fallback.isEmpty()) {
            return stripAbstractPrefix(fallback);
        }

        String chromeFallback = execRoot("cat /proc/net/unix | grep -o '@chrome_devtools_remote' | head -n 1 || true").trim();
        if (!chromeFallback.isEmpty()) {
            return stripAbstractPrefix(chromeFallback);
        }
        return null;
    }

    private String stripAbstractPrefix(String socketName) {
        return socketName.startsWith("@") ? socketName.substring(1) : socketName;
    }

    private String execRoot(String shellCommand) throws Exception {
        String execCommand = "su -M -c " + shellQuote(shellCommand);
        Shell.Result result = Shell.cmd(execCommand).exec();
        String stdout = join(result.getOut());
        String stderr = join(result.getErr());
        if (result.getCode() != 0 && (stdout == null || stdout.trim().isEmpty())) {
            throw new IllegalStateException((stderr == null || stderr.isEmpty()) ? ("command failed: " + shellCommand) : stderr.trim());
        }
        return stdout == null ? "" : stdout;
    }

    private String execRootAllowFailure(String shellCommand) {
        try {
            return execRoot(shellCommand);
        } catch (Exception ignored) {
            return "";
        }
    }

    private String httpGet(String url, int connectTimeoutMs, int readTimeoutMs) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(connectTimeoutMs);
        conn.setReadTimeout(readTimeoutMs);
        conn.setRequestMethod("GET");
        int code = conn.getResponseCode();
        BufferedReader reader = new BufferedReader(new InputStreamReader(
            code >= 200 && code < 400 ? conn.getInputStream() : conn.getErrorStream(),
            StandardCharsets.UTF_8
        ));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append('\n');
        }
        reader.close();
        return sb.toString();
    }

    private String tailLog(String path) {
        try {
            String out = execRoot("tail -n 40 " + shellQuote(path) + " 2>/dev/null || true");
            return out == null ? "" : out.trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private String join(List<String> lines) {
        if (lines == null || lines.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private String shellQuote(String s) {
        if (s == null || s.isEmpty()) return "''";
        return "'" + s.replace("'", "'\"'\"'") + "'";
    }

    private static class EdgeInfo {
        final String pid;
        final String socketName;

        EdgeInfo(String pid, String socketName) {
            this.pid = pid;
            this.socketName = socketName;
        }
    }
}
