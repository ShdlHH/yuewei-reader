package com.yuewei.reader.application;

import com.yuewei.reader.infrastructure.network.HttpGateway;

import java.util.Map;

/** 阅读、网络等全局设置，持久化在 setting 表。 */
public class SettingsService {

    public static final String DEFAULT_FONT_FAMILY = "京华老宋体";

    public static final String FONT_SIZE = "reader.fontSize";
    public static final String LINE_SPACING = "reader.lineSpacing";
    public static final String FONT_FAMILY = "reader.fontFamily";
    public static final String THEME = "reader.theme";
    public static final String TOC_WIDTH = "reader.tocWidth";

    public static final String PUNCT_ENABLED = "reader.punct.enabled";
    public static final String PUNCT_DIALOGUE = "reader.punct.dialogue";
    public static final String PUNCT_STOP = "reader.punct.stop";
    public static final String PUNCT_ASK = "reader.punct.ask";
    public static final String PUNCT_EXCL = "reader.punct.excl";
    public static final String PUNCT_ELLIPSIS = "reader.punct.ellipsis";
    public static final String PUNCT_PAREN = "reader.punct.paren";

    public static final String PUNCT_DIALOGUE_COLOR = "reader.punct.dialogue.color";
    public static final String PUNCT_STOP_COLOR = "reader.punct.stop.color";
    public static final String PUNCT_ASK_COLOR = "reader.punct.ask.color";
    public static final String PUNCT_EXCL_COLOR = "reader.punct.excl.color";
    public static final String PUNCT_ELLIPSIS_COLOR = "reader.punct.ellipsis.color";
    public static final String PUNCT_PAREN_COLOR = "reader.punct.paren.color";

    public static final String DEFAULT_PUNCT_DIALOGUE_COLOR = "#a63d32";
    public static final String DEFAULT_PUNCT_STOP_COLOR = "#2f6fa8";
    public static final String DEFAULT_PUNCT_ASK_COLOR = "#17786b";
    public static final String DEFAULT_PUNCT_EXCL_COLOR = "#c2632a";
    public static final String DEFAULT_PUNCT_ELLIPSIS_COLOR = "#8a8680";
    public static final String DEFAULT_PUNCT_PAREN_COLOR = "#6b7f8c";
    public static final String TIMEOUT = "network.timeout";
    public static final String SEARCH_CONCURRENCY = "network.searchConcurrency";
    public static final String DOWNLOAD_CONCURRENCY = "network.downloadConcurrency";
    public static final String REQUEST_INTERVAL = "network.requestIntervalMs";
    public static final String USER_AGENT = "network.userAgent";
    public static final String CACHE_LIMIT_MB = "storage.cacheLimitMb";

    public static final String SYNC_ENABLED = "sync.webdav.enabled";
    public static final String SYNC_URL = "sync.webdav.url";
    public static final String SYNC_ACCOUNT = "sync.webdav.account";
    public static final String SYNC_PASSWORD = "sync.webdav.password";
    public static final String SYNC_DIR = "sync.webdav.dir";
    public static final String SYNC_LAST_AT = "sync.lastAt";
    public static final String SYNC_LAST_RESULT = "sync.lastResult";
    public static final String SETTINGS_LOCAL_AT = "sync.settings.localAt";
    public static final String SETTINGS_REMOTE_AT = "sync.settings.remoteAt";

    private final com.yuewei.reader.infrastructure.db.SettingDao dao;
    private final Map<String, String> cache;

    public SettingsService(com.yuewei.reader.infrastructure.db.SettingDao dao) {
        this.dao = dao;
        this.cache = dao.all();
    }

    public String get(String key, String fallback) {
        String v = cache.get(key);
        return v == null || v.isBlank() ? fallback : v;
    }

