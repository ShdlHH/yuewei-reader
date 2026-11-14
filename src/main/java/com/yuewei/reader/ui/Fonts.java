package com.yuewei.reader.ui;

import com.yuewei.reader.application.SettingsService;
import javafx.scene.text.Font;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/** 字体：加载数据目录 fonts 下的字体文件，缺字体时给出回退方案。 */
public final class Fonts {

    private static final String[] FALLBACKS = {"宋体", "SimSun", "微软雅黑", "Microsoft YaHei"};

    private static Path directory;

    private Fonts() {
    }

    /** 启动时调用：创建字体目录并加载其中已有的字体文件。 */
    public static void load(Path dataDir) {
        directory = dataDir.resolve("fonts");
        try {
            Files.createDirectories(directory);
            try (var stream = Files.list(directory)) {
                stream.filter(Fonts::isFontFile).forEach(Fonts::loadFile);
            }
        } catch (IOException ignored) {
            // 字体目录不可用时继续使用系统字体
        }
    }

    public static Path directory() {
        return directory;
    }

    public static boolean available(String family) {
        return actualFamily(family) != null;
    }

    /**
     * 按规范化名称匹配实际字体家族。字体内部的家族名常带后缀或繁体写法
     * （如「京華老宋体_KingHwa_OldSong」），统一去空格、去符号并归一繁体字后再比较。
     *
     * @return 字体文件中真实的家族名，未加载时返回 null
     */
    public static String actualFamily(String family) {
        if (family == null || family.isBlank()) {
            return null;
        }
        String key = normalize(family);
        if (key.isEmpty()) {
            return null;
        }
        String prefixMatch = null;
        for (String name : Font.getFamilies()) {
            String candidate = normalize(name);
            if (candidate.isEmpty()) {
                continue;
            }
            if (candidate.equals(key)) {
                return name;
            }
            if (prefixMatch == null && candidate.startsWith(key)) {
                prefixMatch = name;
            }
        }
        return prefixMatch;
    }

    /** 首选字体不可用时回退宋体；返回 null 表示交给 JavaFX 默认字体。 */
    public static String resolve(String family) {
        if (family == null || family.isBlank() || "系统默认".equals(family)) {
            return null;
        }
        String actual = actualFamily(family);
        if (actual != null) {
            return actual;
        }
        for (String fallback : FALLBACKS) {
            String resolved = actualFamily(fallback);
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
    }

    /** 把字体文件复制到数据目录并加载，返回字体家族名；无法识别时返回 null。 */
    public static String install(File source) throws IOException {
        if (directory == null) {
            throw new IOException("字体目录尚未初始化");
        }
        Path target = directory.resolve(source.getName());
        Files.copy(source.toPath(), target, StandardCopyOption.REPLACE_EXISTING);
        return loadFile(target);
    }

    public static boolean isMissingDefault() {
        return !available(SettingsService.DEFAULT_FONT_FAMILY);
    }

    private static boolean isFontFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".ttf") || name.endsWith(".otf") || name.endsWith(".ttc");
    }

    /** 小写、繁体归一、只保留字母和数字，用于宽松比对字体家族名。 */
    private static String normalize(String name) {
        String lower = name.toLowerCase(Locale.ROOT).replace('華', '华').replace('體', '体');
        StringBuilder sb = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String loadFile(Path path) {
        try {
            Font font = Font.loadFont(path.toUri().toString(), 12);
            return font == null ? null : font.getFamily();
        } catch (Exception e) {
            return null;
        }
    }
}
