package themythofcrawl.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Locale;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import themythofcrawl.model.Service;

/**
 * V_10.4 "Gửi riêng": sends one raw HTTP/1.1 request with a plain socket, outside Burp's networking, so Burp tools
 * and extensions that log through Burp (Logger, Logger++) never see it. Like Burp, the server certificate is not
 * verified (test targets often use self-signed certificates). {@code Connection: close} is forced so the response
 * ends at end of stream; a chunked body is decoded and given a Content-Length.
 */
public final class DirectHttp {
    private static final int TIMEOUT_MS = 30_000;
    private static final int MAX_BYTES = 64 * 1024 * 1024;

    private DirectHttp() {
    }

    public static Bridge.Sent send(Service s, byte[] request) {
        long t0 = System.currentTimeMillis();
        try (Socket sock = open(s)) {
            sock.setSoTimeout(TIMEOUT_MS);
            OutputStream out = sock.getOutputStream();
            out.write(prepare(request));
            out.flush();
            byte[] raw = readAll(sock.getInputStream());
            long ms = System.currentTimeMillis() - t0;
            if (raw.length == 0) {
                return new Bridge.Sent(null, ms, "Không có response (gửi riêng)");
            }
            return new Bridge.Sent(dechunk(raw), ms, null);
        } catch (IOException | RuntimeException e) {
            return new Bridge.Sent(null, System.currentTimeMillis() - t0, "Gửi riêng lỗi: " + e.getMessage());
        }
    }

    private static Socket open(Service s) throws IOException {
        Socket plain = new Socket();
        plain.connect(new InetSocketAddress(s.host(), s.port()), TIMEOUT_MS);
        if (!s.secure()) {
            return plain;
        }
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[] {new TrustAll()}, new SecureRandom());
            SSLSocket ssl = (SSLSocket) ctx.getSocketFactory().createSocket(plain, s.host(), s.port(), true);
            ssl.startHandshake();
            return ssl;
        } catch (java.security.GeneralSecurityException e) {
            plain.close();
            throw new IOException(e.getMessage(), e);
        }
    }

    /** HTTP/1.1 request line, one "Connection: close" header, body untouched. */
    static byte[] prepare(byte[] request) {
        String raw = new String(request, StandardCharsets.ISO_8859_1);
        int split = raw.indexOf("\r\n\r\n");
        String head = split >= 0 ? raw.substring(0, split) : raw;
        String body = split >= 0 ? raw.substring(split + 4) : "";
        String[] lines = head.split("\r\n");
        StringBuilder sb = new StringBuilder();
        String first = lines.length > 0 ? lines[0] : "GET / HTTP/1.1";
        sb.append(first.replaceAll("HTTP/2(\\.0)?$", "HTTP/1.1")).append("\r\n");
        for (int i = 1; i < lines.length; i++) {
            String low = lines[i].toLowerCase(Locale.ROOT);
            if (low.startsWith("connection:") || low.startsWith("keep-alive:") || low.startsWith("proxy-connection:")) {
                continue;
            }
            sb.append(lines[i]).append("\r\n");
        }
        sb.append("Connection: close\r\n\r\n").append(body);
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
            bo.write(buf, 0, n);
            if (bo.size() > MAX_BYTES) {
                break;
            }
        }
        return bo.toByteArray();
    }

    /** Chunked transfer-encoding -> plain body + Content-Length (so Burp-style viewers show the real body). */
    static byte[] dechunk(byte[] resp) {
        String raw = new String(resp, StandardCharsets.ISO_8859_1);
        int split = raw.indexOf("\r\n\r\n");
        if (split < 0 || !raw.substring(0, split).toLowerCase(Locale.ROOT).contains("transfer-encoding: chunked")) {
            return resp;
        }
        String head = raw.substring(0, split);
        String rest = raw.substring(split + 4);
        StringBuilder body = new StringBuilder();
        int pos = 0;
        while (pos < rest.length()) {
            int eol = rest.indexOf("\r\n", pos);
            if (eol < 0) {
                break;
            }
            int size;
            try {
                size = Integer.parseInt(rest.substring(pos, eol).split(";", 2)[0].trim(), 16);
            } catch (NumberFormatException e) {
                break;
            }
            if (size == 0) {
                break;
            }
            int start = eol + 2;
            int end = Math.min(rest.length(), start + size);
            body.append(rest, start, end);
            pos = end + 2;
        }
        StringBuilder h = new StringBuilder();
        for (String line : head.split("\r\n")) {
            String low = line.toLowerCase(Locale.ROOT);
            if (low.startsWith("transfer-encoding:") || low.startsWith("content-length:")) {
                continue;
            }
            h.append(line).append("\r\n");
        }
        h.append("Content-Length: ").append(body.length()).append("\r\n\r\n").append(body);
        return h.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static final class TrustAll implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