    public int getInt(String key, int fallback) {
        try {
            return Integer.parseInt(get(key, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public double getDouble(String key, double fallback) {
        try {
            return Double.parseDouble(get(key, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public void put(String key, String value) {
        cache.put(key, value == null ? "" : value);
        dao.put(key, value);
        if (key.startsWith("reader.")) {
            String now = java.time.LocalDateTime.now().toString();
            cache.put(SETTINGS_LOCAL_AT, now);
            dao.put(SETTINGS_LOCAL_AT, now);
        }
    }

    public int fontSize() {
        return Math.max(12, Math.min(32, getInt(FONT_SIZE, 18)));
    }

    public double lineSpacing() {
        return Math.max(1.2, Math.min(3.0, getDouble(LINE_SPACING, 1.8)));
    }

    public String fontFamily() {
        return get(FONT_FAMILY, DEFAULT_FONT_FAMILY);
    }

    public boolean punctEnabled() {
        return Boolean.parseBoolean(get(PUNCT_ENABLED, "true"));
    }

    public boolean punctDialogue() {
        return Boolean.parseBoolean(get(PUNCT_DIALOGUE, "true"));
    }

    public boolean punctStop() {
        return Boolean.parseBoolean(get(PUNCT_STOP, "true"));
    }

    public boolean punctAsk() {
        return Boolean.parseBoolean(get(PUNCT_ASK, "true"));
    }

    public boolean punctExcl() {
        return Boolean.parseBoolean(get(PUNCT_EXCL, "true"));
    }

    public boolean punctEllipsis() {
        return Boolean.parseBoolean(get(PUNCT_ELLIPSIS, "false"));
    }

    public boolean punctParen() {
        return Boolean.parseBoolean(get(PUNCT_PAREN, "false"));
    }

    public String punctColor(String key, String fallback) {
        return get(key, fallback);
    }

    public void savePunct(boolean enabled, boolean dialogue, boolean stop, boolean ask,
                          boolean excl, boolean ellipsis, boolean paren) {
        put(PUNCT_ENABLED, String.valueOf(enabled));
        put(PUNCT_DIALOGUE, String.valueOf(dialogue));
        put(PUNCT_STOP, String.valueOf(stop));
        put(PUNCT_ASK, String.valueOf(ask));
        put(PUNCT_EXCL, String.valueOf(excl));
        put(PUNCT_ELLIPSIS, String.valueOf(ellipsis));
        put(PUNCT_PAREN, String.valueOf(paren));
    }

    public void savePunctColors(String dialogue, String stop, String ask,
                                String excl, String ellipsis, String paren) {
        put(PUNCT_DIALOGUE_COLOR, dialogue);
        put(PUNCT_STOP_COLOR, stop);
        put(PUNCT_ASK_COLOR, ask);
        put(PUNCT_EXCL_COLOR, excl);
        put(PUNCT_ELLIPSIS_COLOR, ellipsis);
        put(PUNCT_PAREN_COLOR, paren);
    }

    public String theme() {
        return get(THEME, "paper");
    }

    public int timeout() {
        return getInt(TIMEOUT, 20);
    }

    public int searchConcurrency() {
        return Math.max(1, Math.min(10, getInt(SEARCH_CONCURRENCY, 5)));
    }

    public int downloadConcurrency() {
        return Math.max(1, Math.min(5, getInt(DOWNLOAD_CONCURRENCY, 2)));
    }

    public int requestInterval() {
        return Math.max(0, Math.min(5000, getInt(REQUEST_INTERVAL, 300)));
    }

    public String userAgent() {
        return get(USER_AGENT,
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36");
    }

    public int cacheLimitMb() {
        return Math.max(100, getInt(CACHE_LIMIT_MB, 1024));
    }

    public void saveStorage(int cacheLimitMb) {
        put(CACHE_LIMIT_MB, String.valueOf(Math.max(100, cacheLimitMb)));
    }

    public void saveReader(int fontSize, double lineSpacing, String family, String theme) {
        put(FONT_SIZE, String.valueOf(fontSize));
        put(LINE_SPACING, String.valueOf(lineSpacing));
        put(FONT_FAMILY, family);
        put(THEME, theme);
    }

    public void saveNetwork(int timeout, int searchConcurrency, int downloadConcurrency,
                            int intervalMs, String userAgent) {
        put(TIMEOUT, String.valueOf(timeout));
        put(SEARCH_CONCURRENCY, String.valueOf(searchConcurrency));
        put(DOWNLOAD_CONCURRENCY, String.valueOf(downloadConcurrency));
        put(REQUEST_INTERVAL, String.valueOf(intervalMs));
        put(USER_AGENT, userAgent);
    }

    public boolean syncEnabled() {
        return Boolean.parseBoolean(get(SYNC_ENABLED, "false"));
    }

    public String syncUrl() {
        return get(SYNC_URL, "");
    }

    public String syncAccount() {
        return get(SYNC_ACCOUNT, "");
    }

    public String syncPassword() {
        return get(SYNC_PASSWORD, "");
    }

    public String syncDir() {
        return get(SYNC_DIR, "yuewei");
    }

    /** WebDAV 地址、账号与同步子目录；密码只保存在本地数据库。 */
    public void saveSync(boolean enabled, String url, String account, String password, String dir) {
        put(SYNC_ENABLED, String.valueOf(enabled));
        put(SYNC_URL, url == null ? "" : url.trim());
        put(SYNC_ACCOUNT, account == null ? "" : account.trim());
        put(SYNC_PASSWORD, password == null ? "" : password);
        put(SYNC_DIR, dir == null || dir.isBlank() ? "yuewei" : dir.trim());
    }

    public String lastSyncText() {
        String at = get(SYNC_LAST_AT, "");
        String result = get(SYNC_LAST_RESULT, "");
        if (at.isBlank()) {
            return result.isBlank() ? "尚未同步" : result;
        }
        return "上次同步 " + at.replace('T', ' ').substring(0, Math.min(16, at.length())) + " · " + result;
    }

    public void saveSyncResult(String message) {
        put(SYNC_LAST_RESULT, message == null ? "" : message);
        put(SYNC_LAST_AT, java.time.LocalDateTime.now().toString());
    }

    /** 本地设置最近一次修改时间（毫秒），用于与远端比较新旧。 */
    public long settingsUpdatedAt() {
        return parseTime(get(SETTINGS_LOCAL_AT, ""));
    }

    /** 最近一次应用远端设置的时间，避免同一份远端快照反复覆盖本地。 */
    public long settingsAppliedAt() {
        return parseTime(get(SETTINGS_REMOTE_AT, ""));
    }

    public boolean acceptRemoteSettings(long remoteMillis) {
        return remoteMillis > Math.max(settingsUpdatedAt(), settingsAppliedAt());
    }

    /**
     * 应用远端设置快照：只写值，不刷新本地修改时间，避免“拉取后又被推回去”的回环。
     * 非法或未知的键会被忽略。
     */
    public void applyExternalSettings(Map<String, String> values, long remoteMillis) {
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = entry.getKey();
            if (key == null || !key.startsWith("reader.")) {
                continue;
            }
            String clean = sanitize(key, entry.getValue());
            if (clean != null) {
                cache.put(key, clean);
                dao.put(key, clean);
            }
        }
        String marker = java.time.LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(remoteMillis), java.time.ZoneId.systemDefault()).toString();
        cache.put(SETTINGS_REMOTE_AT, marker);
        dao.put(SETTINGS_REMOTE_AT, marker);
    }

    private String sanitize(String key, String value) {
        String text = value == null ? "" : value.trim();
        try {
            switch (key) {
                case FONT_SIZE:
                    return String.valueOf(Math.max(12, Math.min(32, Integer.parseInt(text))));
                case LINE_SPACING:
                    return String.valueOf(Math.max(1.2, Math.min(3.0, Double.parseDouble(text))));
                case TOC_WIDTH:
                    return String.valueOf(Math.max(180, Math.min(460, Integer.parseInt(text))));
                case THEME:
                    return switch (text) {
                        case "night", "green", "paper" -> text;
                        default -> null;
                    };
                case FONT_FAMILY:
                    return text.isEmpty() || text.length() > 64 ? null : text;
                case PUNCT_ENABLED, PUNCT_DIALOGUE, PUNCT_STOP, PUNCT_ASK,
                     PUNCT_EXCL, PUNCT_ELLIPSIS, PUNCT_PAREN:
                    return switch (text.toLowerCase(java.util.Locale.ROOT)) {
                        case "true" -> "true";
                        case "false" -> "false";
                        default -> null;
                    };
                case PUNCT_DIALOGUE_COLOR, PUNCT_STOP_COLOR, PUNCT_ASK_COLOR,
                     PUNCT_EXCL_COLOR, PUNCT_ELLIPSIS_COLOR, PUNCT_PAREN_COLOR:
                    return text.matches("#[0-9a-fA-F]{6}") ? text : null;
                default:
                    return null;
            }
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long parseTime(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return java.time.LocalDateTime.parse(value)
                    .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    public void applyTo(HttpGateway http) {
        http.configure(userAgent(), timeout(), 2);
    }
}
