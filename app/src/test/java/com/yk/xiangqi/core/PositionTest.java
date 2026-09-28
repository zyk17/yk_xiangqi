package com.yk.xiangqi.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 验证局面、合法着与比赛规则。完整 perft 位于独立的 {@link PositionPerftTest}。 */
public final class PositionTest {
    @Test
    public void startPositionHasExpectedLegalMoves() {
        Position start = Position.start();
        assertEquals(44, start.legalMoves().size());
    }
    @Test
    public void blackPositionStaysInCanonicalCoordinates() {
        Position after = Position.start().play(Move.parse("a3a4"));
        assertEquals(Side.BLACK, after.sideToMove());
        assertPiece(after, Move.square('a', '4'), Side.RED, PieceType.PAWN);
        assertTrue(after.legalMoves().contains(Move.parse("a6a5")));
        assertEquals("rnbakabnr/9/1c5c1/p1p1p1p1p/9/P8/2P1P1P1P/1C5C1/9/RNBAKABNR b - - 1 1", after.toFen());
    }

    @Test
    public void uciMoveParserUsesFixedCoordinateValidation() {
        assertTrue(Move.isUciCoordinate("a0i9"));
        assertFalse(Move.isUciCoordinate("a0j9"));
        assertFalse(Move.isUciCoordinate("a0i10"));
        assertFalse(Move.isUciCoordinate(null));
    }
    @Test
    public void horseLegAndFlyingGeneralAreIllegal() {
        Position horse = Position.fromFen("4k4/9/9/9/9/9/9/3P5/3N5/4K4 w");
        assertFalse(horse.legalMoves().contains(Move.parse("d1e3")));
        Position face = Position.fromFen("4k4/9/9/9/9/9/9/9/4R4/4K4 w");
        assertFalse(face.legalMoves().contains(Move.parse("e1d1")));
    }

    @Test
    public void complexPositionsHaveExpectedLegalMoves() {
        Object[][] cases = {{"r1ba1a3/4kn3/2n1b4/pNp1p1p1p/4c4/6P2/P1P2R2P/1CcC5/9/2BAKAB2 w", 38},
            {"1cbak4/9/n2a5/2p1p3p/5cp2/2n2N3/6PCP/3AB4/2C6/3A1K1N1 w", 7}, {"5a3/3k5/3aR4/9/5r3/5n3/9/3A1A3/5K3/2BC2B2 w", 25},
            {"CRN1k1b2/3ca4/4ba3/9/2nr5/9/9/4B4/4A4/4KA3 w", 28}, {"C1nNk4/9/9/9/9/9/n1pp5/B3C4/9/3A1K3 w", 28}};
        for (Object[] entry : cases) {
            Position position = Position.fromFen((String) entry[0]);
            assertEquals(entry[0].toString(), ((Integer) entry[1]).intValue(), position.legalMoves().size());
        }
    }

    @Test
    public void directLegalCheckMatchesGeneratedMoves() {
        String[] fens = {Position.START_FEN, "r1ba1a3/4kn3/2n1b4/pNp1p1p1p/4c4/6P2/P1P2R2P/1CcC5/9/2BAKAB2 w",
            "5a3/3k5/3aR4/9/5r3/5n3/9/3A1A3/5K3/2BC2B2 w"};
        for (String fen : fens) {
            Position position = Position.fromFen(fen);
            for (int from = 0; from < Board.SQUARES; from++)
                for (int to = 0; to < Board.SQUARES; to++) {
                    Move move = new Move(from, to);
                    assertEquals(fen + ": " + move, position.legalMoves().contains(move), position.isLegal(move));
                }
        }
    }

    @Test
    public void sessionNavigationTruncatesFutureWithoutReplayingFenMoves() {
        GameState game = new GameState();
        game = play(game, "a3a4");
        game = play(game, "a6a5");
        game = reduce(game, GameAction.Navigate.PREVIOUS);
        game = play(game, "c6c5");
        assertEquals(2, game.cursor());
        assertEquals(2, game.allMoves().size());
        assertEquals("c6c5", game.allMoves().get(1).uci());
        assertSameState(game, game.reduce(GameAction.Navigate.NEXT));
    }

