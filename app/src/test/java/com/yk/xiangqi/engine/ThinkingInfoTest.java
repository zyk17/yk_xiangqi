package com.yk.xiangqi.engine;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** 验证单行 UCI 分析信息。 */
public final class ThinkingInfoTest {
    @Test
    public void parsesCompleteInfoLine() {
        ThinkingInfo value = ThinkingInfo.parse(
            "info depth 18 time 321 nodes 456 nps 789 score cp 42 wdl 500 300 200 pv a0a1 b9b8");
        assertEquals(Integer.valueOf(18), value.depth);
        assertEquals(Long.valueOf(321), value.timeMs);
        assertEquals(Long.valueOf(456), value.nodes);
        assertEquals(Long.valueOf(789), value.nps);
        assertEquals(Integer.valueOf(42), value.score);
        assertEquals(500L, value.wdl.win);
        assertEquals(300L, value.wdl.draw);
        assertEquals(200L, value.wdl.loss);
        assertEquals("a0a1", value.pv.get(0));
        assertEquals(2, value.pv.size());
    }

    @Test
    public void parsesMateScore() {
        ThinkingInfo value = ThinkingInfo.parse("info depth 20 score mate -3");
        assertEquals(Integer.valueOf(-3), value.mate);
        assertEquals(null, value.score);
    }

    @Test
    public void parsesCpOmittedScoreAtEndOfLine() {
        ThinkingInfo value = ThinkingInfo.parse("info depth 8 score -17");
        assertEquals(Integer.valueOf(-17), value.score);
    }
}
