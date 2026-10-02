// SPDX-License-Identifier: Apache-2.0
package io.github.krie.kiosk.plugins.audiostream;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

public final class AacHttpServerTest {
    public static void main(String[] args) throws Exception {
        testStreaming();
        testShutdownAndRestart();
        testConnectionLimit();
        testHeaderLimit();
        testIncompleteHeaders();
        testRequestDeadline();
        System.out.println("AacHttpServerTest passed");
    }

    private static void testStreaming() throws Exception {
        try (AacHttpServer server = new AacHttpServer(0)) {
            server.start();
            int port = server.port();
            if (port <= 0) throw new AssertionError("server did not bind a port");

            String missing = request(port, "GET /missing HTTP/1.1\r\nHost: localhost\r\n\r\n");
            assertStatus(missing, "HTTP/1.1 404 Not Found", "404 endpoint failed");

            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(3000);
                OutputStream out = socket.getOutputStream();
                out.write("GET /audio.aac HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();

                InputStream in = socket.getInputStream();
                String headers = readHeaders(in);
                assertStatus(headers, "HTTP/1.1 200 OK", "stream endpoint failed");
                assertContains(headers, "Content-Type: audio/aac", "wrong content type");

                long deadline = System.nanoTime() + 2_000_000_000L;
                while (server.clientCount() != 1 && System.nanoTime() < deadline) Thread.sleep(10);
                if (server.clientCount() != 1)
                    throw new AssertionError("stream client not registered");

                byte[] frame = new byte[]{(byte) 0xff, (byte) 0xf1, 1, 2, 3, 4, 5, 6};
                server.broadcast(frame);
                byte[] received = readExactly(in, frame.length);
                if (!Arrays.equals(frame, received))
                    throw new AssertionError("broadcast frame changed");
            }
        }

    }

    private static void testShutdownAndRestart() throws Exception {
        try (AacHttpServer server = new AacHttpServer(0)) {
            server.start();
            try (Socket pending = connect(server); Socket streaming = connect(server)) {
                send(pending, "GET /audio.aac HTTP/1.1\r\nHost: localhost\r\n");
                send(streaming, "GET /audio.aac HTTP/1.1\r\n\r\n");
                readHeaders(streaming.getInputStream());
                await(() -> server.connectionCount() == 2 && server.clientCount() == 1,
                        "connections were not registered");
                server.close();
                server.start();
                // Complete a request from the previous server generation after restarting.
                try {
                    send(pending, "\r\n");
                } catch (SocketException expected) {
                    // The server already closed the connection.
                }
                assertClosed(pending);
                assertClosed(streaming);
                if (server.connectionCount() != 0 || server.clientCount() != 0)
                    throw new AssertionError("closed connections survived restart");
                String head = request(server.port(), "HEAD /audio.aac HTTP/1.1\r\n\r\n");
                assertStatus(head, "HTTP/1.1 200 OK", "HEAD request after restart failed");
                assertEndsWith(head, "\r\n\r\n", "HEAD response must not include a body");
            }
        }
    }

    private static void testConnectionLimit() throws Exception {
        List<Socket> sockets = new ArrayList<>();
        try (AacHttpServer server = new AacHttpServer(0)) {
            server.start();
            try {
                for (int i = 0; i < AacHttpServer.MAX_CONNECTIONS; i++) {
                    Socket socket = connect(server);
                    sockets.add(socket);
                    send(socket, "GET /audio.aac HTTP/1.1\r\n");
                }
                await(() -> server.connectionCount() == AacHttpServer.MAX_CONNECTIONS,
                        "pending requests did not fill the connection limit");
                try (Socket excess = connect(server)) {
                    assertClosed(excess);
                }
                // The limit includes both pending requests and active stream clients.
                send(sockets.get(0), "\r\n");
                readHeaders(sockets.get(0).getInputStream());
                await(() -> server.clientCount() == 1, "stream did not start at the connection limit");
                try (Socket excess = connect(server)) {
                    assertClosed(excess);
                }
                sockets.get(1).close();
                await(() -> server.connectionCount() == AacHttpServer.MAX_CONNECTIONS - 1,
                        "disconnected request did not release its slot");
                String response = request(server.port(), "HEAD /audio.aac HTTP/1.1\r\n\r\n");
                assertStatus(response, "HTTP/1.1 200 OK", "released connection slot was not reusable");
                server.close();
                for (Socket socket : sockets) {
                    if (!socket.isClosed()) assertClosed(socket);
                }
                if (server.clientCount() != 0 || server.connectionCount() != 0)
                    throw new AssertionError("shutdown did not clear connection counts");
            } finally {
                for (Socket socket : sockets) socket.close();
            }
        }
    }

