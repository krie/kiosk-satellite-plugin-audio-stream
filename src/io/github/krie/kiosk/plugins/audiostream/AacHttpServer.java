// SPDX-License-Identifier: Apache-2.0
package io.github.krie.kiosk.plugins.audiostream;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Tiny HTTP server that exposes a live AAC/ADTS stream at /audio.aac.
 */
final class AacHttpServer implements AutoCloseable {
    private static final int CLIENT_QUEUE_FRAMES = 24;
    static final int MAX_CONNECTIONS = 16;
    static final int MAX_REQUEST_HEADER_BYTES = 8192;
    static final int REQUEST_TIMEOUT_MILLIS = 5000;

    private final int requestedPort;
    // Guarded by this; includes sockets still reading request headers.
    private final Set<Socket> connections = new HashSet<>();
    private final Set<Client> clients = Collections.newSetFromMap(
            new ConcurrentHashMap<Client, Boolean>()
    );
    private volatile boolean running;
    private volatile String error;
    private volatile int boundPort;
    private ServerSocket serverSocket;
    private Thread acceptThread;

    AacHttpServer(int port) {
        this.requestedPort = port;
    }

    synchronized void start() throws IOException {
        if (running) return;

        ServerSocket server = new ServerSocket();
        try {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(requestedPort));
        } catch (IOException e) {
            server.close();
            throw e;
        }
        serverSocket = server;
        boundPort = server.getLocalPort();
        error = null;
        running = true;

        acceptThread = new Thread(() -> acceptLoop(server), "audio-stream-http");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    int port() {
        return boundPort == 0 ? requestedPort : boundPort;
    }

    int clientCount() {
        return clients.size();
    }

    synchronized int connectionCount() {
        return connections.size();
    }

    String error() {
        return error;
    }

    void broadcast(byte[] frame) {
        if (!running || frame == null || frame.length == 0) return;
        for (Client client : clients) client.offer(frame);
    }

    private void acceptLoop(ServerSocket listener) {
        while (running && !listener.isClosed()) {
            try {
                Socket socket = listener.accept();
                long deadlineNs = System.nanoTime()
                        + TimeUnit.MILLISECONDS.toNanos(REQUEST_TIMEOUT_MILLIS);
                synchronized (this) {
                    // A previous accept loop must not register sockets after a restart.
                    if (!running || serverSocket != listener
                            || connections.size() >= MAX_CONNECTIONS) {
                        closeSocket(socket);
                        continue;
                    }
                    connections.add(socket);
                }
                Thread thread = new Thread(
                        () -> handle(socket, deadlineNs),
                        "audio-stream-client"
                );
                thread.setDaemon(true);
                thread.start();
            } catch (IOException e) {
                synchronized (this) {
                    if (running && serverSocket == listener) error = rootMessage(e);
                }
            }
        }
    }

