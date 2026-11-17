package com.yuewei.reader.mobile;

import android.util.Base64;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 移动端 WebDAV：只实现同步需要的 MKCOL / PUT / GET / PROPFIND。
 * Android 自带的 HttpURLConnection 不允许自定义方法，这里用 OkHttp 发 PROPFIND。
 */
public class WebDav {

    public static class RemoteFile {
        public final String name;
        public final long lastModified;

        RemoteFile(String name, long lastModified) {
            this.name = name;
            this.lastModified = lastModified;
        }
    }

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final MediaType XML = MediaType.parse("application/xml; charset=utf-8");

    private static final String PROPFIND_BODY = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:getlastmodified/>"
            + "<d:getcontentlength/></d:prop></d:propfind>";

    private static final Pattern RESPONSE = Pattern.compile(
            "<[^>]*response[^>]*>(.*?)</[^>]*response>", Pattern.DOTALL);
    private static final Pattern HREF = Pattern.compile(
            "<[^>]*href[^>]*>(.*?)</[^>]*href>", Pattern.DOTALL);
    private static final Pattern MODIFIED = Pattern.compile(
            "<[^>]*getlastmodified[^>]*>(.*?)</[^>]*getlastmodified>", Pattern.DOTALL);

    private final String root;
    private final String authorization;
    private final OkHttpClient client;

    public WebDav(String url, String directory, String account, String password) {
        String base = url == null ? "" : url.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String dir = directory == null ? "" : directory.trim();
        while (dir.startsWith("/")) {
            dir = dir.substring(1);
        }
        while (dir.endsWith("/")) {
            dir = dir.substring(0, dir.length() - 1);
        }
        root = base + "/" + encodePath(dir);
        authorization = account == null || account.isEmpty() ? null
                : "Basic " + Base64.encodeToString(
                        (account + ":" + (password == null ? "" : password)).getBytes(StandardCharsets.UTF_8),
                        Base64.NO_WRAP);
        client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .build();
    }

    public void ensureDir() throws IOException {
        mkcol("");
        mkcol("bookProgress");
    }

    public void put(String path, String json) throws IOException {
        Request request = builder(path)
                .put(RequestBody.create(JSON, json))
                .build();
        try (Response response = client.newCall(request).execute()) {
            int status = response.code();
            if (status / 100 != 2) {
                throw new IOException("上传失败（HTTP " + status + "）");
            }
        }
    }

    /** 返回文件内容；不存在时返回 null。 */
    public String get(String path) throws IOException {
        Request request = builder(path).get().build();
        try (Response response = client.newCall(request).execute()) {
            int status = response.code();
            if (status == 404) {
                return null;
            }
            if (status / 100 != 2) {
                throw new IOException("下载失败（HTTP " + status + "）");
            }
            return response.body() == null ? "" : response.body().string();
        }
    }

    /** 列目录项；目录不存在返回空列表。 */
    public List<RemoteFile> list(String directory) throws IOException {
        String dir = directory == null ? "" : directory;
        while (dir.endsWith("/")) {
            dir = dir.substring(0, dir.length() - 1);
        }
        Request request = builder(dir.isEmpty() ? "" : dir + "/")
                .method("PROPFIND", RequestBody.create(XML, PROPFIND_BODY))
                .header("Depth", "1")
                .build();
        try (Response response = client.newCall(request).execute()) {
            int status = response.code();
            if (status == 404) {
                return new ArrayList<>();
            }
            if (status == 401 || status == 403) {
                throw new IOException("WebDAV 认证失败，请检查账号与密码（坚果云需用「应用密码」，不是登录密码）");
            }
            if (status != 207 && status / 100 != 2) {
                throw new IOException("读取目录失败（HTTP " + status + "）");
            }
            return parse(response.body() == null ? "" : response.body().string());
        }
    }

    private List<RemoteFile> parse(String xml) {
        List<RemoteFile> files = new ArrayList<>();
        Matcher response = RESPONSE.matcher(xml);
        while (response.find()) {
            String block = response.group(1);
            Matcher href = HREF.matcher(block);
            if (!href.find()) {
                continue;
            }
            String decoded = URLDecoder.decode(href.group(1).trim(), StandardCharsets.UTF_8);
            if (decoded.endsWith("/")) {
                continue;
            }
            String name = decoded.substring(decoded.lastIndexOf('/') + 1);
            if (name.isEmpty()) {
                continue;
            }
            long modified = 0;
            Matcher time = MODIFIED.matcher(block);
            if (time.find()) {
                try {
                    modified = java.time.ZonedDateTime.parse(time.group(1).trim(),
                            java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                            .toInstant().toEpochMilli();
                } catch (RuntimeException ignored) {
                    modified = 0;
                }
            }
            files.add(new RemoteFile(name, modified));
        }
        return files;
    }

    private void mkcol(String path) throws IOException {
        Request request = builder(path).method("MKCOL", null).build();
        try (Response response = client.newCall(request).execute()) {
            int status = response.code();
            if (status == 401 || status == 403) {
                throw new IOException("WebDAV 认证失败，请检查账号与密码（坚果云需用「应用密码」，不是登录密码）");
            }
            if (status / 100 != 2 && status != 405 && status != 301 && status != 409) {
                throw new IOException("创建目录失败（HTTP " + status + "）");
            }
        }
    }

    private Request.Builder builder(String path) {
        String target = path == null || path.isEmpty() ? root + "/" : root + "/" + encodePath(path);
        Request.Builder builder = new Request.Builder()
                .url(target)
                .header("User-Agent", "YueweiReader-Android");
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        return builder;
    }

    private static String encodePath(String path) {
        String[] parts = path.split("/", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append('/');
            }
            try {
                sb.append(URLEncoder.encode(parts[i], "UTF-8").replace("+", "%20"));
            } catch (Exception e) {
                sb.append(parts[i]);
            }
        }
        return sb.toString();
    }
}
