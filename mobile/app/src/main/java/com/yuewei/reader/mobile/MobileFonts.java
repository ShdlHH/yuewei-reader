package com.yuewei.reader.mobile;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 正文字体：Android 内置字体族 + 导入到 filesDir/fonts 的 ttf/otf。 */
final class MobileFonts {

    static final String KEY_SERIF = "builtin:serif";
    static final String KEY_SANS = "builtin:sans-serif";
    static final String KEY_MONO = "builtin:monospace";

    private static final String FILE_PREFIX = "file:";
    private static final Map<String, Typeface> CACHE = new HashMap<>();

    /** 字体列表项：key 用于持久化，name 用于界面显示。 */
    static final class FontChoice {
        final String key;
        final String name;

        FontChoice(String key, String name) {
            this.key = key;
            this.name = name;
        }
    }

    private MobileFonts() {
    }

    static File dir(Context context) {
        File dir = new File(context.getFilesDir(), "fonts");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    /** 解析字体；未知或加载失败时回退宋体。 */
    static Typeface resolve(Context context, String key) {
        String safeKey = key == null || key.isEmpty() ? KEY_SERIF : key;
        Typeface cached = CACHE.get(safeKey);
        if (cached != null) {
            return cached;
        }
        Typeface typeface = null;
        if (safeKey.startsWith("builtin:")) {
            typeface = Typeface.create(safeKey.substring("builtin:".length()), Typeface.NORMAL);
        } else if (safeKey.startsWith(FILE_PREFIX)) {
            File file = new File(dir(context), safeKey.substring(FILE_PREFIX.length()));
            if (file.isFile()) {
                try {
                    typeface = Typeface.createFromFile(file);
                } catch (RuntimeException ignored) {
                    // 文件损坏或格式不支持时回退
                }
            }
        }
        if (typeface == null) {
            typeface = Typeface.create("serif", Typeface.NORMAL);
        }
        CACHE.put(safeKey, typeface);
        return typeface;
    }

    /** 内置字体 + 已导入字体。 */
    static List<FontChoice> choices(Context context) {
        List<FontChoice> result = new ArrayList<>();
        result.add(new FontChoice(KEY_SERIF, "宋体（衬线）"));
        result.add(new FontChoice(KEY_SANS, "黑体（无衬线）"));
        result.add(new FontChoice(KEY_MONO, "等宽"));
        File[] files = dir(context).listFiles();
        if (files != null) {
            List<String> names = new ArrayList<>();
            for (File file : files) {
                if (isFontFile(file.getName())) {
                    names.add(file.getName());
                }
            }
            names.sort(String::compareToIgnoreCase);
            for (String name : names) {
                result.add(new FontChoice(FILE_PREFIX + name, displayName(name)));
            }
        }
        return result;
    }

    static String displayName(Context context, String key) {
        for (FontChoice choice : choices(context)) {
            if (choice.key.equals(key)) {
                return choice.name;
            }
        }
        return "宋体（衬线）";
    }

    /** 导入字体文件到 filesDir/fonts；无法识别时删除并抛出异常。 */
    static String importFont(Context context, Uri uri) throws IOException {
        String name = safeName(displayName(context, uri));
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".ttf") && !lower.endsWith(".otf")) {
            throw new IOException("只支持 ttf / otf 字体文件");
        }
        File target = uniqueFile(dir(context), name);
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                throw new IOException("无法读取字体文件");
            }
            try (FileOutputStream out = new FileOutputStream(target)) {
                byte[] buffer = new byte[16384];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, read);
                }
            }
        }
        try {
            Typeface typeface = Typeface.createFromFile(target);
            if (typeface == null) {
                throw new IOException("无法识别这个字体文件");
            }
        } catch (RuntimeException e) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            throw new IOException("无法识别这个字体文件");
        }
        String key = FILE_PREFIX + target.getName();
        CACHE.remove(key);
        return key;
    }

    private static boolean isFontFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".ttf") || lower.endsWith(".otf");
    }

    private static String displayName(String fileName) {
        int index = fileName.lastIndexOf('.');
        return index > 0 ? fileName.substring(0, index) : fileName;
    }

    private static File uniqueFile(File dir, String name) {
        File target = new File(dir, name);
        if (!target.exists()) {
            return target;
        }
        String base = displayName(name);
        String extension = name.substring(base.length());
        for (int i = 1; i < 1000; i++) {
            File candidate = new File(dir, base + "_" + i + extension);
            if (!candidate.exists()) {
                return candidate;
            }
        }
        return new File(dir, System.currentTimeMillis() + extension);
    }

    private static String safeName(String name) {
        String value = name == null ? "" : name.trim();
        value = value.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_");
        return value.isEmpty() ? "font.ttf" : value;
    }

    private static String displayName(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver()
                .query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    return cursor.getString(index);
                }
            }
        } catch (RuntimeException ignored) {
            // 取不到名字时用默认名
        }
        return "font.ttf";
    }
}
