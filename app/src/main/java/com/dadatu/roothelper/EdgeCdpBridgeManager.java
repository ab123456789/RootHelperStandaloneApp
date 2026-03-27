package com.dadatu.roothelper;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import com.topjohnwu.superuser.Shell;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class EdgeCdpBridgeManager {

    private static final String DEFAULT_PACKAGE = "com.microsoft.emmx";
    private static final int DEFAULT_PORT = 19222;

    private final Object lock = new Object();
    private volatile BridgeServer bridge;

    public JSONObject openEdgeBridge() throws Exception {
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

            if (bridge != null && bridge.isRunning()) {
                if (bridge.matches(socketName, DEFAULT_PORT)) {
                    return bridgeInfo(true, DEFAULT_PACKAGE, pid, socketName, DEFAULT_PORT);
                }
                bridge.close();
                bridge = null;
            }

            bridge = new BridgeServer(socketName, DEFAULT_PORT);
            bridge.start();
            return bridgeInfo(false, DEFAULT_PACKAGE, pid, socketName, DEFAULT_PORT);
        }
    }

    public JSONObject getStatus() throws Exception {
        synchronized (lock) {
            JSONObject obj = new JSONObject();
            obj.put("ok", true);
            obj.put("package", DEFAULT_PACKAGE);
            obj.put("running", bridge != null && bridge.isRunning());
            if (bridge != null && bridge.isRunning()) {
                obj.put("socketName", bridge.socketName);
                obj.put("localPort", bridge.localPort);
                obj.put("versionUrl", "http://127.0.0.1:" + bridge.localPort + "/json/version");
                obj.put("listUrl", "http://127.0.0.1:" + bridge.localPort + "/json/list");
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

        String fallback = execRoot("cat /proc/net/unix | grep -o '@webview_devtools_remote_[0-9]\+' | head -n 1 || true").trim();
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

    private static class BridgeServer {
        private final String socketName;
        private final int localPort;
        private volatile boolean running;
        private ServerSocket serverSocket;
        private Thread acceptThread;
        private ExecutorService clientPool;

        BridgeServer(String socketName, int localPort) {
            this.socketName = socketName;
            this.localPort = localPort;
        }

        boolean matches(String socketName, int localPort) {
            return this.localPort == localPort && this.socketName.equals(socketName);
        }

        boolean isRunning() {
            return running;
        }

        void start() throws Exception {
            serverSocket = new ServerSocket(localPort, 50);
            serverSocket.setReuseAddress(true);
            clientPool = Executors.newCachedThreadPool();
            running = true;
            acceptThread = new Thread(this::acceptLoop, "edge-cdp-bridge-accept");
            acceptThread.start();
        }

        void close() {
            running = false;
            try {
                if (serverSocket != null) serverSocket.close();
            } catch (Exception ignored) {}
            if (clientPool != null) clientPool.shutdownNow();
        }

        private void acceptLoop() {
            while (running) {
                try {
                    Socket client = serverSocket.accept();
                    clientPool.execute(() -> handleClient(client));
                } catch (Exception e) {
                    if (!running) return;
                }
            }
        }

        private void handleClient(Socket client) {
            LocalSocket local = new LocalSocket();
            try (Socket tcp = client) {
                local.connect(new LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT));
                InputStream tcpIn = tcp.getInputStream();
                OutputStream tcpOut = tcp.getOutputStream();
                InputStream localIn = local.getInputStream();
                OutputStream localOut = local.getOutputStream();

                Thread t1 = new Thread(() -> pipe(tcpIn, localOut));
                Thread t2 = new Thread(() -> pipe(localIn, tcpOut));
                t1.start();
                t2.start();
                t1.join();
                try { local.shutdownOutput(); } catch (Exception ignored) {}
                t2.join();
            } catch (Exception ignored) {
            } finally {
                try { local.close(); } catch (Exception ignored) {}
            }
        }

        private void pipe(InputStream in, OutputStream out) {
            byte[] buffer = new byte[8192];
            int n;
            try {
                while ((n = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, n);
                    out.flush();
                }
            } catch (Exception ignored) {
            } finally {
                try { out.close(); } catch (Exception ignored) {}
            }
        }
    }
}
