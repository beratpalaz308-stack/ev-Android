package com.ev.assistant;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

final class Gh {
    private Gh() { }

    static String[][] headers(String token) {
        return new String[][]{
                {"Authorization", "Bearer " + token},
                {"Accept", "application/vnd.github+json"},
                {"X-GitHub-Api-Version", "2022-11-28"},
                {"User-Agent", "EV-Android"}
        };
    }

    static String http(String method, String url, String[][] headers, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(15000);
        c.setReadTimeout(120000);
        if (headers != null) {
            for (String[] h : headers) c.setRequestProperty(h[0], h[1]);
        }
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            OutputStream os = c.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.close();
        }
        int code = c.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
        String resp = readAll(is);
        if (code < 200 || code >= 300) {
            throw new Exception("HTTP " + code + " " + (resp.length() > 200 ? resp.substring(0, 200) : resp));
        }
        return resp;
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line).append('\n');
        br.close();
        return sb.toString();
    }
}
