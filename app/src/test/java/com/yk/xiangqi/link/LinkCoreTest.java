package com.yk.xiangqi.link;

import static org.junit.Assert.assertEquals;

import com.yk.xiangqi.core.Board;
import com.yk.xiangqi.core.Move;
import com.yk.xiangqi.core.PieceType;
import com.yk.xiangqi.core.Position;
import com.yk.xiangqi.core.Side;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

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
    public void comparatorFindsObservedLegalMove() {
        Position position = Position.start();
        Move move = Move.parse("a3a4");
        LinkAction action = state().reduce(position, screenForRed(ObservedBoard.from(position.play(move))), 0L).action();
        assertEquals(LinkAction.Kind.PLAY, action.kind());
        assertEquals(move, action.move());
    }

    @Test
    public void comparatorIgnoresWrongPieceKinds() {
        Position position = Position.start();
        Move move = Move.parse("a3a4");
        LinkAction action = state().reduce(position, screenForRed(wrongKinds(ObservedBoard.from(position.play(move)))), 0L).action();
        assertEquals(LinkAction.Kind.PLAY, action.kind());
        assertEquals(move, action.move());
    }

    @Test
    public void repairRequiresBothMoveEndpoints() {
        Position position = Position.start();
        LinkAction action = state().reduce(position,
            screenForRed(withLabel(position, Board.square(0, 3), ObservedBoard.EMPTY)), 0L).action();
        assertEquals(LinkAction.Kind.NONE, action.kind());
    }

    @Test
    public void repairAllowsOneUnchangedSquareError() {
        Position position = Position.start();
        Move move = Move.parse("a3a4");
        LinkAction action = state().reduce(position,
            screenForRed(withLabel(position.play(move), Board.square(8, 9), ObservedBoard.EMPTY)), 0L).action();
        assertEquals(LinkAction.Kind.PLAY, action.kind());
        assertEquals(move, action.move());
    }

    @Test
    public void standardStartRepairsDuplicateKingLabels() {
        ObservedBoard malformed = wrongKinds(ObservedBoard.from(Position.start()));
        LinkResult result = LinkState.start(Side.RED, Side.RED).synchronize(screenForRed(malformed), Side.RED);
        assertEquals(LinkAction.Kind.RESET, result.action().kind());
        assertEquals(Position.start().toFen(), result.action().position().toFen());
    }

    @Test
    public void invalidNonStandardSyncFailsWithoutThrowing() {
        Position position = Position.start().play(Move.parse("a3a4"));
        LinkResult result = LinkState.start(Side.RED, Side.RED)
            .synchronize(screenForRed(wrongKinds(ObservedBoard.from(position))), Side.BLACK);
        assertEquals(LinkAction.Kind.DESYNCED, result.action().kind());
        assertEquals(LinkState.Phase.DESYNCED, result.state().phase());
    }

    @Test
    public void comparatorHandlesBlackMoveAndCapture() {
        Position afterRed = Position.start().play(Move.parse("a3a4"));
        Move blackMove = Move.parse("a6a5");
        LinkAction blackAction = state().reduce(afterRed, screenForRed(ObservedBoard.from(afterRed.play(blackMove))), 0L).action();
        assertEquals(LinkAction.Kind.PLAY, blackAction.kind());
        assertEquals(blackMove, blackAction.move());

        Position beforeCapture = afterRed.play(blackMove);
        Move capture = Move.parse("a4a5");
        LinkAction captureAction = state().reduce(beforeCapture, screenForRed(ObservedBoard.from(beforeCapture.play(capture))), 0L).action();
        assertEquals(LinkAction.Kind.PLAY, captureAction.kind());
        assertEquals(capture, captureAction.move());
    }

    @Test
    public void samplingPlanKeepsBilinearNchwInput() {
        int width = 81, height = 93, pixelStride = 4, rowStride = width * pixelStride + 12;
        ByteBuffer pixels = ByteBuffer.allocateDirect(rowStride * height);
        for (int index = 0; index < pixels.capacity(); index++)
            pixels.put(index, (byte) (index * 37 + 11));
        assertSamplingEqualsLegacy(pixels, new BoardGeometry(10.25f, 12.75f, 70.5f, 82.5f),
            width, height, rowStride, pixelStride);
        // 框选靠近屏幕边缘时，半格采样区会超出图像；验证旧的 clamp 语义也保持一致。
        assertSamplingEqualsLegacy(pixels, new BoardGeometry(1.25f, 1.75f, 61.5f, 71.5f),
            width, height, rowStride, pixelStride);
    }

    @Test
    public void writebackWaitsForOldBoardThenAcceptsReply() {
        Position afterRed = Position.start().play(Move.parse("a3a4"));
        LinkState waiting = state().awaitWriteback(100L);

        LinkResult oldBoard = waiting.reduce(afterRed, screenForRed(ObservedBoard.from(Position.start())), 101L);
        assertEquals(LinkAction.Kind.NONE, oldBoard.action().kind());
        assertEquals(LinkState.Phase.WAITING_WRITEBACK, oldBoard.state().phase());

        Move reply = Move.parse("a6a5");
        LinkResult response = oldBoard.state().reduce(afterRed,
            screenForRed(ObservedBoard.from(afterRed.play(reply))), 102L);
        assertEquals(LinkAction.Kind.PLAY, response.action().kind());
        assertEquals(reply, response.action().move());
        assertEquals(LinkState.Phase.NORMAL, response.state().phase());
    }

    @Test
    public void nonStandardLargeStructuralChangeDesyncsInsteadOfResetting() {
        Position current = Position.start().play(Move.parse("a3a4")).play(Move.parse("a6a5")).play(Move.parse("c3c4"));
        Position unrelated = Position.start().play(Move.parse("e3e4"));
        LinkAction action = state().reduce(current, screenForRed(ObservedBoard.from(unrelated)), 0L).action();
        assertEquals(LinkAction.Kind.DESYNCED, action.kind());
    }

    @Test
    public void gesturePlanRotatesForBlackBottom() {
        BoardGeometry geometry = new BoardGeometry(0, 0, 800, 900);
        Move move = new Move(Board.square(0, 0), Board.square(0, 1));
        GesturePlan red = GesturePlan.forMove(geometry, Side.RED, move, 30, 50);
        GesturePlan black = GesturePlan.forMove(geometry, Side.BLACK, move, 30, 50);
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
        assertEquals(Side.RED, LinkState.standardStartBottom(start.orientForBottom(Side.RED)));
        assertEquals(Side.BLACK, LinkState.standardStartBottom(start.orientForBottom(Side.BLACK)));
    }

    @Test
    public void desyncedStateAutomaticallyRecoversOnlyAtStandardStart() {
        LinkState desynced = state().stopRecognition();
        LinkResult result = desynced.reduce(Position.start(), screenForRed(ObservedBoard.from(Position.start())), 0L);
        assertEquals(LinkAction.Kind.RESET, result.action().kind());
        assertEquals(LinkState.Phase.NORMAL, result.state().phase());
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

    private static LinkState state() {
        ObservedBoard start = screenForRed(ObservedBoard.from(Position.start()));
        return LinkState.start(Side.RED, Side.RED).synchronize(start, Side.RED).state();
    }

    private static ObservedBoard withLabel(Position position, int changedSquare, byte changedLabel) {
        float[] logits = new float[Board.SQUARES * ObservedBoard.LABELS];
        position.forEachPiece((square, side, type) -> logits[square * ObservedBoard.LABELS + label(side, type)] = 10f);
        for (int label = 0; label < ObservedBoard.LABELS; label++)
            logits[changedSquare * ObservedBoard.LABELS + label] = label == changedLabel ? 10f : 0f;
        return ObservedBoard.fromLogits(logits);
    }

    private static int label(Side side, PieceType type) {
        int kind = switch (type) {
            case KING -> 1;
            case ADVISOR -> 2;
            case BISHOP -> 3;
            case KNIGHT -> 4;
            case ROOK -> 5;
            case CANNON -> 6;
            case PAWN -> 7;
        };
        return side == Side.RED ? kind : kind + 7;
    }

    private static FloatBuffer floats() {
        return ByteBuffer.allocateDirect(Board.SQUARES * 3 * 48 * 48 * Float.BYTES)
            .order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    /** 优化前的双线性采样，作为模型输入兼容性基准。 */
    private static FloatBuffer legacyInput(ByteBuffer pixels, BoardGeometry geometry, int width, int height,
                                           int rowStride, int pixelStride) {
        float[] mean = {.485f, .456f, .406f};
        float[] invStd = {1f / .229f, 1f / .224f, 1f / .225f};
        FloatBuffer out = floats();
        for (int row = 0; row < Board.RANKS; row++)
            for (int column = 0; column < Board.FILES; column++) {
                BoardGeometry.Rect cell = geometry.cell(column, row);
                for (int channel = 0; channel < 3; channel++)
                    for (int y = 0; y < 48; y++)
                        for (int x = 0; x < 48; x++) {
                            float sourceX = cell.left() + (x + .5f) * (cell.right() - cell.left()) / 48f;
                            float sourceY = cell.top() + (y + .5f) * (cell.bottom() - cell.top()) / 48f;
                            int x0 = clamp((int) Math.floor(sourceX), 0, width - 1);
                            int y0 = clamp((int) Math.floor(sourceY), 0, height - 1);
                            int x1 = Math.min(x0 + 1, width - 1), y1 = Math.min(y0 + 1, height - 1);
                            float dx = sourceX - x0, dy = sourceY - y0;
                            float a = pixel(pixels, y0 * rowStride + x0 * pixelStride + channel);
                            float b = pixel(pixels, y0 * rowStride + x1 * pixelStride + channel);
                            float c = pixel(pixels, y1 * rowStride + x0 * pixelStride + channel);
                            float d = pixel(pixels, y1 * rowStride + x1 * pixelStride + channel);
                            float value = (a + (b - a) * dx) + ((c + (d - c) * dx) - (a + (b - a) * dx)) * dy;
                            out.put((value / 255f - mean[channel]) * invStd[channel]);
                        }
            }
        out.rewind();
        return out;
    }

    private static void assertSamplingEqualsLegacy(ByteBuffer pixels, BoardGeometry geometry, int width, int height,
                                                   int rowStride, int pixelStride) {
        float[] actual = new float[Board.SQUARES * 3 * 48 * 48];
        new PieceRecognizer.SamplingPlan(geometry, width, height, rowStride, pixelStride).fill(pixels, actual);
        FloatBuffer expected = legacyInput(pixels, geometry, width, height, rowStride, pixelStride);
        for (int index = 0; index < actual.length; index++)
            assertEquals("输入索引=" + index, expected.get(index), actual[index], 0f);
    }

    private static int pixel(ByteBuffer pixels, int offset) {
        return pixels.get(offset) & 255;
    }

    private static int clamp(int value, int lower, int upper) {
        return Math.max(lower, Math.min(upper, value));
    }
}
