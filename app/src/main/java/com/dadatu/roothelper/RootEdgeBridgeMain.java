package com.dadatu.roothelper;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RootEdgeBridgeMain {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: RootEdgeBridgeMain <port> <socketName>");
            System.exit(2);
            return;
        }

        int port = Integer.parseInt(args[0]);
        String socketName = args[1];

        ServerSocket server = new ServerSocket(port, 50);
        server.setReuseAddress(true);
        ExecutorService pool = Executors.newCachedThreadPool();
        System.out.println("LISTENING 127.0.0.1:" + port + " -> abstract:" + socketName);
        System.out.flush();

        while (true) {
            final Socket client = server.accept();
            pool.execute(() -> handle(client, socketName));
        }
    }

    private static void handle(Socket client, String socketName) {
        LocalSocket local = new LocalSocket();
        try (Socket tcp = client) {
            tcp.setKeepAlive(true);
            tcp.setTcpNoDelay(true);
            local.connect(new LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT));

            InputStream tcpIn = tcp.getInputStream();
            OutputStream tcpOut = tcp.getOutputStream();
            InputStream localIn = local.getInputStream();
            OutputStream localOut = local.getOutputStream();

            CountDownLatch done = new CountDownLatch(2);
            Thread t1 = new Thread(() -> pipeTcpToLocal(tcp, tcpIn, local, localOut, done), "root-edge-tcp-to-local");
            Thread t2 = new Thread(() -> pipeLocalToTcp(local, localIn, tcp, tcpOut, done), "root-edge-local-to-tcp");
            t1.start();
            t2.start();
            done.await();
        } catch (Exception ignored) {
        } finally {
            try { local.close(); } catch (Exception ignored) {}
        }
    }

    private static void pipeTcpToLocal(Socket tcp, InputStream in, LocalSocket local, OutputStream out, CountDownLatch done) {
        pipe(in, out);
        try { local.shutdownOutput(); } catch (Exception ignored) {}
        try { tcp.shutdownInput(); } catch (Exception ignored) {}
        done.countDown();
    }

    private static void pipeLocalToTcp(LocalSocket local, InputStream in, Socket tcp, OutputStream out, CountDownLatch done) {
        pipe(in, out);
        try { tcp.shutdownOutput(); } catch (Exception ignored) {}
        try { local.shutdownInput(); } catch (Exception ignored) {}
        done.countDown();
    }

    private static void pipe(InputStream in, OutputStream out) {
        byte[] buffer = new byte[8192];
        try {
            while (true) {
                int n = in.read(buffer);
                if (n < 0) break;
                if (n == 0) continue;
                out.write(buffer, 0, n);
                out.flush();
            }
        } catch (SocketException ignored) {
        } catch (Exception ignored) {
        }
    }
}