    private static void testHeaderLimit() throws Exception {
        try (AacHttpServer server = new AacHttpServer(0)) {
            server.start();
            // An unterminated header must be bounded too, rather than awaiting a newline.
            byte[] oversized = new byte[AacHttpServer.MAX_REQUEST_HEADER_BYTES];
            Arrays.fill(oversized, (byte) 'x');
            byte[] prefix = "GET /audio.aac HTTP/1.1\r\nX: ".getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(prefix, 0, oversized, 0, prefix.length);
            try (Socket socket = connect(server)) {
                socket.getOutputStream().write(oversized);
                socket.getOutputStream().flush();
                String headers = readHeaders(socket.getInputStream());
                assertStatus(
                        headers,
                        "HTTP/1.1 431 Request Header Fields Too Large",
                        "oversized header was not rejected"
                );
            }
            await(() -> server.connectionCount() == 0, "oversized header retained a connection");
            if (server.clientCount() != 0)
                throw new AssertionError("oversized header registered a stream client");

            // A complete request at the byte limit remains valid, even across multiple reads.
            byte[] valid = oversized.clone();
            valid[valid.length - 4] = '\r';
            valid[valid.length - 3] = '\n';
            valid[valid.length - 2] = '\r';
            valid[valid.length - 1] = '\n';
            try (Socket socket = connect(server)) {
                socket.getOutputStream().write(valid, 0, valid.length - 1);
                socket.getOutputStream().flush();
                socket.getOutputStream().write(valid[valid.length - 1]);
                socket.getOutputStream().flush();
                assertStatus(
                        readHeaders(socket.getInputStream()),
                        "HTTP/1.1 200 OK",
                        "complete header at byte limit was rejected"
                );
            }
        }
    }

    private static void testIncompleteHeaders() throws Exception {
        try (AacHttpServer server = new AacHttpServer(0)) {
            server.start();
            try (Socket socket = connect(server)) {
                send(socket, "GET /audio.aac HTTP/1.1\r\nHost: localhost\r\n");
                socket.shutdownOutput();
                assertClosed(socket);
                if (server.clientCount() != 0)
                    throw new AssertionError("incomplete headers registered a stream client");
            }
        }
    }

    private static void testRequestDeadline() throws Exception {
        try (AacHttpServer server = new AacHttpServer(0)) {
            server.start();
            try (Socket socket = connect(server)) {
                socket.setSoTimeout(AacHttpServer.REQUEST_TIMEOUT_MILLIS + 3000);
                send(socket, "GET /audio.aac HTTP/1.1\r\nX: ");
                AtomicBoolean writing = new AtomicBoolean(true);
                Thread trickle = new Thread(() -> {
                    try {
                        while (writing.get()) {
                            send(socket, "x");
                            Thread.sleep(100);
                        }
                    } catch (Exception expected) {
                        // The request deadline closes the socket.
                    }
                }, "http-test-trickle");
                trickle.setDaemon(true);
                long started = System.nanoTime();
                trickle.start();
                try {
                    assertClosed(socket);
                    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                    if (elapsedMs > AacHttpServer.REQUEST_TIMEOUT_MILLIS + 2000)
                        throw new AssertionError("trickled bytes extended the request deadline");
                    await(() -> server.connectionCount() == 0, "timed out request retained its slot");
                    if (server.clientCount() != 0)
                        throw new AssertionError("timed out request registered a stream client");
                } finally {
                    writing.set(false);
                    trickle.interrupt();
                    trickle.join(1000);
                }
            }
        }
    }

    private static Socket connect(AacHttpServer server) throws Exception {
        Socket socket = new Socket("127.0.0.1", server.port());
        socket.setSoTimeout(3000);
        return socket;
    }

    private static void send(Socket socket, String text) throws java.io.IOException {
        socket.getOutputStream().write(text.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }

    private static void assertClosed(Socket socket) throws Exception {
        try {
            if (socket.getInputStream().read() != -1)
                throw new AssertionError("connection returned data instead of closing");
        } catch (SocketException expected) {
            // A reset is also a closed connection.
        }
    }

    private static void assertStatus(String response, String expectedStatus, String message) {
        if (!response.startsWith(expectedStatus)) throw new AssertionError(message);
    }

    private static void assertContains(String response, String expectedText, String message) {
        if (!response.contains(expectedText)) throw new AssertionError(message);
    }

    private static void assertEndsWith(String response, String expectedText, String message) {
        if (!response.endsWith(expectedText)) throw new AssertionError(message);
    }

    private static void await(BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        if (!condition.getAsBoolean()) throw new AssertionError(message);
    }

    private static String request(int port, String request) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(3000);
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            InputStream in = socket.getInputStream();
            byte[] chunk = new byte[1024];
            int read;
            while ((read = in.read(chunk)) >= 0) buffer.write(chunk, 0, read);
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String readHeaders(InputStream in) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int state = 0;
        while (true) {
            int value = in.read();
            if (value < 0) throw new AssertionError("stream closed before headers completed");
            buffer.write(value);
            if (state == 0 && value == '\r') state = 1;
            else if (state == 1 && value == '\n') state = 2;
            else if (state == 2 && value == '\r') state = 3;
            else if (state == 3 && value == '\n') break;
            else state = value == '\r' ? 1 : 0;
        }
        return new String(buffer.toByteArray(), StandardCharsets.US_ASCII);
    }

    private static byte[] readExactly(InputStream in, int length) throws Exception {
        byte[] bytes = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = in.read(bytes, offset, length - offset);
            if (read < 0) throw new AssertionError("stream closed early");
            offset += read;
        }
        return bytes;
    }
}
