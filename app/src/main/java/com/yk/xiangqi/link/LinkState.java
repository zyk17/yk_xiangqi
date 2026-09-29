package com.yk.xiangqi.link;

import com.yk.xiangqi.core.Board;
import com.yk.xiangqi.core.Move;
import com.yk.xiangqi.core.Position;
import com.yk.xiangqi.core.Side;

/**
 * 连线的纯状态归约。
 *
 * <p>输入当前应用局面与识别棋盘，输出下一连线状态和应用动作；不接触 Android 图像、
 * ONNX、线程或无障碍服务。</p>
 */
public record LinkState(Side bottomSide, Side newGameSide, Phase phase, long writebackDeadlineMs) {
    public enum Phase {INITIAL, NORMAL, WAITING_WRITEBACK, DESYNCED}

    private static final int REPAIR_DIFFERENCE = 1;
    private static final long WRITEBACK_WAIT_MS = 3_000L;
    private static final ObservedBoard STANDARD_START = ObservedBoard.from(Position.start());

    public static LinkState start(Side bottomSide, Side newGameSide) {
        return new LinkState(bottomSide, newGameSide, Phase.INITIAL, 0L);
    }

    public LinkState withNewGameSide(Side side) {
        return new LinkState(bottomSide, side, phase, writebackDeadlineMs);
    }

    public LinkState awaitWriteback(long nowMs) {
        if (phase == Phase.DESYNCED)
            return this;
        return new LinkState(bottomSide, newGameSide, Phase.WAITING_WRITEBACK, nowMs + WRITEBACK_WAIT_MS);
    }

    public LinkState cancelWriteback() {
        return phase == Phase.WAITING_WRITEBACK
            ? new LinkState(bottomSide, newGameSide, Phase.NORMAL, 0L)
            : this;
    }

    public LinkState stopRecognition() {
        return new LinkState(bottomSide, newGameSide, Phase.DESYNCED, 0L);
    }

    /** 用户同步当前识别棋盘时，直接重建应用局面。 */
    public LinkResult synchronize(ObservedBoard screenBoard, Side sideToMove) {
        return reset(screenBoard, sideToMove);
    }

    public LinkResult reduce(Position current, ObservedBoard screenBoard, long nowMs) {
        if (phase == Phase.DESYNCED)
            return result(LinkAction.NONE);
        if (phase == Phase.INITIAL)
            return reset(screenBoard, newGameSide);

        ObservedBoard observed = screenBoard.orientForBottom(bottomSide);
        ObservedBoard currentBoard = ObservedBoard.from(current);
        int difference = observed.structureDifference(currentBoard);
        if (phase == Phase.WAITING_WRITEBACK) {
            if (difference == 0)
                return normal(LinkAction.NONE);
            Move response = findMove(current, currentBoard, observed, difference, 0);
            if (response != null)
                return normal(LinkAction.play(response));
            if (nowMs < writebackDeadlineMs)
                return result(LinkAction.NONE);
        }

        if (difference == 0)
            return normal(LinkAction.NONE);

        Move move = findMove(current, currentBoard, observed, difference, 0);
        if (move != null)
            return normal(LinkAction.play(move));
        if (standardStartBottom(screenBoard) != null)
            return reset(screenBoard, newGameSide);

        move = findMove(current, currentBoard, observed, difference, REPAIR_DIFFERENCE);
        if (move != null)
            return normal(LinkAction.play(move));
        if (difference <= REPAIR_DIFFERENCE)
            return normal(LinkAction.NONE);
        return new LinkResult(stopRecognition(), LinkAction.DESYNCED);
    }

    static Side standardStartBottom(ObservedBoard screenBoard) {
        if (screenBoard.orientForBottom(Side.RED).structureDifference(STANDARD_START) == 0)
            return Side.RED;
        if (screenBoard.orientForBottom(Side.BLACK).structureDifference(STANDARD_START) == 0)
            return Side.BLACK;
        return null;
    }

    private LinkResult reset(ObservedBoard screenBoard, Side sideToMove) {
        Side standardBottom = standardStartBottom(screenBoard);
        if (standardBottom != null)
            return reset(standardBottom, STANDARD_START.toPosition(sideToMove));
        if (!screenBoard.canBuildPosition())
            return failed();
        Side bottom = inferBottomSide(screenBoard);
        return reset(bottom, screenBoard.orientForBottom(bottom).toPosition(sideToMove));
    }

    private LinkResult reset(Side bottom, Position position) {
        LinkState next = new LinkState(bottom, newGameSide, Phase.NORMAL, 0L);
        return new LinkResult(next, LinkAction.reset(position));
    }

    private LinkResult failed() {
        return new LinkResult(stopRecognition(), LinkAction.DESYNCED);
    }

    private LinkResult normal(LinkAction action) {
        return new LinkResult(new LinkState(bottomSide, newGameSide, Phase.NORMAL, 0L), action);
    }

    private LinkResult result(LinkAction action) {
        return new LinkResult(this, action);
    }

    private static Move findMove(Position current, ObservedBoard currentBoard, ObservedBoard observed,
                                 int currentDifference, int allowedDifference) {
        Move result = null;
        int movingSide = current.sideToMove() == Side.RED ? 1 : 2;
        for (Move move : current.legalMoves()) {
            // 起终点定义这一步；补救只能忽略其余不动格的一处结构误差。
            if (observed.structureAt(move.from) != 0 || observed.structureAt(move.to) != movingSide)
                continue;
            int difference = currentDifference;
            difference -= mismatch(observed.structureAt(move.from), currentBoard.structureAt(move.from));
            difference -= mismatch(observed.structureAt(move.to), currentBoard.structureAt(move.to));
            if (difference > allowedDifference)
                continue;
            if (result != null)
                return null;
            result = move;
        }
        return result;
    }

    private static int mismatch(int first, int second) {
        return first == second ? 0 : 1;
    }

    private static Side inferBottomSide(ObservedBoard board) {
        int redKing = -1;
        int blackKing = -1;
        for (int square = 0; square < Board.SQUARES; square++) {
            if (board.at(square) == 1) redKing = square;
            if (board.at(square) == 8) blackKing = square;
        }
        if (redKing < 0 || blackKing < 0)
            return Side.RED;
        return Board.rank(redKing) > Board.rank(blackKing) ? Side.RED : Side.BLACK;
    }
}
