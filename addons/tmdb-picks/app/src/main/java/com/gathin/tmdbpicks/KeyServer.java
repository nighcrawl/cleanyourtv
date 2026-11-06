package com.gathin.tmdbpicks;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/**
 * A one-page web server for the pairing screen: the phone opens the address in the QR code, pastes the TMDB key, and
 * the page hands it to the app. It exists only while that screen is open, serves one request at a time, answers
 * nothing without the random token that is in the QR code, and the app stops it as soon as it has a key.
 */
final class KeyServer {
    /** Gets the key the phone sent; true when it is good, which ends the pairing. */
    interface Receiver {
        boolean accept(String key) throws IOException;
    }

    /** The words on the web page, in the TV's language. */
    static final class Text {
        final String title;
        final String label;
        final String button;
        final String saved;
        final String invalid;

        Text(String title, String label, String button, String saved, String invalid) {
            this.title = title;
            this.label = label;
            this.button = button;
            this.saved = saved;
            this.invalid = invalid;
        }
    }

    private static final int FIRST_PORT = 8765;
    private static final int PORTS = 10;
    private static final int MAX_BODY = 2048;
    private static final int MAX_LINE = 2048;
    private static final int TIMEOUT_MS = 5000;

    private final String token;
    private final Text text;
    private final Receiver receiver;
    private ServerSocket socket;

    KeyServer(String token, Text text, Receiver receiver) {
        this.token = token;
        this.text = text;
        this.receiver = receiver;
    }

    /** Listens on the first free port from 8765 and returns it. */
    int start() throws IOException {
        IOException last = null;
        for (int port = FIRST_PORT; port < FIRST_PORT + PORTS; port++) {
            try {
                socket = new ServerSocket(port);
                break;
            } catch (IOException e) {
                last = e;
            }
        }
        if (socket == null) throw last;
        Thread thread = new Thread(this::run, "key-server");
        thread.setDaemon(true);
        thread.start();
        return socket.getLocalPort();
    }

    void stop() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
    }

    private void run() {
        while (!socket.isClosed()) {
            try (Socket client = socket.accept()) {
                client.setSoTimeout(TIMEOUT_MS);
                serve(client);
            } catch (IOException e) {
                // A stalled or broken client, or stop(): next connection.
            }
        }
    }

    private void serve(Socket client) throws IOException {
        InputStream in = client.getInputStream();
        OutputStream out = client.getOutputStream();
        String[] request = readLine(in).split(" ");
        if (request.length < 2) {
            reply(out, 400, "text/plain", "Bad request");
            return;
        }
        int length = 0;
        for (String header = readLine(in); !header.isEmpty(); header = readLine(in)) {
            if (header.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                try {
                    length = Integer.parseInt(header.substring(15).trim());
                } catch (NumberFormatException e) {
                    length = -1;
                }
            }
        }
        if (length < 0 || length > MAX_BODY) {
            reply(out, 413, "text/plain", "Too large");
            return;
        }
        boolean post = request[0].equals("POST");
        String source;
        if (post) {
            byte[] body = new byte[length];
            for (int read = 0; read < length; ) {
                int n = in.read(body, read, length - read);
                if (n < 0) throw new IOException("short body");
                read += n;
            }
            source = new String(body, StandardCharsets.UTF_8);
        } else {
            int q = request[1].indexOf('?');
            source = q < 0 ? "" : request[1].substring(q + 1);
        }
        Map<String, String> params = parse(source);
        String given = params.get("t");
        if (given == null || !MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) {
            reply(out, 403, "text/plain", "Forbidden");
            return;
        }
        if (!post) {
            reply(out, 200, "text/html", form());
            return;
        }
        String key = clean(params.get("key"));
        if (key == null) {
            reply(out, 400, "text/html", page(esc(text.invalid)));
        } else if (receiver.accept(key)) {
            reply(out, 200, "text/html", page(esc(text.saved)));
        } else {
            reply(out, 400, "text/html", page(esc(text.invalid)));
        }
    }

    /** A TMDB key or token is letters, digits and . _ - only; anything else is a paste mistake, not a key. */
    static String clean(String key) {
        if (key == null) return null;
        key = key.trim();
        return key.matches("[A-Za-z0-9._-]{16,600}") ? key : null;
    }

    private String form() {
        return page("<form method=\"post\" action=\"/\"><input type=\"hidden\" name=\"t\" value=\"" + token + "\">"
                + "<label>" + esc(text.label) + "<br><input name=\"key\" autofocus autocomplete=\"off\" "
                + "autocapitalize=\"off\" spellcheck=\"false\" style=\"width:100%;font-size:1rem;margin:.6rem 0\"></label>"
                + "<button style=\"font-size:1rem;padding:.6rem 1rem\">" + esc(text.button) + "</button></form>");
    }

    private String page(String body) {
        return "<!doctype html><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>" + esc(text.title) + "</title><body style=\"font-family:sans-serif;max-width:30rem;margin:2rem auto;padding:0 1rem\">"
                + "<h1>" + esc(text.title) + "</h1>" + body;
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static Map<String, String> parse(String s) throws IOException {
        Map<String, String> map = new HashMap<>();
        for (String pair : s.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            try {
                map.put(URLDecoder.decode(pair.substring(0, eq), "UTF-8"), URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
            } catch (IllegalArgumentException e) {
                throw new IOException("bad encoding", e);
            }
        }
        return map;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        for (int b = in.read(); b != '\n'; b = in.read()) {
            if (b < 0 || line.size() >= MAX_LINE) throw new IOException("bad line");
            if (b != '\r') line.write(b);
        }
        return line.toString(StandardCharsets.UTF_8.name());
    }

    private static void reply(OutputStream out, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + " " + (status == 200 ? "OK" : "Error") + "\r\nContent-Type: " + type
                + "; charset=utf-8\r\nContent-Length: " + bytes.length + "\r\nCache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.flush();
    }
}
