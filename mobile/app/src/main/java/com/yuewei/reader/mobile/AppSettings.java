package com.yuewei.reader.mobile;

import android.content.Context;
import android.content.SharedPreferences;

/** 移动端设置：阅读样式与 WebDAV 同步参数。 */
public class AppSettings {

    private static final String FILE = "yuewei-settings";

    private final SharedPreferences prefs;

    public AppSettings(Context context) {
        prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public boolean syncEnabled() {
        return prefs.getBoolean("sync.enabled", false);
    }

    public String syncUrl() {
        return prefs.getString("sync.url", "");
    }

    public String syncAccount() {
        return prefs.getString("sync.account", "");
    }

    public String syncPassword() {
        return prefs.getString("sync.password", "");
    }

    public String syncDir() {
        return prefs.getString("sync.dir", "yuewei");
    }

    public void saveSync(boolean enabled, String url, String account, String password, String dir) {
        // 同步参数只影响连接信息，和阅读排版完全独立
        prefs.edit()
                .putBoolean("sync.enabled", enabled)
                .putString("sync.url", url == null ? "" : url.trim())
                .putString("sync.account", account == null ? "" : account.trim())
                .putString("sync.password", password == null ? "" : password)
                .putString("sync.dir", dir == null || dir.isBlank() ? "yuewei" : dir.trim())
                .apply();
    }

    public int fontSize() {
        return Math.max(12, Math.min(32, prefs.getInt("reader.fontSize", 18)));
    }

    public float lineSpacing() {
        return Math.max(1.2f, Math.min(3.0f, prefs.getFloat("reader.lineSpacing", 1.6f)));
    }

    public String theme() {
        return prefs.getString("reader.theme", "paper");
    }

    public void saveReader(int fontSize, float lineSpacing, String theme) {
        prefs.edit()
                .putInt("reader.fontSize", Math.max(12, Math.min(32, fontSize)))
                .putFloat("reader.lineSpacing", Math.max(1.2f, Math.min(3.0f, lineSpacing)))
                .putString("reader.theme", theme == null ? "paper" : theme)
                .apply();
    }

    /** 自动同步开关，默认开启；关闭后只能手动点同步。 */
    public boolean autoSync() {
        return prefs.getBoolean("sync.auto", true);
    }

    public void saveAutoSync(boolean enabled) {
        prefs.edit().putBoolean("sync.auto", enabled).apply();
    }

    public long lastSyncAt() {
        return prefs.getLong("sync.lastAt", 0);
    }

    public String lastSyncMessage() {
        return prefs.getString("sync.lastResult", "");
    }

    public long lastAttemptAt() {
        return prefs.getLong("sync.lastAttemptAt", 0);
    }

    public void saveSyncAttempt(long at) {
        prefs.edit().putLong("sync.lastAttemptAt", at).apply();
    }

    /** 记录一次成功同步，用于界面显示“上次同步 …”。 */
    public void saveSyncDone(String message) {
        prefs.edit()
                .putLong("sync.lastAt", System.currentTimeMillis())
                .putString("sync.lastResult", message == null ? "" : message)
                .apply();
    }
}