    @Test
    public void reduceReturnsNewStateAndLeavesInputUntouched() {
        GameState initial = new GameState();
        GameState afterRed = play(initial, "a3a4");
        assertEquals(0, initial.cursor());
        assertEquals(0, initial.allMoves().size());
        assertEquals(1, afterRed.cursor());
        assertEquals(1, afterRed.allMoves().size());

        GameState withFuture = play(afterRed, "a6a5");
        GameState middle = reduce(withFuture, GameAction.Navigate.PREVIOUS);
        GameState branch = play(middle, "c6c5");
        assertEquals("a6a5", withFuture.allMoves().get(1).uci());
        assertEquals("c6c5", branch.allMoves().get(1).uci());
        assertEquals(2, branch.allMoves().size());
    }

    @Test
    public void fullRuleJudgeMatchesReferenceCycles() {
        assertCycle("4k4/9/2c2an2/4c4/6R2/9/9/4B4/4A4/3K1AB2 w", "g5e5 f7e8 e5g5 e8f7 g5e5 f7e8 e5g5 e8f7", GameResult.DRAW);
        assertCycle("4k3c/9/4bn2n/8c/6R2/6P2/9/9/9/3K5 w", "g5i5 i6f6 i5g5 f6i6 g5i5 i6f6 i5g5 f6i6", GameResult.RED_WON);
        assertCycle("5k3/9/9/9/9/3C5/9/4B4/3K5/2p6 w", "d1e1 c0d0 e1d1 d0c0 d1e1 c0d0 e1d1 d0c0", GameResult.BLACK_WON);
        assertCycle("4k4/9/4n4/1c2N1c2/4N4/6P2/9/9/9/4K4 w", "e6g5 e7c6 g5e6 c6e7 e6g5 e7c6 g5e6 c6e7", GameResult.BLACK_WON);
        assertCycle("4k4/9/4n4/1c2N1r2/4N4/6P2/9/9/9/4K4 w", "e6g5 e7c6 g5e6 c6e7 e6g5 e7c6 g5e6 c6e7", GameResult.DRAW);
        assertCycle("3k5/9/3a5/2C6/2r6/2C6/2r6/5A3/9/5K3 w", "c4e4 c5e5 e4c4 e5c5 c4e4 c5e5 e4c4 e5c5", GameResult.BLACK_WON);
        assertCycle("3k5/9/3a5/2C6/2r6/2C6/2r6/2N2A3/9/5K3 w", "c4e4 c5e5 e4c4 e5c5 c4e4 c5e5 e4c4 e5c5", GameResult.DRAW);
    }

    private static void assertCycle(String fen, String line, GameResult expected) {
        GameState game = new GameState(Position.fromFen(fen));
        for (String move : line.split(" ")) game = play(game, move);
        assertEquals(2, game.position().repetitions());
        assertEquals(expected, game.computeGameResult());
    }

    @Test
    public void fullResultAppliesRule60ButSimpleResultDoesNot() {
        GameState game = new GameState(Position.fromFen("rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/"
            + "RNBAKABNR w - - 120 1"));
        assertEquals(GameResult.UNDECIDED, game.computeSimpleGameResult());
        assertEquals(GameResult.DRAW, game.computeGameResult());
    }

    @Test
    public void fullRuleResultUsesTheVisibleHistoryPrefix() {
        GameState game = new GameState(Position.fromFen("4k4/9/2c2an2/4c4/6R2/9/9/4B4/4A4/3K1AB2 w"));
        for (String move : "g5e5 f7e8 e5g5 e8f7 g5e5 f7e8 e5g5 e8f7".split(" ")) game = play(game, move);
        assertEquals(GameResult.DRAW, game.computeGameResult());
        game = reduce(game, GameAction.Navigate.PREVIOUS);
        assertEquals(GameResult.UNDECIDED, game.computeGameResult());
    }

    private static GameState play(GameState state, String uci) { return reduce(state, new GameAction.Play(Move.parse(uci))); }

    private static GameState reduce(GameState state, GameAction action) {
        GameState next = state.reduce(action);
        assertTrue("action 未产生新状态: " + action, next != state);
        return next;
    }

    private static void assertSameState(GameState expected, GameState actual) {
        assertTrue("无效 action 必须原样返回状态", expected == actual);
    }

    private static void assertPiece(Position position, int square, Side expectedSide, PieceType expectedType) {
        final Side[] side = {null};
        final PieceType[] type = {null};
        position.forEachPiece((at, foundSide, foundType) -> {
            if (at == square) {
                side[0] = foundSide;
                type[0] = foundType;
            }
        });
        assertEquals(expectedSide, side[0]);
        assertEquals(expectedType, type[0]);
    }
}
