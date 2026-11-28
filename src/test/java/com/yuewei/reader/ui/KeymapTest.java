package com.yuewei.reader.ui;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 快捷键：格式归一、冲突检测、按键匹配与默认值唯一性。 */
class KeymapTest {

    @Test
    void normalizesOrderAndCase() {
        assertEquals("CTRL+SHIFT+F", Keymap.normalize("shift+ctrl+f"));
        assertEquals("CTRL+ALT+PAGE_DOWN", Keymap.normalize(" alt + ctrl + page_down "));
        assertEquals("SPACE", Keymap.normalize("space"));
        assertEquals("", Keymap.normalize("  "));
        assertEquals("", Keymap.normalize("CTRL+"));
    }

    @Test
    void detectsConflict() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(Keymap.NEXT_PAGE, "SPACE");
        map.put(Keymap.PREV_PAGE, "BACK_SPACE");
        assertEquals("下一页", Keymap.conflict(map, Keymap.PREV_PAGE, "space"));
        assertNull(Keymap.conflict(map, Keymap.NEXT_PAGE, "space"));
        assertNull(Keymap.conflict(map, Keymap.PREV_PAGE, "shift+space"));
    }

    @Test
    void matchesKeyEvents() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(Keymap.NEXT_PAGE, "SPACE");
        map.put(Keymap.NEXT_CHAPTER, "CTRL+PAGE_DOWN");
        KeyEvent space = new KeyEvent(KeyEvent.KEY_PRESSED, " ", " ",
                KeyCode.SPACE, false, false, false, false);
        assertEquals(Keymap.NEXT_PAGE, Keymap.actionOf(map, space));
        KeyEvent ctrlPageDown = new KeyEvent(KeyEvent.KEY_PRESSED, "", "",
                KeyCode.PAGE_DOWN, false, true, false, false);
        assertEquals(Keymap.NEXT_CHAPTER, Keymap.actionOf(map, ctrlPageDown));
    }

    @Test
    void defaultCombosAreUnique() {
        Map<String, String> seen = new LinkedHashMap<>();
        for (Keymap.ActionDef def : Keymap.ACTIONS) {
            String combo = Keymap.normalize(def.defaultCombo());
            assertTrue(!seen.containsKey(combo),
                    "默认快捷键重复：" + combo + "（" + def.label() + " 与 " + seen.get(combo) + "）");
            seen.put(combo, def.label());
        }
    }
}
