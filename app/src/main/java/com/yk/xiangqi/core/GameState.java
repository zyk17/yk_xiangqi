package com.yk.xiangqi.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 不可变的一条线性对局。所有改变都通过 {@link #reduce(GameAction)} 返回新状态；
 * 中间局面落子会在新状态中确定性地截断未来历史。
 */
public final class GameState {
    /**
     * 当前棋局的规则状态；只表达进行或终局原因。
     */
    public enum GameStatus {ONGOING, CHECKMATE, STALEMATE}

    private final List<Position> positions;
    private final List<Move> moves;
    private final int cursor;

    public GameState() {
        this(Position.start());
    }

    public GameState(Position initial) {
        if (initial == null)
            throw new IllegalArgumentException("initial 不能为空");
        positions = List.of(initial);
        moves = List.of();
        cursor = 0;
    }

    private GameState(List<Position> positions, List<Move> moves, int cursor) {
        if (positions.isEmpty() || positions.size() != moves.size() + 1 || cursor < 0 || cursor > moves.size())
            throw new IllegalArgumentException("无效的线性棋局状态");
        this.positions = List.copyOf(positions);
        this.moves = List.copyOf(moves);
        this.cursor = cursor;
    }

    public Position position() {
        return positions.get(cursor);
    }

    /**
     * 线性对局的根局面。返回的 {@link Position} 不可变，可用于 UCI 等协议边界重放当前着法序列。
     */
    public Position initialPosition() {
        return positions.get(0);
    }

    /**
     * 按历史 ply 返回不可变局面快照，包含游标之后尚未截断的未来。
     */
    public Position positionAt(int ply) {
        return positions.get(ply);
    }

    public int cursor() {
        return cursor;
    }

    /**
     * 当前游标之前的主线着法。
     */
    public List<Move> moves() {
        return moves.subList(0, cursor);
    }

    /**
     * 未因中间落子截断前，完整线性历史中的全部着法。
     */
    public List<Move> allMoves() {
        return moves;
    }

    /**
     * 纯归约：非法着或无法继续导航时返回当前实例；其余情况返回全新的不可变状态。
     */
    public GameState reduce(GameAction action) {
        if (action instanceof GameAction.Play play)
            return reducePlay(play.move());
        if (action instanceof GameAction.Reset reset)
            return new GameState(reset.position());
        if (action instanceof GameAction.Navigate navigate)
            return reduceNavigate(navigate);
        throw new IllegalArgumentException("未知 action: " + action);
    }

    private GameState reducePlay(Move move) {
        Position current = position();
        if (!current.isLegal(move))
            return this;

        List<Position> nextPositions = new ArrayList<>(positions.subList(0, cursor + 1));
        List<Move> nextMoves = new ArrayList<>(moves.subList(0, cursor));
        nextMoves.add(move);
        Position next = current.playLegal(move);
        nextPositions.add(next.withRepetitions(computeLastMoveRepetitions(nextPositions, next)));
        return new GameState(nextPositions, nextMoves, cursor + 1);
    }

    private GameState reduceNavigate(GameAction.Navigate action) {
        int nextCursor = switch (action) {
            case PREVIOUS -> cursor == 0 ? cursor : cursor - 1;
            case NEXT -> cursor == moves.size() ? cursor : cursor + 1;
            case FIRST -> 0;
            case LAST -> moves.size();
        };
        return nextCursor == cursor ? this : new GameState(positions, moves, nextCursor);
    }

    /**
     * 在尚未追加候选局面时，计算它与同侧历史局面的重复次数。
     */
    private static int computeLastMoveRepetitions(List<Position> history, Position last) {
        if (last.rule60Ply() < 4)
            return 0;
        for (int index = history.size() - 4; index >= 0; index -= 2) {
            Position prior = history.get(index);
            if (prior.sameBoard(last))
                return 1 + prior.repetitions();
            if (prior.rule60Ply() < 2)
                return 0;
        }
        return 0;
    }

    public boolean isOver() {
        return gameStatus() != GameStatus.ONGOING;
    }

    public GameStatus gameStatus() {
        if (!position().legalMoves().isEmpty())
            return GameStatus.ONGOING;
        return position().isInCheck() ? GameStatus.CHECKMATE : GameStatus.STALEMATE;
    }

    /**
     * 基础棋规结果：只判断将死与困毙，不涉及重复、长将长捉或 60 回合。
     */
    public GameResult computeSimpleGameResult() {
        if (!isOver())
            return GameResult.UNDECIDED;
        return position().sideToMove() == Side.RED ? GameResult.BLACK_WON : GameResult.RED_WON;
    }

    /**
     * 完整比赛裁决：三次重复时进入长将长捉判定，并处理 60 回合和子力和棋。
     */
    public GameResult computeGameResult() {
        GameResult simple = computeSimpleGameResult();
        if (simple != GameResult.UNDECIDED)
            return simple;
        Position last = position();
        if (last.rule60Ply() >= 120 || !hasMatingMaterial())
            return GameResult.DRAW;
        if (last.repetitions() >= 2)
            return ruleJudge();
        return GameResult.UNDECIDED;
    }

    private boolean hasMatingMaterial() {
        Board board = position().board();
        if (!board.pawns().isEmpty() || !board.rooks().isEmpty() || !board.knights().isEmpty())
            return true;
        DrawLevel level = matingDrawLevel(board);
        if (level == DrawLevel.NONE)
            return true;
        if (level == DrawLevel.DRAW)
            return false;
        for (Move move : position().legalMoves())
            if (position().play(move).legalMoves().isEmpty())
                return true;
        return false;
    }

    private GameResult ruleJudge() {
        int length = cursor + 1;
        if (length < 3)
            return GameResult.DRAW;
        Board last = positions.get(length - 1).board();
        Board previous = positions.get(length - 2).board();
        Board beforePrevious = positions.get(length - 3).board();
        boolean checkThem = last.underCheck();
        boolean checkUs = previous.underCheck();
        long chaseThem = last.themChased() & ~previous.usChased();
        long chaseUs = previous.themChased() & ~beforePrevious.usChased();

        for (int index = length - 3; ; index -= 2) {
            Board board = positions.get(index).board();
            if (board.underCheck()) {
                chaseThem = 0;
                chaseUs = 0;
            } else
                checkThem = false;
            if (board.sameState(last) && positions.get(index).repetitions() == 0) {
                GameResult relative;
                if (checkThem || checkUs)
                    relative = !checkUs ? GameResult.BLACK_WON : !checkThem ? GameResult.RED_WON : GameResult.DRAW;
                else if (chaseThem != 0 || chaseUs != 0)
                    relative = chaseUs == 0 ? GameResult.BLACK_WON : chaseThem == 0 ? GameResult.RED_WON : GameResult.DRAW;
                else
                    relative = GameResult.DRAW;
                return position().sideToMove() == Side.BLACK ? relative : relative.negate();
            }
            if (index < 2)
                return GameResult.DRAW;
            Board oneEarlier = positions.get(index - 1).board();
            if (oneEarlier.underCheck()) {
                chaseThem = 0;
                chaseUs = 0;
            } else
                checkUs = false;
            chaseThem &= board.themChased() & ~oneEarlier.usChased();
            chaseUs &= oneEarlier.themChased() & ~positions.get(index - 2).board().usChased();
        }
    }

    private enum DrawLevel {NONE, DRAW, MATE}

    private static DrawLevel matingDrawLevel(Board board) {
        if (board.cannons().isEmpty())
            return DrawLevel.DRAW;
        if (board.cannons().count() == 1) {
            Board.BitBoard cannonSide = board.cannons().and(board.ours()).isEmpty() ? board.theirs() : board.ours();
            Board.BitBoard otherSide = cannonSide == board.ours() ? board.theirs() : board.ours();
            int cannonAdvisors = board.advisors().and(cannonSide).count();
            int otherAdvisors = board.advisors().and(otherSide).count();
            if (cannonAdvisors == 0) {
                if (otherAdvisors == 0)
                    return DrawLevel.DRAW;
                if (otherAdvisors == 1)
                    return board.bishops().and(cannonSide).isEmpty() ? DrawLevel.DRAW : DrawLevel.MATE;
                if (board.bishops().and(cannonSide).isEmpty())
                    return DrawLevel.MATE;
            }
        }
        if (board.cannons().and(board.ours()).count() == 1 && board.cannons().and(board.theirs()).count() == 1
            && board.advisors().isEmpty())
            return board.bishops().isEmpty() ? DrawLevel.DRAW : DrawLevel.MATE;
        return DrawLevel.NONE;
    }
}
