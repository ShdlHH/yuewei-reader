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
        prefs.edit()
                .putBoolean("sync.enabled", enabled)
                .putString("sync.url", url == null ? "" : url.trim())
                .putString("sync.account", account == null ? "" : account.trim())
                .putString("sync.password", password == null ? "" : password)
                .putString("sync.dir", dir == null || dir.isBlank() ? "yuewei" : dir.trim())
                .putLong("settings.localAt", System.currentTimeMillis())
                .apply();
    }

    public int fontSize() {
        return Math.max(12, Math.min(32, prefs.getInt("reader.fontSize", 18)));
    }

    public float lineSpacing() {
        return Math.max(1.2f, Math.min(3.0f, prefs.getFloat("reader.lineSpacing", 1.8f)));
    }

    public String theme() {
        return prefs.getString("reader.theme", "paper");
    }

    public void saveReader(int fontSize, float lineSpacing, String theme) {
        prefs.edit()
                .putInt("reader.fontSize", Math.max(12, Math.min(32, fontSize)))
                .putFloat("reader.lineSpacing", Math.max(1.2f, Math.min(3.0f, lineSpacing)))
                .putString("reader.theme", theme == null ? "paper" : theme)
                .putLong("settings.localAt", System.currentTimeMillis())
                .apply();
    }

    public long settingsLocalAt() {
        return prefs.getLong("settings.localAt", 0);
    }

    public long settingsRemoteAt() {
        return prefs.getLong("settings.remoteAt", 0);
    }

    /** 应用远端设置：合法值才写入，并记录远端快照时间防止反复覆盖。 */
    public void applyRemoteSettings(int fontSize, float lineSpacing, String theme, long remoteAt) {
        SharedPreferences.Editor editor = prefs.edit();
        if (fontSize >= 12 && fontSize <= 32) {
            editor.putInt("reader.fontSize", fontSize);
        }
        if (lineSpacing >= 1.2f && lineSpacing <= 3.0f) {
            editor.putFloat("reader.lineSpacing", lineSpacing);
        }
        if ("paper".equals(theme) || "night".equals(theme) || "green".equals(theme)) {
            editor.putString("reader.theme", theme);
        }
        editor.putLong("settings.remoteAt", remoteAt).apply();
    }
}
