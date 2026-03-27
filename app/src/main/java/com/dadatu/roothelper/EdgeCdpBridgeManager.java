package com.dadatu.roothelper;

import android.content.Context;
import android.content.pm.ApplicationInfo;

import com.topjohnwu.superuser.Shell;

import org.json.JSONObject;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;

public class EdgeCdpBridgeManager {

    private static final String DEFAULT_PACKAGE = "com.microsoft.emmx";
    private static final int DEFAULT_PORT = 19222;

    private final Context appContext;
    private final Object lock = new Object();
    private volatile String forwardedSocketName;

    public EdgeCdpBridgeManager(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public JSONObject openEdgeBridge() throws Exception {
        synchronized (lock) {
            EdgeInfo info = resolveEdgeInfo();
            boolean reused = info.socketName.equals(forwardedSocketName) && isLocalPortOpen(DEFAULT_PORT);
            if (!reused) {
                startRootBridge(DEFAULT_PORT, info.socketName);
                forwardedSocketName = info.socketName;
            }
            return bridgeInfo(reused, DEFAULT_PACKAGE, info.pid, info.socketName, DEFAULT_PORT);
        }
    }

    public JSONObject getStatus() throws Exception {
        synchronized (lock) {
            JSONObject obj = new JSONObject();
            obj.put("ok", true);
            obj.put("package", DEFAULT_PACKAGE);
            obj.put("running", forwardedSocketName != null && isLocalPortOpen(DEFAULT_PORT));
            if (forwardedSocketName != null) {
                obj.put("socketName", forwardedSocketName);
                obj.put("localPort", DEFAULT_PORT);
                obj.put("versionUrl", "http://127.0.0.1:" + DEFAULT_PORT + "/json/version");
                obj.put("listUrl", "http://127.0.0.1:" + DEFAULT_PORT + "/json/list");
            }
            return obj;
        }
    }

    private JSONObject bridgeInfo(boolean reused, String pkg, String pid, String socketName, int port) throws Exception {
        JSONObject obj = new JSONObject();
        obj.put("ok", true);
        obj.put("package", pkg);
        obj.put("pid", pid);
        obj.put("socketName", socketName);
        obj.put("localPort", port);
        obj.put("reused", reused);
        obj.put("versionUrl", "http://127.0.0.1:" + port + "/json/version");
        obj.put("listUrl", "http://127.0.0.1:" + port + "/json/list");
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

    private void startRootBridge(int localPort, String socketName) throws Exception {
        ApplicationInfo ai = appContext.getApplicationInfo();
        String apkPath = ai.sourceDir;

        execRoot("rm -f /data/local/tmp/root-probe.log /data/local/tmp/root-edge-bridge.log");

        String probeCmd = "CLASSPATH=" + shellQuote(apkPath)
            + " app_process /system/bin com.dadatu.roothelper.RootProbeMain "
            + localPort + " " + shellQuote(socketName)
            + " > /data/local/tmp/root-probe-stdout.log 2>&1";
        execRoot(probeCmd);

        String probeLog = execRoot("cat /data/local/tmp/root-probe.log 2>/dev/null || true").trim();
        if (probeLog.isEmpty()) {
            String stdout = execRoot("cat /data/local/tmp/root-probe-stdout.log 2>/dev/null || true");
            throw new IllegalStateException("root probe did not write log. stdout=" + stdout);
        }

        throw new IllegalStateException("root probe ok; bridge not re-enabled yet");
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
