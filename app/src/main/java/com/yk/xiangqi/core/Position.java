package com.yk.xiangqi.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 公开的不可变局面；FEN 仅存在于导入、导出与 UCI 边界。
 */
public final class Position {
    public interface PieceVisitor {
        void accept(int square, Side side, PieceType type);
    }

    public static final String START_FEN = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1";
    private final Board board;
    private final int rule60Ply;
    private final int usCheck;
    private final int themCheck;
    private final int repetitions;
    private final int gamePly;

    private Position(Board board, int rule60Ply, int usCheck, int themCheck, int repetitions, int gamePly) {
        this.board = board;
        this.rule60Ply = rule60Ply;
        this.usCheck = usCheck;
        this.themCheck = themCheck;
        this.repetitions = repetitions;
        this.gamePly = gamePly;
    }

    public static Position start() {
        return fromFen(START_FEN);
    }

    /**
     * 从绝对坐标棋盘创建局面。调用方负责把红方放入 {@code ours}，黑方放入
     * {@code theirs}；该方法只在需要从识别结果同步局面时使用。
     */
    public static Position fromBoard(Board source, Side sideToMove) {
        if (source == null || sideToMove == null)
            throw new IllegalArgumentException("棋盘和行棋方不能为空");
        Board board = new Board(source);
        if (board.flipped())
            throw new IllegalArgumentException("同步棋盘必须使用红方规范坐标");
        if (board.ourKing() < 0 || board.theirKing() < 0)
            throw new IllegalArgumentException("同步局面必须包含双方将帅");
        board.initializeRuleIds();
        if (sideToMove == Side.BLACK)
            board.mirror();
        return new Position(board, 0, 0, 0, 0, 1);
    }

    public static Position fromFen(String fen) {
        String[] fields = fen.trim().split("\\s+");
        if (fields.length < 2 || !(fields[1].equals("w") || fields[1].equals("b")))
            throw new IllegalArgumentException("FEN side must be w or b");
        String[] rows = fields[0].split("/", -1);
        if (rows.length != 10)
            throw new IllegalArgumentException("FEN must have ten ranks");
        Board board = new Board();
        for (int visual = 0; visual < 10; visual++) {
            int file = 0;
            for (char c : rows[visual].toCharArray()) {
                if (c >= '1' && c <= '9') {
                    file += c - '0';
                    continue;
                }
                if (file >= 9 || "RNBAKCPrnbakcp".indexOf(c) < 0)
                    throw new IllegalArgumentException("bad FEN piece: " + c);
                board.put(Board.square(file++, 9 - visual), PieceType.fromFen(c), Character.isLowerCase(c));
            }
            if (file != 9)
                throw new IllegalArgumentException("FEN rank width");
        }
        if (board.ourKing() < 0 || board.theirKing() < 0)
            throw new IllegalArgumentException("both kings are required");
        int rule60Ply = fields.length > 4 ? parseNonNegative(fields[4], "rule-60 clock") : 0;
        int gamePly = fields.length > 5 ? parseNonNegative(fields[5], "总回合数") : 1;
        board.initializeRuleIds();
        if (fields[1].equals("b"))
            board.mirror();
        return new Position(board, rule60Ply, 0, 0, 0, gamePly);
    }

    private static int parseNonNegative(String value, String label) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0)
                throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("bad " + label + ": " + value, error);
        }
    }

    public Side sideToMove() {
        return board.flipped() ? Side.BLACK : Side.RED;
    }

    public Board board() {
        return new Board(board);
    }

    public void forEachPiece(PieceVisitor visitor) {
        Side oursSide = sideToMove();
        Side theirsSide = oursSide.opposite();
        for (int normalized : board.occupied()) {
            int absolute = board.flipped() ? Board.flipRank(normalized) : normalized;
            visitor.accept(absolute, board.theirs().contains(normalized) ? theirsSide : oursSide, board.pieceAt(normalized));
        }
    }

    public int rule60Ply() {
        return rule60Ply;
    }

    public int repetitions() {
        return repetitions;
    }

    public int gamePly() {
        return gamePly;
    }

    boolean sameBoard(Position other) {
        return board.sameState(other.board);
    }

    Position withRepetitions(int value) {
        return new Position(board, rule60Ply, usCheck, themCheck, value, gamePly);
    }

    public String toFen() {
        char[] cells = new char[Board.SQUARES];
        forEachPiece((square, side, type) -> cells[square] = side == Side.RED ? type.fen : Character.toLowerCase(type.fen));
        StringBuilder out = new StringBuilder();
        for (int rank = 9; rank >= 0; rank--) {
            if (rank < 9)
                out.append('/');
            int empty = 0;
            for (int file = 0; file < 9; file++) {
                char p = cells[Board.square(file, rank)];
                if (p == '\0')
                    empty++;
                else {
                    if (empty > 0) {
                        out.append(empty);
                        empty = 0;
                    }
                    out.append(p);
                }
            }
            if (empty > 0)
                out.append(empty);
        }
        int fullMove = (gamePly + (sideToMove() == Side.BLACK ? 1 : 2)) / 2;
        return out.append(sideToMove() == Side.RED ? " w - - " : " b - - ").append(rule60Ply).append(' ').append(fullMove).toString();
    }

    public List<Move> legalMoves() {
        List<Move> moves = board.legalMoves();
        if (!board.flipped())
            return moves;
        List<Move> result = new ArrayList<>(moves.size());
        for (Move move : moves) result.add(Board.flipRank(move));
        return result;
    }

    public boolean isInCheck() {
        return board.underCheck();
    }

    /**
     * 当前绝对坐标着法是否符合基础行棋规则。
     */
    public boolean isLegal(Move absoluteMove) {
        Move move = board.flipped() ? Board.flipRank(absoluteMove) : absoluteMove;
        return board.isLegal(move);
    }

    public Position play(Move absoluteMove) {
        if (!isLegal(absoluteMove))
            throw new IllegalArgumentException("illegal move: " + absoluteMove);
        return playLegal(absoluteMove);
    }

    /**
     * 走一着已由 {@link #isLegal(Move)} 验证的绝对坐标着法。
     * 仅供同包的不可变状态归约复用，避免重复检查。
     */
    Position playLegal(Move absoluteMove) {
        Move move = board.flipped() ? Board.flipRank(absoluteMove) : absoluteMove;
        Board next = new Board(board);
        boolean zeroing = next.apply(move);
        next.mirror();
        int nextUsCheck = themCheck;
        int nextThemCheck = usCheck;
        int nextRule60 = rule60Ply;
        if (!next.underCheck() || ++nextThemCheck <= 10) {
            if (nextUsCheck > 10 && board.underCheck())
                nextUsCheck++;
            else
                nextRule60++;
        }
        if (zeroing) {
            nextRule60 = 0;
            nextUsCheck = 0;
            nextThemCheck = 0;
        }
        return new Position(next, nextRule60, nextUsCheck, nextThemCheck, 0, gamePly + 1);
    }
}
