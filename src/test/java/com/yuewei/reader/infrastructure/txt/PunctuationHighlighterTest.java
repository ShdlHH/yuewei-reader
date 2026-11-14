package com.yuewei.reader.infrastructure.txt;

import com.yuewei.reader.infrastructure.txt.PunctuationHighlighter.Role;
import com.yuewei.reader.infrastructure.txt.PunctuationHighlighter.Rules;
import com.yuewei.reader.infrastructure.txt.PunctuationHighlighter.Run;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PunctuationHighlighterTest {

    private static String joined(List<Run> runs) {
        return runs.stream().map(Run::text).collect(Collectors.joining());
    }

    private static List<Role> roles(List<Run> runs) {
        return runs.stream().map(Run::role).toList();
    }

    @Test
    void keepsEveryCharacter() {
        String text = "他说：“走吧。”然后离开。\n「真的？」";
        List<Run> runs = PunctuationHighlighter.highlight(text, Rules.defaults());
        assertEquals(text, joined(runs));
    }

    @Test
    void colorsDialogueAndPeriod() {
        List<Run> runs = PunctuationHighlighter.highlight("他说：“练成过。”随即沉默。", Rules.defaults());
        assertTrue(runs.stream().anyMatch(run -> run.role() == Role.DIALOGUE && run.text().contains("练成过")));
        assertEquals(2, runs.stream().filter(run -> run.role() == Role.STOP)
                .mapToInt(run -> run.text().length()).sum());
        assertEquals(Role.INK, runs.get(0).role());
    }

    @Test
    void leavesPlainNarrationInk() {
        List<Run> runs = PunctuationHighlighter.highlight("雨下了整夜，檐角的水线连成薄帘", Rules.defaults());
        assertEquals(List.of(Role.INK), roles(runs));
    }

    @Test
    void disablesAllRulesToSingleRun() {
        Rules off = new Rules(false, false, false, false, false, false);
        List<Run> runs = PunctuationHighlighter.highlight("“走吧。”他说。", off);
        assertEquals(1, runs.size());
        assertEquals(Role.INK, runs.get(0).role());
    }

    @Test
    void colorsQuestionAndExclamation() {
        List<Run> runs = PunctuationHighlighter.highlight("“真的？”他喊道！“走！”", Rules.defaults());
        assertTrue(roles(runs).contains(Role.ASK));
        assertTrue(roles(runs).contains(Role.EXCL));
    }

    @Test
    void optionalRulesStayInkByDefault() {
        List<Run> runs = PunctuationHighlighter.highlight("他愣住（旧话）……", Rules.defaults());
        assertTrue(roles(runs).stream().noneMatch(role -> role == Role.PAREN));
        assertTrue(roles(runs).stream().noneMatch(role -> role == Role.ELLIPSIS));

        List<Run> all = PunctuationHighlighter.highlight("他愣住（旧话）……", Rules.allEnabled());
        assertTrue(roles(all).contains(Role.PAREN));
        assertTrue(roles(all).contains(Role.ELLIPSIS));
    }

    @Test
    void mergesNeighbouringRuns() {
        List<Run> runs = PunctuationHighlighter.highlight("…………", Rules.allEnabled());
        assertEquals(1, runs.size());
        assertEquals(Role.ELLIPSIS, runs.get(0).role());
    }

    @Test
    void handlesNestedQuotes() {
        String text = "「他说『好』。」";
        List<Run> runs = PunctuationHighlighter.highlight(text, Rules.defaults());
        assertEquals(text, joined(runs));
        assertTrue(roles(runs).contains(Role.DIALOGUE));
        assertTrue(roles(runs).contains(Role.STOP));
    }

    @Test
    void finishesLargeChapterQuickly() {
        String text = ("他抬头看向窗外。“雨停了。”他说。\n").repeat(2000);
        long start = System.nanoTime();
        List<Run> runs = PunctuationHighlighter.highlight(text, Rules.defaults());
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertEquals(text, joined(runs));
        assertTrue(millis < 300, "着色耗时 " + millis + " ms");
    }
}
