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

    /**
     * 将一张已经稳定下来的识别棋盘归约为连线动作。
     *
     * <p>{@code current} 是应用的权威局面；{@code screenBoard} 是模型刚识别出的屏幕棋盘。
     * 比较时只使用格子的空、红、黑结构，不相信具体棋子类别：类别偶尔识别错时，仍可根据
     * 起点、终点和合法着法恢复正确走子。</p>
     *
     * <p>返回的 {@link LinkAction} 只描述应该如何更新应用；下一轮所需的连线阶段放在
     * {@link LinkResult#state()} 中，调用方必须同时保存二者。</p>
     */
    public LinkResult reduce(Position current, ObservedBoard screenBoard, long nowMs) {
        // 0) 已失步时不再把任意屏幕变化当作走子；标准初始局面是唯一可无歧义自动恢复的信号。
        if (phase == Phase.DESYNCED) {
            if (standardStartBottom(screenBoard) != null)
                return reset(screenBoard, newGameSide);
            return result(LinkAction.NONE);
        }
        // 1) 刚开始连线时没有可比较的旧局面，首张识别结果尝试直接建立应用局面。
        if (phase == Phase.INITIAL)
            return reset(screenBoard, newGameSide);

        // 2) 统一到使用者视角后，将屏幕棋盘与应用当前局面逐格比较。
        ObservedBoard observed = screenBoard.orientForBottom(bottomSide);
        ObservedBoard currentBoard = ObservedBoard.from(current);
        int difference = observed.structureDifference(currentBoard);
        if (phase == Phase.WAITING_WRITEBACK) {
            // 本地刚通过无障碍回写走子：第三方棋盘仍显示旧局面是正常的，继续等待即可。
            if (difference == 0)
                return normal(LinkAction.NONE);
            // 若第三方已显示与本地回写不同的一步，优先把它当作对方的即时回应。
            Move response = findMove(current, currentBoard, observed, difference, 0);
            if (response != null)
                return normal(LinkAction.play(response));
            // 回写宽限期内的动画帧、过渡帧不参与失步判定。
            if (nowMs < writebackDeadlineMs)
                return result(LinkAction.NONE);
        }

        // 3) 普通阶段：结构完全相同，屏幕没有形成新的走子。
        if (difference == 0)
            return normal(LinkAction.NONE);

        // 4) 先要求屏幕与某个合法着法完全匹配；这是正常外部走子的主要路径。
        Move move = findMove(current, currentBoard, observed, difference, 0);
        if (move != null)
            return normal(LinkAction.play(move));
        // 5) 若当前识别结果恰为标准开局，视为第三方开始了一盘新棋。
        if (standardStartBottom(screenBoard) != null)
            return reset(screenBoard, newGameSide);

        // 6) 最后只允许一个与起终点无关的格子结构误差，补偿单个漏识别/颜色误识别。
        // 起点和终点始终必须正确，故不会把“少识别一个兵”误判为它走了一步。
        move = findMove(current, currentBoard, observed, difference, REPAIR_DIFFERENCE);
        if (move != null)
            return normal(LinkAction.play(move));
        // 单格误差不足以确定走子时保留当前局面，等待下一张稳定帧。
        if (difference <= REPAIR_DIFFERENCE)
            return normal(LinkAction.NONE);
        // 其余情况无法安全解释为一步合法走子，停止自动处理，等待用户手动同步。
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
