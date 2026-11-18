package com.yuewei.reader.infrastructure.sync;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 极简 WebDAV 客户端：只覆盖同步需要的 PUT / GET / PROPFIND / MKCOL。
 * 兼容坚果云、Nextcloud 等常见实现，使用 Basic 认证。
 */
public class WebDavClient {

    /** 远端文件：name 为所在目录下的文件名，path 为相对根目录的路径。 */
    public record RemoteFile(String name, String path, long size, long lastModified) {
    }

    private static final String PROPFIND_BODY = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:">
              <d:prop>
                <d:getlastmodified/>
                <d:getcontentlength/>
                <d:resourcetype/>
              </d:prop>
            </d:propfind>
            """;

    private final HttpClient http;
    private final String root;
    private final String authorization;
    private final Duration timeout;

    public WebDavClient(String url, String directory, String account, String password, int timeoutSeconds) {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.timeout = Duration.ofSeconds(Math.max(5, timeoutSeconds));
        String base = url == null ? "" : url.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        base = safeBase(base);
        String dir = directory == null ? "" : directory.trim();
        while (dir.startsWith("/")) {
            dir = dir.substring(1);
        }
        while (dir.endsWith("/")) {
            dir = dir.substring(0, dir.length() - 1);
        }
        this.root = base.isEmpty() ? "" : base + "/" + encodePath(dir);
        this.authorization = account == null || account.isBlank()
                ? null
                : "Basic " + Base64.getEncoder().encodeToString(
                        (account + ":" + (password == null ? "" : password)).getBytes(StandardCharsets.UTF_8));
    }

    public String root() {
        return root;
    }

    /** 创建同步根目录与 bookProgress 子目录；已存在时服务端会返回 405/301，视为成功。 */
    public void ensureDir() throws IOException {
        mkcol("");
        mkcol("bookProgress");
    }

    public void put(String path, byte[] body, String contentType) throws IOException {
        HttpRequest request = builder(path)
                .header("Content-Type", contentType)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        send(request, 200, 201, 204);
    }

    public byte[] get(String path) throws IOException {
        HttpRequest request = builder(path).GET().build();
        HttpResponse<byte[]> response = execute(request);
        if (response.statusCode() == 404) {
            return null;
        }
        if (response.statusCode() / 100 != 2) {
            throw new IOException("无法下载 " + path + "（HTTP " + response.statusCode() + "）");
        }
        return response.body();
    }

    /** 列出目录下的文件；目录不存在返回空列表。 */
    public List<RemoteFile> list(String directory) throws IOException {
        String rel = directory == null ? "" : directory;
        while (rel.endsWith("/")) {
            rel = rel.substring(0, rel.length() - 1);
        }
        String requestPath = rel.isEmpty() ? "" : rel + "/";
        HttpRequest request = builder(requestPath)
                .header("Depth", "1")
                .header("Content-Type", "application/xml; charset=utf-8")
                .method("PROPFIND", HttpRequest.BodyPublishers.ofString(PROPFIND_BODY))
                .build();
        HttpResponse<String> response = executeString(request);
        int status = response.statusCode();
        if (status == 404) {
            return List.of();
        }
        if (status != 207 && status / 100 != 2) {
            throw new IOException("无法读取目录 " + rel + "（HTTP " + status + "）");
        }
        return parse(response.body(), rel);
    }

    private List<RemoteFile> parse(String xml, String directory) {
        List<RemoteFile> files = new ArrayList<>();
        if (xml == null || xml.isBlank()) {
            return files;
        }
        Document doc = Jsoup.parse(xml, "", Parser.xmlParser());
        for (Element element : doc.getAllElements()) {
            String tag = element.tagName();
            if (!tag.equals("d:response") && !tag.endsWith(":response") && !tag.equals("response")) {
                continue;
            }
            String href = childText(element, "href");
            if (href == null || href.isBlank()) {
                continue;
            }
            String decoded = decodeHref(href);
            boolean isDir = decoded.endsWith("/");
            String name = lastSegment(decoded);
            if (name.isEmpty() || isDir) {
                continue;
            }
            long size = parseLong(childText(element, "getcontentlength"));
            long modified = parseTime(childText(element, "getlastmodified"));
            String path = directory.isEmpty() ? name : directory + "/" + name;
            files.add(new RemoteFile(name, path, size, modified));
        }
        return files;
    }

    private String childText(Element parent, String suffix) {
        for (Element child : parent.getAllElements()) {
            String tag = child.tagName();
            if (tag.equals(suffix) || tag.endsWith(":" + suffix)) {
                String text = child.text();
                return text == null ? null : text.trim();
            }
        }
        return null;
    }

    private static String lastSegment(String path) {
        String value = path;
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        int slash = value.lastIndexOf('/');
        return slash >= 0 ? value.substring(slash + 1) : value;
    }

    private static long parseLong(String text) {
        try {
            return text == null ? 0 : Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long parseTime(String text) {
        if (text == null) {
            return 0;
        }
        try {
            return ZonedDateTime.parse(text.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private void mkcol(String path) throws IOException {
        HttpRequest request = builder(path).method("MKCOL", HttpRequest.BodyPublishers.noBody()).build();
        int status = executeString(request).statusCode();
        if (status == 401 || status == 403) {
            throw new IOException("WebDAV 认证失败（HTTP " + status + "），请检查账号与密码");
        }
        if (status / 100 != 2 && status != 405 && status != 301 && status != 409) {
            throw new IOException("无法创建目录 " + path + "（HTTP " + status + "）");
        }
    }

    private HttpRequest.Builder builder(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(uri(path)))
                .timeout(timeout)
                .header("User-Agent", "YueweiReader/WebDAV");
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        return builder;
    }

    private String uri(String path) {
        String rel = path == null ? "" : path.trim();
        while (rel.startsWith("/")) {
            rel = rel.substring(1);
        }
        if (rel.isEmpty()) {
            return root + "/";
        }
        return root + "/" + encodePath(rel);
    }

    private static String encodePath(String path) {
        StringBuilder sb = new StringBuilder(path.length() + 16);
        String[] parts = path.split("/", -1);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(URLEncoder.encode(parts[i], StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return sb.toString();
    }

    /** 地址含中文/空格时 URI.create 会失败，这里只对路径部分做编码。 */
    private static String safeBase(String base) {
        if (base.isEmpty()) {
            return base;
        }
        try {
            URI.create(base);
            return base;
        } catch (IllegalArgumentException ignored) {
            // 继续按路径分段编码
        }
        int scheme = base.indexOf("://");
        int slash = base.indexOf('/', scheme < 0 ? 0 : scheme + 3);
        if (slash < 0) {
            return encodePath(base);
        }
        return base.substring(0, slash) + "/" + encodePath(base.substring(slash + 1));
    }

    /** 服务端可能返回未转义或裸 % 的 href，解码失败时退回原值，避免整次同步异常。 */
    private static String decodeHref(String href) {
        try {
            return URLDecoder.decode(href.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            return href;
        }
    }

    private HttpResponse<byte[]> execute(HttpRequest request) throws IOException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("请求被中断", e);
        }
    }

    private HttpResponse<String> executeString(HttpRequest request) throws IOException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("请求被中断", e);
        }
    }

    private void send(HttpRequest request, int... allowed) throws IOException {
        HttpResponse<byte[]> response = execute(request);
        int status = response.statusCode();
        for (int code : allowed) {
            if (status == code) {
                return;
            }
        }
        if (status == 401 || status == 403) {
            throw new IOException("WebDAV 认证失败（HTTP " + status + "），请检查账号与密码");
        }
        throw new IOException("WebDAV 请求失败（HTTP " + status + "）");
    }
}
