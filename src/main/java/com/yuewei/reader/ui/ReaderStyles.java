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

/** 阅读器正文着色：把标点分段结果渲染成 TextFlow 中的彩色 Text 节点。 */
final class ReaderStyles {

    private ReaderStyles() {
    }

    static void fill(TextFlow flow, String content, Font font, boolean enabled, Rules rules,
                     Map<Role, Color> colors, Color ink) {
        flow.getChildren().clear();
        if (content == null || content.isEmpty()) {
            return;
        }
        List<Run> runs = enabled
                ? PunctuationHighlighter.highlight(content, rules)
                : List.of(new Run(content, Role.INK));
        for (Run run : runs) {
            Text node = new Text(run.text());
            node.setFont(font);
            node.setFill(run.role() == Role.INK ? ink : colors.getOrDefault(run.role(), ink));
            flow.getChildren().add(node);
        }
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
