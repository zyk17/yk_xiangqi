package com.yk.xiangqi.link;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.yk.xiangqi.Settings;
import com.yk.xiangqi.core.Board;
import com.yk.xiangqi.core.Move;
import com.yk.xiangqi.core.Position;
import com.yk.xiangqi.core.Side;
import org.junit.Test;

public final class LinkCoreTest {
    @Test
    public void geometryUsesIntersectionCentersAndHalfCells() {
        BoardGeometry geometry = new BoardGeometry(100, 200, 900, 1100);
        assertEquals(100f, geometry.center(0, 0).x(), 0f);
        assertEquals(200f, geometry.center(0, 0).y(), 0f);
        assertEquals(100f, geometry.stepX(), 0f);
        assertEquals(100f, geometry.stepY(), 0f);
        BoardGeometry.Rect cell = geometry.cell(0, 0);
        assertEquals(50f, cell.left(), 0f);
        assertEquals(150f, cell.right(), 0f);
    }

    @Test
    public void motionGateOnlyAllowsOncePerStaticPeriod() {
        MotionGate gate = new MotionGate();
        Settings.Link config = new Settings.Link(100, .02f, 50);
        assertFalse(gate.allow(new float[] {0f, 0f}, 0, config));
        assertFalse(gate.allow(new float[] {0f, 0f}, 99, config));
        assertTrue(gate.allow(new float[] {0f, 0f}, 100, config));
        assertFalse(gate.allow(new float[] {0f, 0f}, 133, config));
        assertFalse(gate.allow(new float[] {.1f, 0f}, 166, config));
        assertTrue(gate.allow(new float[] {.1f, 0f}, 266, config));
    }

    @Test
    public void comparatorFindsObservedLegalMove() {
        Position position = Position.start();
        Move move = Move.parse("a3a4");
        LinkAction action = loop().compare(position, screenForRed(ObservedBoard.from(position.play(move))));
        assertEquals(LinkAction.Kind.PLAY, action.kind());
        assertEquals(move, action.move());
    }

    @Test
    public void comparatorIgnoresWrongPieceKinds() {
        Position position = Position.start();
        Move move = Move.parse("a3a4");
        LinkAction action = loop().compare(position, screenForRed(wrongKinds(ObservedBoard.from(position.play(move)))));
        assertEquals(LinkAction.Kind.PLAY, action.kind());
        assertEquals(move, action.move());
    }

    @Test
    public void comparatorHandlesBlackMoveAndCapture() {
        Position afterRed = Position.start().play(Move.parse("a3a4"));
        Move blackMove = Move.parse("a6a5");
        LinkAction blackAction = loop().compare(afterRed, screenForRed(ObservedBoard.from(afterRed.play(blackMove))));
        assertEquals(LinkAction.Kind.PLAY, blackAction.kind());
        assertEquals(blackMove, blackAction.move());

        Position beforeCapture = afterRed.play(blackMove);
        Move capture = Move.parse("a4a5");
        LinkAction captureAction = loop().compare(beforeCapture, screenForRed(ObservedBoard.from(beforeCapture.play(capture))));
        assertEquals(LinkAction.Kind.PLAY, captureAction.kind());
        assertEquals(capture, captureAction.move());
    }

    @Test
    public void largeStructuralChangeIsNewGameInsteadOfDesync() {
        Position current = Position.start().play(Move.parse("a3a4")).play(Move.parse("a6a5")).play(Move.parse("c3c4"));
        LinkAction action = loop().compare(current, screenForRed(ObservedBoard.from(Position.start())));
        assertEquals(LinkAction.Kind.RESET, action.kind());
    }

    @Test
    public void gesturePlanRotatesForBlackBottom() {
        BoardGeometry geometry = new BoardGeometry(0, 0, 800, 900);
        Move move = new Move(Board.square(0, 0), Board.square(0, 1));
        GesturePlan red = GesturePlan.forMove(geometry, Side.RED, move, 50);
        GesturePlan black = GesturePlan.forMove(geometry, Side.BLACK, move, 50);
        assertEquals(0f, red.from().x(), 0f);
        assertEquals(900f, red.from().y(), 0f);
        assertEquals(800f, black.from().x(), 0f);
        assertEquals(0f, black.from().y(), 0f);
    }

    @Test
    public void screenLabelsAreReorderedWithoutAnotherInference() {
        byte[] screen = new byte[Board.SQUARES];
        screen[Board.square(0, 0)] = 8;
        screen[Board.square(8, 9)] = 1;
        ObservedBoard observed = new ObservedBoard(screen);
        ObservedBoard red = observed.orientForBottom(Side.RED);
        assertEquals(8, red.at(Board.square(0, 9)));
        assertEquals(1, red.at(Board.square(8, 0)));
        ObservedBoard black = observed.orientForBottom(Side.BLACK);
        assertEquals(8, black.at(Board.square(8, 0)));
        assertEquals(1, black.at(Board.square(0, 9)));
    }

    @Test
    public void standardStartDetectsViewerSideFromScreenStructure() {
        ObservedBoard start = ObservedBoard.from(Position.start());
        assertEquals(Side.RED, LinkLoop.standardStartBottom(start.orientForBottom(Side.RED)));
        assertEquals(Side.BLACK, LinkLoop.standardStartBottom(start.orientForBottom(Side.BLACK)));
    }

    private static ObservedBoard wrongKinds(ObservedBoard board) {
        float[] logits = new float[Board.SQUARES * ObservedBoard.LABELS];
        for (int square = 0; square < Board.SQUARES; square++) {
            byte label = board.at(square);
            int wrong = label == 0 ? 0 : label <= 7 ? 1 : 8;
            logits[square * ObservedBoard.LABELS + wrong] = 10f;
        }
        return ObservedBoard.fromLogits(logits);
    }

    private static ObservedBoard screenForRed(ObservedBoard board) {
        return board.orientForBottom(Side.RED);
    }

    private static LinkLoop loop() {
        LinkLoop loop = new LinkLoop(null);
        loop.start(new BoardGeometry(0, 0, 800, 900), Side.RED);
        return loop;
    }
}
