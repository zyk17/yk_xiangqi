package com.yk.xiangqi.engine;

import static org.junit.Assert.assertEquals;

import java.util.List;
import org.junit.Test;

public final class GoParamsTest {
    @Test
    public void writesAllSupportedGoFieldsInUciOrder() {
        GoParams params = new GoParams();
        params.wtime = 1_000L;
        params.btime = 2_000L;
        params.winc = 10L;
        params.binc = 20L;
        params.movesToGo = 30;
        params.depth = 18;
        params.mate = 4;
        params.nodes = 5_000L;
        params.moveTime = 500L;
        params.searchMoves.addAll(List.of("a0a1", "b0b1"));
        params.ponder = true;
        params.infinite = true;

        assertEquals("go wtime 1000 btime 2000 winc 10 binc 20 movestogo 30 depth 18 mate 4 nodes 5000 movetime 500 "
            + "searchmoves a0a1 b0b1 ponder infinite", params.uci());
    }

    @Test
    public void buildsFiniteAndInfiniteSearches() {
        GoParams finite = new GoParams();
        finite.depth = 18;
        assertEquals("go depth 18", finite.uci());
        assertEquals("go searchmoves a0a1 infinite", GoParams.infinite(List.of("a0a1")).uci());
    }
}
