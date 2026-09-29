package com.yk.xiangqi.link;

import android.media.Image;
import android.os.SystemClock;

import com.yk.xiangqi.Settings;
import com.yk.xiangqi.core.Board;
import com.yk.xiangqi.core.Move;
import com.yk.xiangqi.core.Position;
import com.yk.xiangqi.core.Side;

/**
 * 连线的一帧处理循环。
 *
 * <p>它不创建线程、不读取屏幕，也不写入应用棋局。ImageReader 回调交来一帧，
 * 本类同步处理后返回一个 {@link LinkAction}；服务层负责应用该动作。</p>
 */
public final class LinkLoop implements AutoCloseable {
    private static final int REPAIR_DIFFERENCE = 1;
    private static final int NEW_GAME_DIFFERENCE = 4;
    private static final ObservedBoard STANDARD_START = ObservedBoard.from(Position.start());

    private final FramePreprocessor preprocessor = new FramePreprocessor();
    private final MotionGate gate = new MotionGate();
    private final PieceRecognizer recognizer;
    private final float[] luma = new float[FramePreprocessor.GATE_WIDTH * FramePreprocessor.GATE_HEIGHT];
    private volatile BoardGeometry geometry;
    private volatile Side bottomSide;
    private Side newGameSide = Side.RED;
    private long lastFrameMs;
    private boolean initialBoardPending;
    private boolean stopped;

    public LinkLoop(PieceRecognizer recognizer) {
        this.recognizer = recognizer;
    }

    public void start(BoardGeometry geometry, Side bottomSide) {
        this.geometry = geometry;
        this.bottomSide = bottomSide;
        gate.reset();
        lastFrameMs = 0L;
        initialBoardPending = true;
        stopped = false;
    }

    public BoardGeometry geometry() {
        return geometry;
    }

    public Side bottomSide() {
        return bottomSide;
    }

    /**
     * 用户选择的下一局行棋方，也用于首次识别。
     */
    public void setNewGameSide(Side value) {
        newGameSide = value;
    }

    /**
     * 处理一张最新屏幕帧。未到采样时间或门控未放行时返回 {@code null}；
     * 其他情况总会返回一个明确的连线动作。
     */
    public LinkAction processFrame(Image image, Position current, Settings.Link config) throws Exception {
        // 门控的过滤
        if (stopped || geometry == null)
            return null;
        long now = SystemClock.elapsedRealtime();
        if (now - lastFrameMs < config.scanIntervalMs)
            return null;
        lastFrameMs = now;

        preprocessor.luma(image, geometry, luma);
        if (!gate.allow(luma, now, config))
            return null;

        // 同步调用NN
        ObservedBoard screenBoard = recognizer.recognize(preprocessor.input(image, geometry));
        // 如果是首帧
        if (initialBoardPending) {
            initialBoardPending = false;
            bottomSide = detectBottomSide(screenBoard);
            return LinkAction.reset(screenBoard.orientForBottom(bottomSide).toPosition(newGameSide));
        }

        // 如果是识别到初始局面
        Side standardStartBottom = standardStartBottom(screenBoard);
        if (standardStartBottom != null) {
            bottomSide = standardStartBottom;
            return LinkAction.reset(screenBoard.orientForBottom(bottomSide).toPosition(newGameSide));
        }

        // 否则比较 + 补救
        LinkAction action = compare(current, screenBoard);
        return action != null ? action : repair(current, screenBoard);
    }

    /**
     * 用户选择红先或黑先后，绕过门控立刻用当前帧同步。
     */
    public LinkAction synchronize(Image image, Side sideToMove) throws Exception {
        if (geometry == null)
            return null;
        ObservedBoard screenBoard = recognizer.recognize(preprocessor.input(image, geometry));
        bottomSide = detectBottomSide(screenBoard);
        gate.reset();
        initialBoardPending = false;
        stopped = false;
        return LinkAction.reset(screenBoard.orientForBottom(bottomSide).toPosition(sideToMove));
    }

    /**
     * 推理异常时由服务调用，避免相同错误在后续每帧重复触发。
     */
    public void stop() {
        stopped = true;
    }

    private static Side detectBottomSide(ObservedBoard screenBoard) {
        Side standard = standardStartBottom(screenBoard);
        return standard != null ? standard : inferBottomSide(screenBoard);
    }

    /**
     * 只接受未变、唯一精确走子和明显的新局；其余情况交给 repair。
     */
    LinkAction compare(Position current, ObservedBoard screenBoard) {
        ObservedBoard observed = screenBoard.orientForBottom(bottomSide);
        ObservedBoard currentBoard = ObservedBoard.from(current);
        int difference = observed.structureDifference(currentBoard);
        if (difference == 0)
            return LinkAction.NONE;

        Move move = findMove(current, currentBoard, observed, difference, 0);
        if (move != null)
            return LinkAction.play(move);
        if (difference > NEW_GAME_DIFFERENCE) {
            bottomSide = detectBottomSide(screenBoard);
            return LinkAction.reset(screenBoard.orientForBottom(bottomSide).toPosition(newGameSide));
        }
        return null;
    }

    /**
     * 精确比较未能确定结果后，最多容忍一格空红黑结构误差。
     */
    private LinkAction repair(Position current, ObservedBoard screenBoard) {
        ObservedBoard observed = screenBoard.orientForBottom(bottomSide);
        ObservedBoard currentBoard = ObservedBoard.from(current);
        int difference = observed.structureDifference(currentBoard);
        Move move = findMove(current, currentBoard, observed, difference, REPAIR_DIFFERENCE);
        if (move != null)
            return LinkAction.play(move);
        if (difference <= REPAIR_DIFFERENCE)
            return LinkAction.NONE;
        stopped = true;
        return LinkAction.DESYNCED;
    }

    /**
     * 合法走子只会改变起点与终点的空红黑三态。基于当前总差异修正这两格，
     * 无需为每个候选复制 Position、生成后继棋盘或重复合法性校验。
     */
    private static Move findMove(Position current, ObservedBoard currentBoard, ObservedBoard observed,
                                 int currentDifference, int allowedDifference) {
        Move result = null;
        int movingSide = current.sideToMove() == Side.RED ? 1 : 2;
        for (Move move : current.legalMoves()) {
            int difference = currentDifference;
            difference -= mismatch(observed.structureAt(move.from), currentBoard.structureAt(move.from));
            difference -= mismatch(observed.structureAt(move.to), currentBoard.structureAt(move.to));
            difference += mismatch(observed.structureAt(move.from), 0);
            difference += mismatch(observed.structureAt(move.to), movingSide);
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

    /**
     * 屏幕标签按两种观看方重排，能还原标准布局的一方即为屏幕底边方。
     */
    static Side standardStartBottom(ObservedBoard screenBoard) {
        if (screenBoard.orientForBottom(Side.RED).structureDifference(STANDARD_START) == 0)
            return Side.RED;
        if (screenBoard.orientForBottom(Side.BLACK).structureDifference(STANDARD_START) == 0)
            return Side.BLACK;
        return null;
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

    @Override
    public void close() {
        recognizer.close();
    }
}
