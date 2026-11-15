package com.inpulync.app;

import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Cable-only loopback transport used through adb forward. */
final class UsbBridgeServer {
    interface Listener {
        void onHostLine(String line);
        void onStateChanged();
    }

    private static final int PORT = 27183;
    private final ExecutorService readers = Executors.newCachedThreadPool();
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private final List<Client> clients = new CopyOnWriteArrayList<>();
    private final Listener listener;
    private final Supplier<String> pairCode;
    private final SecureRandom random = new SecureRandom();
    private ServerSocket server;

    UsbBridgeServer(Supplier<String> pairCode, Listener listener) {
        this.pairCode = pairCode;
        this.listener = listener;
    }

    void start() {
        readers.execute(() -> {
            try {
                server = new ServerSocket(PORT, 4, InetAddress.getByName("127.0.0.1"));
                listener.onStateChanged();
                while (!server.isClosed()) {
                    Client client = new Client(server.accept());
                    // Handshake off the accept thread so a silent client cannot block others.
                    readers.execute(() -> authenticate(client));
                }
            } catch (IOException ignored) {
                listener.onStateChanged();
            }
        });
    }

    boolean isStarted() {
        return server != null && !server.isClosed();
    }

    boolean hasClients() {
        return !clients.isEmpty();
    }

    void send(String line) {
        writer.execute(() -> {
            for (Client client : new ArrayList<>(clients)) {
                if (!client.write(line)) {
                    clients.remove(client);
                    client.close();
                }
            }
            listener.onStateChanged();
        });
    }

    void close() {
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        for (Client client : clients) client.close();
        readers.shutdownNow();
        writer.shutdownNow();
    }

    /**
     * Mutual HMAC handshake using the pair code shown in Settings. The PC only trusts a
     * phone that proves it knows the code, and the phone only serves a PC that does too.
     */
    private void authenticate(Client client) {
        try {
            String serverNonce = nonce();
            if (!client.write("HELLO INPULYNC 2 " + serverNonce)) {
                client.close();
                return;
            }
            client.setTimeout(4000);
            String reply = client.readLine();
            String[] parts = reply == null ? new String[0] : reply.trim().split(" ");
            if (parts.length != 3 || !"AUTH".equals(parts[0]) || parts[1].length() > 64) {
                client.close();
                return;
            }
            String code = normalize(pairCode.get());
            String clientNonce = parts[1];
            byte[] expected = hmac(code, "C|" + serverNonce + "|" + clientNonce).getBytes(
                    StandardCharsets.US_ASCII);
            byte[] given = parts[2].toLowerCase().getBytes(StandardCharsets.US_ASCII);
            if (!MessageDigest.isEqual(expected, given)) {
                Log.w("InpuLync", "USB client failed pair-code check");
                client.close();
                return;
            }
            if (!client.write("OK " + hmac(code, "S|" + serverNonce + "|" + clientNonce))) {
                client.close();
                return;
            }
            client.setTimeout(0);
            clients.add(client);
            listener.onStateChanged();
            read(client);
        } catch (IOException | GeneralSecurityException e) {
            client.close();
        }
    }

    static String normalize(String code) {
        return code == null ? "" : code.toUpperCase().replaceAll("[^A-Z0-9]", "");
    }

    private String nonce() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return hex(bytes);
    }

    static String hmac(String code, String message) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(code.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return hex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format("%02x", b));
        return out.toString();
    }

    private void read(Client client) {
        try {
            String line;
            while ((line = client.readLine()) != null) listener.onHostLine(line);
        } catch (IOException e) {
            Log.d("InpuLync", "Laptop battery link disconnected");
        } finally {
            clients.remove(client);
            client.close();
            listener.onStateChanged();
        }
    }

    private static final class Client {
        private final Socket socket;
        private final BufferedReader reader;
        private final BufferedWriter writer;

        Client(Socket socket) throws IOException {
            this.socket = socket;
            reader = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.UTF_8));
            writer = new BufferedWriter(new OutputStreamWriter(
                    socket.getOutputStream(), StandardCharsets.UTF_8));
        }

        String readLine() throws IOException {
            return reader.readLine();
        }

        void setTimeout(int millis) throws IOException {
            socket.setSoTimeout(millis);
        }

        synchronized boolean write(String line) {
            try {
                writer.write(line);
                writer.newLine();
                writer.flush();
                return true;
            } catch (IOException e) {
                Log.w("InpuLync", "USB client disconnected", e);
                return false;
            }
        }

        void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
