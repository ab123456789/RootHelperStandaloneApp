package com.dadatu.roothelper;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import com.topjohnwu.superuser.Shell;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;

public class EdgeCdpBridgeManager {

    private static final String DEFAULT_PACKAGE = "com.microsoft.emmx";
    private static final int DEFAULT_PORT = 19222;
    private static final String DEFAULT_SERIAL = "emulator-5554";

    private final Object lock = new Object();
    private volatile String forwardedSocketName;

    public JSONObject openEdgeBridge() throws Exception {
        synchronized (lock) {
            EdgeInfo info = resolveEdgeInfo();
            boolean reused = info.socketName.equals(forwardedSocketName) && isLocalPortOpen(DEFAULT_PORT);
            if (!reused) {
                adbKillForward(DEFAULT_PORT);
                adbForward(DEFAULT_PORT, info.socketName);
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

    private void adbForward(int localPort, String socketName) throws Exception {
        String cmd = "host-serial:" + DEFAULT_SERIAL + ":forward:tcp:" + localPort + ";localabstract:" + socketName;
        byte[] response = adbRequest(cmd);
        String body = decodeAdbBody(response);
        if (body != null && !body.isEmpty() && !body.contains(String.valueOf(localPort))) {
            throw new IllegalStateException("adb forward failed: " + body);
        }
    }

    private void adbKillForward(int localPort) throws Exception {
        adbRequest("host-serial:" + DEFAULT_SERIAL + ":killforward:tcp:" + localPort);
    }

    private byte[] adbRequest(String command) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 5037), 2000);
            socket.setSoTimeout(2000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            byte[] payload = String.format("%04x%s", command.length(), command).getBytes();
            out.write(payload);
            out.flush();

            byte[] status = readExact(in, 4);
            if (status == null) {
                throw new IllegalStateException("adb status empty");
            }
            String statusText = new String(status);
            ByteArrayOutputStream rest = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            try {
                while (true) {
                    int n = in.read(buf);
                    if (n < 0) break;
                    if (n == 0) continue;
                    rest.write(buf, 0, n);
                }
            } catch (Exception ignored) {
            }
            if (!"OKAY".equals(statusText)) {
                throw new IllegalStateException("adb request failed: " + decodeAdbBody(rest.toByteArray()));
            }
            return rest.toByteArray();
        }
    }

    private byte[] readExact(InputStream in, int length) throws Exception {
        byte[] data = new byte[length];
        int off = 0;
        while (off < length) {
            int n = in.read(data, off, length - off);
            if (n < 0) return null;
            off += n;
        }
        return data;
    }

    private String decodeAdbBody(byte[] body) {
        if (body == null || body.length == 0) return "";
        String s = new String(body);
        if (s.length() >= 4) {
            try {
                int n = Integer.parseInt(s.substring(0, 4), 16);
                if (s.length() >= 4 + n) {
                    return s.substring(4, 4 + n);
                }
            } catch (Exception ignored) {
            }
        }
        return s;
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
