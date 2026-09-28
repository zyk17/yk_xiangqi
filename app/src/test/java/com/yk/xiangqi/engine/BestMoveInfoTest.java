package com.yk.xiangqi.engine;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class BestMoveInfoTest {
    @Test
    public void parsesBestMoveAndPonderMove() {
        BestMoveInfo info = BestMoveInfo.parse("bestmove a0a1 ponder b9b8");
        assertEquals("a0a1", info.bestMove);
        assertEquals("b9b8", info.ponderMove);
    }
}
