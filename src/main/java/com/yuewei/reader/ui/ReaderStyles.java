package com.yuewei.reader.ui;

import com.yuewei.reader.application.SettingsService;
import com.yuewei.reader.infrastructure.txt.PunctuationHighlighter;
import com.yuewei.reader.infrastructure.txt.PunctuationHighlighter.Role;
import com.yuewei.reader.infrastructure.txt.PunctuationHighlighter.Rules;
import com.yuewei.reader.infrastructure.txt.PunctuationHighlighter.Run;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** 阅读器正文着色：把标点分段结果渲染成 TextFlow 中的彩色 Text 节点。 */
final class ReaderStyles {

    /** 章节标题不参与首行缩进，例如「第一章 雨夜」「第12回　风起」。 */
    private static final Pattern CHAPTER_TITLE =
            Pattern.compile("^第[0-9零一二三四五六七八九十百千万两]+[章回节卷部篇集][\\s　:：、.．]?");

    private ReaderStyles() {
    }

    static void fill(TextFlow flow, String content, Font font, boolean enabled, Rules rules,
                     Map<Role, Color> colors, Color ink) {
        fillRaw(flow, indent(content), font, enabled, rules, colors, ink);
    }

    /** 直接渲染已带缩进的文本，供分页切分后的续段使用，避免重复加首行缩进。 */
    static void fillRaw(TextFlow flow, String text, Font font, boolean enabled, Rules rules,
                        Map<Role, Color> colors, Color ink) {
        flow.getChildren().clear();
        if (text == null || text.isEmpty()) {
            return;
        }
        List<Run> runs = enabled
                ? PunctuationHighlighter.highlight(text, rules)
                : List.of(new Run(text, Role.INK));
        for (Run run : runs) {
            Text node = new Text(run.text());
            node.setFont(font);
            node.setFill(run.role() == Role.INK ? ink : colors.getOrDefault(run.role(), ink));
            flow.getChildren().add(node);
        }
    }

    /** 每个自然段首行缩进两个字，空行保持不变。 */
    static String indent(String content) {
        String[] lines = content.split("\n", -1);
        StringBuilder sb = new StringBuilder(content.length() + lines.length * 2);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            String line = lines[i];
            if (!line.isBlank()) {
                String trimmed = line.stripLeading();
                if (!CHAPTER_TITLE.matcher(trimmed).find()) {
                    sb.append("　　");
                }
                sb.append(trimmed);
            }
        }
        return sb.toString();
    }

    static Rules rules(SettingsService settings) {
        return new Rules(settings.punctDialogue(), settings.punctStop(), settings.punctAsk(),
                settings.punctExcl(), settings.punctEllipsis(), settings.punctParen());
    }

    static Map<Role, Color> colors(SettingsService settings, String themeKey) {
        Map<Role, Color> colors = new EnumMap<>(Role.class);
        colors.put(Role.DIALOGUE, adjust(Color.web(settings.punctColor(
                SettingsService.PUNCT_DIALOGUE_COLOR, SettingsService.DEFAULT_PUNCT_DIALOGUE_COLOR)), themeKey));
        colors.put(Role.STOP, adjust(Color.web(settings.punctColor(
                SettingsService.PUNCT_STOP_COLOR, SettingsService.DEFAULT_PUNCT_STOP_COLOR)), themeKey));
        colors.put(Role.ASK, adjust(Color.web(settings.punctColor(
                SettingsService.PUNCT_ASK_COLOR, SettingsService.DEFAULT_PUNCT_ASK_COLOR)), themeKey));
        colors.put(Role.EXCL, adjust(Color.web(settings.punctColor(
                SettingsService.PUNCT_EXCL_COLOR, SettingsService.DEFAULT_PUNCT_EXCL_COLOR)), themeKey));
        colors.put(Role.ELLIPSIS, adjust(Color.web(settings.punctColor(
                SettingsService.PUNCT_ELLIPSIS_COLOR, SettingsService.DEFAULT_PUNCT_ELLIPSIS_COLOR)), themeKey));
        colors.put(Role.PAREN, adjust(Color.web(settings.punctColor(
                SettingsService.PUNCT_PAREN_COLOR, SettingsService.DEFAULT_PUNCT_PAREN_COLOR)), themeKey));
        return colors;
    }

    /** 夜间提亮、护眼略降饱和，保证深色底上的可读性。 */
    static Color adjust(Color base, String themeKey) {
        return switch (themeKey == null ? "" : themeKey) {
            case "night" -> base.deriveColor(0, 0.78, 1.55, 1);
            case "green" -> base.deriveColor(0, 0.9, 0.95, 1);
            default -> base;
        };
    }
}
