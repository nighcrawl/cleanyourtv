package com.gathin.tmdbpicks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class KeyServerTest {
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";
    private static final String GOOD = "0123456789abcdef0123456789abcdef";
    private final AtomicReference<String> received = new AtomicReference<>();
    private KeyServer server;
    private int port;

    @Before public void start() throws Exception {
        KeyServer.Text text = new KeyServer.Text("Picks", "Key", "Send", "Saved", "Refused");
        server = new KeyServer(TOKEN, text, key -> {
            if (!key.equals(GOOD)) return false;
            received.set(key);
            return true;
        });
        port = server.start();
    }

    @After public void stop() {
        server.stop();
    }

    private String request(String raw) throws Exception {
        try (Socket s = new Socket(InetAddress.getLoopbackAddress(), port)) {
            OutputStream out = s.getOutputStream();
            out.write(raw.getBytes(StandardCharsets.UTF_8));
            out.flush();
            InputStream in = s.getInputStream();
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n = in.read(buf); n > 0; n = in.read(buf)) all.write(buf, 0, n);
            return all.toString("UTF-8");
        }
    }

    private static String post(String body) {
        return "POST / HTTP/1.1\r\nHost: tv\r\nContent-Length: " + body.length() + "\r\n\r\n" + body;
    }

    @Test public void theFormIsServedWithTheToken() throws Exception {
        String reply = request("GET /?t=" + TOKEN + " HTTP/1.1\r\nHost: tv\r\n\r\n");
        assertTrue(reply.startsWith("HTTP/1.1 200"));
        assertTrue(reply.contains("name=\"key\""));
    }

    @Test public void nothingIsServedWithoutTheToken() throws Exception {
        assertTrue(request("GET / HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 403"));
        assertTrue(request("GET /?t=wrong HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 403"));
        assertTrue(request(post("t=wrong&key=" + GOOD)).startsWith("HTTP/1.1 403"));
        assertNull(received.get());
    }

    @Test public void aGoodKeyIsHandedOver() throws Exception {
        String reply = request(post("t=" + TOKEN + "&key=%20" + GOOD + "%0A"));
        assertTrue(reply.startsWith("HTTP/1.1 200"));
        assertTrue(reply.contains("Saved"));
        assertEquals(GOOD, received.get());
    }

    @Test public void aRefusedOrMalformedKeyIsNotKept() throws Exception {
        assertTrue(request(post("t=" + TOKEN + "&key=ffffffffffffffffffffffffffffffff")).startsWith("HTTP/1.1 400"));
        assertTrue(request(post("t=" + TOKEN + "&key=has%20space%20inside")).startsWith("HTTP/1.1 400"));
        assertTrue(request(post("t=" + TOKEN)).startsWith("HTTP/1.1 400"));
        assertNull(received.get());
    }

    @Test public void anOversizedBodyIsRefused() throws Exception {
        assertTrue(request("POST / HTTP/1.1\r\nContent-Length: 999999\r\n\r\n").startsWith("HTTP/1.1 413"));
    }

    @Test public void cleanKeepsOnlyKeyShapedText() {
        assertEquals(GOOD, KeyServer.clean("  " + GOOD + "\n"));
        assertNull(KeyServer.clean("short"));
        assertNull(KeyServer.clean("abc def ghi jkl mno pqr"));
        assertNull(KeyServer.clean(null));
        assertFalse(KeyServer.clean("eyJhbGciOiJIUzI1NiJ9.e30.abc_def-ghi") == null);
    }
}
