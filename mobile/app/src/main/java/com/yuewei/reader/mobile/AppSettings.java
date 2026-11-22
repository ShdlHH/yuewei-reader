package com.yuewei.reader.mobile;

import android.content.Context;
import android.content.SharedPreferences;

/** 移动端设置：阅读样式与 WebDAV 同步参数。 */
public class AppSettings {

    private static final String FILE = "yuewei-settings";
    /** 视觉升级：旧默认 18sp/1.6 一次性迁移到 16sp/2.1，用户自调过的值不动。 */
    private static final String MIGRATED_VISUAL = "reader.visual.migrated";

    private final SharedPreferences prefs;

    public AppSettings(Context context) {
        prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        migrateVisualDefaults();
    }

    private void migrateVisualDefaults() {
        if (prefs.getBoolean(MIGRATED_VISUAL, false)) {
            return;
        }
        int size = prefs.getInt("reader.fontSize", 18);
        float spacing = prefs.getFloat("reader.lineSpacing", 1.6f);
        SharedPreferences.Editor editor = prefs.edit().putBoolean(MIGRATED_VISUAL, true);
        if (size == 18 && Math.abs(spacing - 1.6f) < 0.01f) {
            editor.putInt("reader.fontSize", 16).putFloat("reader.lineSpacing", 2.1f);
        }
        editor.apply();
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
        return Math.max(12, Math.min(32, prefs.getInt("reader.fontSize", 16)));
    }

    public float lineSpacing() {
        return Math.max(1.2f, Math.min(3.0f, prefs.getFloat("reader.lineSpacing", 2.1f)));
    }

    public String fontKey() {
        return prefs.getString("reader.fontKey", MobileFonts.KEY_SERIF);
    }

    public String theme() {
        return prefs.getString("reader.theme", "paper");
    }

    public void saveReader(int fontSize, float lineSpacing, String theme) {
        saveReader(fontSize, lineSpacing, theme, fontKey());
    }

    public void saveReader(int fontSize, float lineSpacing, String theme, String fontKey) {
        prefs.edit()
                .putInt("reader.fontSize", Math.max(12, Math.min(32, fontSize)))
                .putFloat("reader.lineSpacing", Math.max(1.2f, Math.min(3.0f, lineSpacing)))
                .putString("reader.theme", theme == null ? "paper" : theme)
                .putString("reader.fontKey", fontKey == null ? MobileFonts.KEY_SERIF : fontKey)
                .apply();
    }

    /** 标点着色总开关，默认开启。 */
    public boolean punctEnabled() {
        return prefs.getBoolean("reader.punct.enabled", true);
    }

    public boolean punctDialogue() {
        return prefs.getBoolean("reader.punct.dialogue", true);
    }

    public boolean punctStop() {
        return prefs.getBoolean("reader.punct.stop", true);
    }

    public boolean punctAsk() {
        return prefs.getBoolean("reader.punct.ask", true);
    }

    public boolean punctExcl() {
        return prefs.getBoolean("reader.punct.excl", true);
    }

    public boolean punctEllipsis() {
        return prefs.getBoolean("reader.punct.ellipsis", false);
    }

    public boolean punctParen() {
        return prefs.getBoolean("reader.punct.paren", false);
    }

    public void savePunctuation(boolean enabled, boolean dialogue, boolean stop, boolean ask,
                                boolean excl, boolean ellipsis, boolean paren) {
        prefs.edit()
                .putBoolean("reader.punct.enabled", enabled)
                .putBoolean("reader.punct.dialogue", dialogue)
                .putBoolean("reader.punct.stop", stop)
                .putBoolean("reader.punct.ask", ask)
                .putBoolean("reader.punct.excl", excl)
                .putBoolean("reader.punct.ellipsis", ellipsis)
                .putBoolean("reader.punct.paren", paren)
                .apply();
    }

    /** 在线书正文缓存上限，默认 200MB。 */
    public int cacheLimitMb() {
        return Math.max(50, Math.min(2048, prefs.getInt("storage.cacheLimitMb", 200)));
    }

    public void saveCacheLimit(int mb) {
        prefs.edit().putInt("storage.cacheLimitMb", Math.max(50, Math.min(2048, mb))).apply();
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

    /** 同步进行中标记；进程被杀时来不及清除，下次启动据此补跑。 */
    public boolean syncPending() {
        return prefs.getBoolean("sync.pending", false);
    }

    public void saveSyncPending(boolean pending) {
        prefs.edit().putBoolean("sync.pending", pending).apply();
    }
}
