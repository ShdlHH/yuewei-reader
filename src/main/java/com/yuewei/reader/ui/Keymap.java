package com.yuewei.reader.ui;

import com.yuewei.reader.application.SettingsService;
import javafx.scene.input.KeyEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 阅读快捷键：解析、序列化、冲突检测与按键匹配。
 *
 * <p>存储格式为 {@code [CTRL+][ALT+][SHIFT+]KEY}，按键名取 KeyCode 名称（如 SPACE、PAGE_DOWN）。</p>
 */
public final class Keymap {

    public static final String NEXT_PAGE = "shortcut.nextPage";
    public static final String PREV_PAGE = "shortcut.prevPage";
    public static final String NEXT_CHAPTER = "shortcut.nextChapter";
    public static final String PREV_CHAPTER = "shortcut.prevChapter";
    public static final String TOC = "shortcut.toc";
    public static final String READER_PANEL = "shortcut.readerPanel";
    public static final String IMMERSIVE = "shortcut.immersive";

    /** 一个可配置动作：设置键、展示名、默认组合。 */
    public record ActionDef(String key, String label, String defaultCombo) {
    }

    public static final List<ActionDef> ACTIONS = List.of(
            new ActionDef(NEXT_PAGE, "下一页", "SPACE"),
            new ActionDef(PREV_PAGE, "上一页", "BACK_SPACE"),
            new ActionDef(NEXT_CHAPTER, "下一章", "CTRL+PAGE_DOWN"),
            new ActionDef(PREV_CHAPTER, "上一章", "CTRL+PAGE_UP"),
            new ActionDef(TOC, "打开 / 关闭目录", "CTRL+T"),
            new ActionDef(READER_PANEL, "阅读设置", "CTRL+P"),
            new ActionDef(IMMERSIVE, "沉浸阅读", "CTRL+SHIFT+F"));

    private Keymap() {
    }

    public static String defaultCombo(String key) {
        for (ActionDef def : ACTIONS) {
            if (def.key().equals(key)) {
                return def.defaultCombo();
            }
        }
        return "";
    }

    public static String labelOf(String key) {
        for (ActionDef def : ACTIONS) {
            if (def.key().equals(key)) {
                return def.label();
            }
        }
        return key;
    }

    /** 读取当前生效的快捷键（缺失取默认值）。 */
    public static Map<String, String> load(SettingsService settings) {
        Map<String, String> map = new LinkedHashMap<>();
        for (ActionDef def : ACTIONS) {
            String value = settings.get(def.key(), def.defaultCombo());
            String normalized = normalize(value);
            map.put(def.key(), normalized.isEmpty() ? def.defaultCombo() : normalized);
        }
        return map;
    }

    /** 统一格式：大写、去掉空格、固定 CTRL → ALT → SHIFT → 主键顺序。 */
    public static String normalize(String combo) {
        if (combo == null) {
            return "";
        }
        String text = combo.trim().toUpperCase(Locale.ROOT).replace(" ", "");
        if (text.isEmpty()) {
            return "";
        }
        boolean ctrl = text.contains("CTRL");
        boolean alt = text.contains("ALT");
        boolean shift = text.contains("SHIFT");
        String[] parts = text.split("\\+");
        String main = "";
        for (String part : parts) {
            String token = part.trim();
            if (token.isEmpty() || "CTRL".equals(token) || "ALT".equals(token) || "SHIFT".equals(token)) {
                continue;
            }
            main = token;
        }
        if (main.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        if (ctrl) {
            builder.append("CTRL+");
        }
        if (alt) {
            builder.append("ALT+");
        }
        if (shift) {
            builder.append("SHIFT+");
        }
        return builder.append(main).toString();
    }

    /** 把一次按键转换为组合字符串；只按修饰键时返回空串。 */
    public static String comboOf(KeyEvent event) {
        if (event == null || event.getCode() == null) {
            return "";
        }
        switch (event.getCode()) {
            case CONTROL, ALT, SHIFT, META, UNDEFINED -> {
                return "";
            }
            default -> {
            }
        }
        StringBuilder builder = new StringBuilder();
        if (event.isControlDown()) {
            builder.append("CTRL+");
        }
        if (event.isAltDown()) {
            builder.append("ALT+");
        }
        if (event.isShiftDown()) {
            builder.append("SHIFT+");
        }
        return builder.append(event.getCode().name()).toString();
    }

    /** 返回与 combo 冲突的动作名；没有冲突返回 null。 */
    public static String conflict(Map<String, String> map, String selfKey, String combo) {
        String normalized = normalize(combo);
        for (Map.Entry<String, String> entry : map.entrySet()) {
            if (entry.getKey().equals(selfKey)) {
                continue;
            }
            if (normalize(entry.getValue()).equals(normalized)) {
                return labelOf(entry.getKey());
            }
        }
        return null;
    }

    /** 把一次按键解析为动作设置键；未配置则返回 null。 */
    public static String actionOf(Map<String, String> map, KeyEvent event) {
        String combo = comboOf(event);
        if (combo.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, String> entry : map.entrySet()) {
            if (normalize(entry.getValue()).equals(combo)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** 供设置页展示：把组合拆成易读形式。 */
    public static String describe(String combo) {
        String normalized = normalize(combo);
        if (normalized.isEmpty()) {
            return "未设置";
        }
        List<String> parts = new ArrayList<>(List.of(normalized.split("\\+")));
        return String.join(" + ", parts);
    }
}