    private void handle(Socket socket, long deadlineNs) {
        Client client = null;
        try {
            socket.setTcpNoDelay(true);
            String requestLine;
            try {
                requestLine = readRequestLine(socket, deadlineNs);
            } catch (RequestTooLargeException e) {
                sendText(socket, 431, "Request Header Fields Too Large",
                        "Request headers are too large\n", false);
                return;
            }
            if (requestLine == null) return;

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                sendText(socket, 400, "Bad Request", "Bad request\n", false);
                return;
            }

            String method = parts[0];
            String path = parts[1];
            int query = path.indexOf('?');
            if (query >= 0) path = path.substring(0, query);

            boolean head = "HEAD".equals(method);
            if (!"GET".equals(method) && !head) {
                sendText(socket, 405, "Method Not Allowed", "GET or HEAD only\n", false);
                return;
            }

            if (!"/audio.aac".equals(path)) {
                sendText(socket, 404, "Not Found", "Use /audio.aac\n", head);
                return;
            }

            OutputStream out = socket.getOutputStream();
            if (!head) {
                client = new Client(socket, out);
                synchronized (this) {
                    if (!running || !connections.contains(socket)) return;
                    clients.add(client);
                }
            }
            writeAscii(
                    out,
                    "HTTP/1.1 200 OK\r\n"
                            + "Content-Type: audio/aac\r\n"
                            + "Cache-Control: no-store, no-cache, must-revalidate\r\n"
                            + "Pragma: no-cache\r\n"
                            + "X-Content-Type-Options: nosniff\r\n"
                            + "Connection: close\r\n"
                            + "\r\n"
            );
            out.flush();
            if (head) return;

            socket.setSoTimeout(0);
            client.run();
        } catch (IOException ignored) {
            // Normal when a client disconnects or exceeds the request deadline.
        } finally {
            if (client != null) {
                clients.remove(client);
                client.close();
            }
            closeSocket(socket);
            synchronized (this) {
                connections.remove(socket);
            }
        }
    }

    private static String readRequestLine(Socket socket, long deadlineNs) throws IOException {
        InputStream in = socket.getInputStream();
        byte[] headers = new byte[MAX_REQUEST_HEADER_BYTES];
        int used = 0;
        while (used < headers.length) {
            long remainingNs = deadlineNs - System.nanoTime();
            if (remainingNs <= 0) throw new SocketTimeoutException("Request header deadline exceeded");
            long timeoutMs = Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNs));
            socket.setSoTimeout((int) timeoutMs);
            int read = in.read(headers, used, headers.length - used);
            if (read < 0) return null;
            if (System.nanoTime() >= deadlineNs)
                throw new SocketTimeoutException("Request header deadline exceeded");

            int previous = used;
            used += read;
            for (int i = Math.max(1, previous - 3); i < used; i++) {
                boolean end = headers[i] == '\n' && (headers[i - 1] == '\n'
                        || (i >= 3 && headers[i - 3] == '\r'
                        && headers[i - 2] == '\n' && headers[i - 1] == '\r'));
                if (end) {
                    int lineEnd = 0;
                    while (lineEnd < used && headers[lineEnd] != '\n') lineEnd++;
                    if (lineEnd > 0 && headers[lineEnd - 1] == '\r') lineEnd--;
                    return new String(headers, 0, lineEnd, StandardCharsets.US_ASCII);
                }
            }
        }
        throw new RequestTooLargeException();
    }

    private static final class RequestTooLargeException extends IOException {
        private static final long serialVersionUID = 1L;
    }

    private static void closeSocket(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private static void sendText(
            Socket socket,
            int code,
            String reason,
            String body,
            boolean head
    ) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        OutputStream out = socket.getOutputStream();
        writeAscii(
                out,
                "HTTP/1.1 " + code + " " + reason + "\r\n"
                        + "Content-Type: text/plain; charset=utf-8\r\n"
                        + "Content-Length: " + bytes.length + "\r\n"
                        + "X-Content-Type-Options: nosniff\r\n"
                        + "Connection: close\r\n\r\n"
        );
        if (!head) out.write(bytes);
        out.flush();
    }

    private static void writeAscii(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.US_ASCII));
    }

    @Override
    public synchronized void close() {
        running = false;

        ServerSocket server = serverSocket;
        serverSocket = null;
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
            }
        }

        for (Client client : clients) client.close();
        clients.clear();
        for (Socket socket : connections) closeSocket(socket);
        connections.clear();

        Thread thread = acceptThread;
        acceptThread = null;
        if (thread != null) thread.interrupt();
        boundPort = 0;
    }

    private static final class Client implements AutoCloseable {
        private final Socket socket;
        private final OutputStream out;
        private final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(
                CLIENT_QUEUE_FRAMES
        );
        private volatile boolean running = true;

        Client(Socket socket, OutputStream out) {
            this.socket = socket;
            this.out = out;
        }

        void offer(byte[] frame) {
            if (!running) return;
            if (!queue.offer(frame)) {
                queue.poll();
                queue.offer(frame);
            }
        }

        void run() throws IOException {
            while (running) {
                byte[] frame;
                try {
                    frame = queue.poll(1, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (frame != null) {
                    out.write(frame);
                    out.flush();
                }
            }
        }

        @Override
        public void close() {
            running = false;
            queue.clear();
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return current.getClass().getSimpleName()
                + (message == null || message.isEmpty() ? "" : ": " + message);
    }
}
