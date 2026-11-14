package com.goutou.junshi.core;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Minimal HTTP helper.
 *
 * <p>Only what the question-bank clients need: a GET and a form POST with a fixed user agent and
 * hard timeouts.  Everything runs on a caller supplied background thread; nothing here touches the
 * UI thread and no response is cached.</p>
 */
public final class Http {

    public static final String UA = "Lumen/1.0 (Android)";

    private Http() {
    }

    public static String get(String url, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            conn = open(url, "GET", timeoutMs);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                Logx.w("http " + code + " " + host(url));
                return null;
            }
            return read(conn.getInputStream());
        } catch (Throwable t) {
            Logx.w("http error " + host(url) + " :: " + t.getClass().getSimpleName());
            return null;
        } finally {
            close(conn);
        }
    }

    /** {@code body} is an already URL-encoded form string. */
    public static String postForm(String url, String body, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            conn = open(url, "POST", timeoutMs);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(payload.length);
            OutputStream out = conn.getOutputStream();
            try {
                out.write(payload);
            } finally {
                out.close();
            }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                Logx.w("http " + code + " " + host(url));
                return null;
            }
            return read(conn.getInputStream());
        } catch (Throwable t) {
            Logx.w("http error " + host(url) + " :: " + t.getClass().getSimpleName());
            return null;
        } finally {
            close(conn);
        }
    }

    /** {@code body} is a JSON document. */
    public static String requestJson(String url, String method, String body, int timeoutMs) {
        return requestJson(url, method, body, null, timeoutMs);
    }

    /** Same as {@link #requestJson(String, String, String, int)} with a bearer token. */
    public static String requestJson(String url, String method, String body, String bearer,
                                     int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            conn = open(url, method, timeoutMs);
            if (bearer != null && !bearer.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + bearer);
            }
            if (body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(payload.length);
                OutputStream out = conn.getOutputStream();
                try {
                    out.write(payload);
                } finally {
                    out.close();
                }
            }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                Logx.w("http " + code + " " + host(url));
                return null;
            }
            return read(conn.getInputStream());
        } catch (Throwable t) {
            Logx.w("http error " + host(url) + " :: " + t.getClass().getSimpleName());
            return null;
        } finally {
            close(conn);
        }
    }

    public static String enc(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    public static String host(String url) {
        try {
            return new URL(url).getHost();
        } catch (Throwable t) {
            return "remote";
        }
    }

    private static HttpURLConnection open(String url, String method, int timeoutMs) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setUseCaches(false);
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("Accept", "application/json, text/plain, */*");
        conn.setRequestProperty("Accept-Encoding", "identity");
        return conn;
    }

    private static String read(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder(256);
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        try {
            char[] buf = new char[2048];
            int n;
            while ((n = reader.read(buf)) > 0) {
                sb.append(buf, 0, n);
                if (sb.length() > 256 * 1024) {
                    break;
                }
            }
        } finally {
            reader.close();
        }
        return sb.toString();
    }

    private static void close(HttpURLConnection conn) {
        if (conn != null) {
            try {
                conn.disconnect();
            } catch (Throwable ignored) {
            }
        }
    }
}
