package com.yk.xiangqi.core;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.junit.experimental.categories.Category;

/** 完整局面树计数基准；仅由 Gradle 的 perftTest 任务执行。 */
@Category(PerftTest.class)
public final class PositionPerftTest {
    @Test
    public void startPositionPerft() {
        Position start = Position.start();
        assertEquals(1920, perft(start, 2));
        assertEquals(79666, perft(start, 3));
    }

    @Test
    public void complexPositionPerftAtTwoPlies() {
        Object[][] cases = {{"r1ba1a3/4kn3/2n1b4/pNp1p1p1p/4c4/6P2/P1P2R2P/1CcC5/9/2BAKAB2 w", 1128L},
            {"1cbak4/9/n2a5/2p1p3p/5cp2/2n2N3/6PCP/3AB4/2C6/3A1K1N1 w", 281L}, {"5a3/3k5/3aR4/9/5r3/5n3/9/3A1A3/5K3/2BC2B2 w", 424L},
            {"CRN1k1b2/3ca4/4ba3/9/2nr5/9/9/4B4/4A4/4KA3 w", 516L}, {"C1nNk4/9/9/9/9/9/n1pp5/B3C4/9/3A1K3 w", 222L}};
        for (Object[] entry : cases)
            assertEquals(entry[0].toString(), ((Long) entry[1]).longValue(), perft(Position.fromFen((String) entry[0]), 2));
    }

    private static long perft(Position position, int depth) {
        if (depth == 0)
            return 1;
        long nodes = 0;
        for (Move move : position.legalMoves()) nodes += perft(position.play(move), depth - 1);
        return nodes;
    }
}
