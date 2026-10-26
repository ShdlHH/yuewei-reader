package com.yuewei.reader.infrastructure.network;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 统一 HTTP 入口：UA、超时、重试、URL 规整。
 * 只做 GET，POST/登录态在 P0 范围外。
 */
public class HttpGateway {

    public static class Response {
        public int status;
        public byte[] body;
        public String finalUrl;
        public String contentType;
        public long millis;
    }

    /** 4xx/5xx 不重试。 */
    public static class HttpStatusException extends IOException {
        public final int status;

        public HttpStatusException(int status, String url) {
            super("HTTP " + status + " · " + url);
            this.status = status;
        }
    }

    private final HttpClient client;
    private volatile String userAgent =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";
    private volatile int timeoutSeconds = 20;
    private volatile int retries = 2;

    public HttpGateway() {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public void configure(String ua, int timeoutSeconds, int retries) {
        if (ua != null && !ua.isBlank()) {
            this.userAgent = ua;
        }
        this.timeoutSeconds = Math.max(3, timeoutSeconds);
        this.retries = Math.max(0, retries);
    }

    public Response get(String rawUrl) throws IOException {
        String url = normalizeUrl(rawUrl);
        IOException last = null;
        for (int attempt = 0; attempt <= retries; attempt++) {
            if (attempt > 0) {
                sleep(attempt == 1 ? 1000 : 3000);
            }
            try {
                return execute(url);
            } catch (HttpStatusException e) {
                throw e;
            } catch (IOException e) {
                last = e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("请求被中断", e);
            }
        }
        throw last == null ? new IOException("请求失败") : last;
    }

    private Response execute(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("User-Agent", userAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .GET()
                .build();
        long start = System.currentTimeMillis();
        HttpResponse<byte[]> resp = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        Response r = new Response();
        r.status = resp.statusCode();
        r.body = resp.body();
        r.finalUrl = resp.uri().toString();
        r.contentType = resp.headers().firstValue("content-type").orElse("");
        r.millis = System.currentTimeMillis() - start;
        if (r.status >= 400) {
            throw new HttpStatusException(r.status, url);
        }
        return r;
    }

    /** 去掉 Legado URL 后的 ,{...} 选项，并对空格做最小转义。 */
    public static String normalizeUrl(String raw) {
        String url = raw == null ? "" : raw.trim();
        int options = url.indexOf(",{");
        if (options > 0) {
            url = url.substring(0, options);
        }
        return url.replace(" ", "%20");
    }

    public static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    /** 相对链接转为绝对链接；已是绝对链接时原样返回。 */
    public static String absolutize(String baseUrl, String link) {
        if (link == null || link.isBlank()) {
            return link;
        }
        String value = link.trim();
        if (value.startsWith("javascript:") || value.startsWith("#")) {
            return null;
        }
        if (value.startsWith("//")) {
            String scheme = "http";
            if (baseUrl != null && baseUrl.startsWith("https")) {
                scheme = "https";
            }
            return scheme + ":" + value;
        }
        if (value.matches("(?i)^(https?|ftp|file):.*")) {
            return value.replace(" ", "%20");
        }
        try {
            URI base = URI.create(normalizeUrl(baseUrl));
            return base.resolve(value.replace(" ", "%20")).toString();
        } catch (Exception e) {
            return value;
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
