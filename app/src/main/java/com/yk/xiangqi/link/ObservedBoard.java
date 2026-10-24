package com.yk.xiangqi.link;

import com.yk.xiangqi.core.Board;
import com.yk.xiangqi.core.PieceType;
import com.yk.xiangqi.core.Position;
import com.yk.xiangqi.core.Side;

/**
 * 模型一次识别出的 90 格、15 类紧凑棋盘。
 */
public final class ObservedBoard {
    public static final byte EMPTY = 0;
    public static final int LABELS = 15;
    private final byte[] cells;

    public ObservedBoard(byte[] cells) {
        if (cells == null || cells.length != Board.SQUARES)
            throw new IllegalArgumentException("观测棋盘必须恰有 90 格");
        this.cells = cells.clone();
        for (byte value : this.cells)
            if (value < 0 || value >= LABELS)
                throw new IllegalArgumentException("模型标签无效");
    }

    /**
     * 将模型的 [90, 15] logits 直接归约为每格一个标签。
     */
    public static ObservedBoard fromLogits(float[] logits) {
        if (logits == null || logits.length != Board.SQUARES * LABELS)
            throw new IllegalArgumentException("模型输出尺寸无效");
        byte[] cells = new byte[Board.SQUARES];
        for (int square = 0; square < Board.SQUARES; square++) {
            int offset = square * LABELS;
            int best = 0;
            for (int label = 1; label < LABELS; label++)
                if (logits[offset + label] > logits[offset + best])
                    best = label;
            cells[square] = (byte) best;
        }
        return new ObservedBoard(cells);
    }

    public byte at(int square) {
        return cells[square];
    }

    /** 当前格的空、红、黑三态，仅供连线比较的热路径使用。 */
    int structureAt(int square) {
        return structure(cells[square]);
    }

    public static ObservedBoard from(Position position) {
        byte[] cells = new byte[Board.SQUARES];
        position.forEachPiece((square, side, type) -> cells[square] = label(side, type));
        return new ObservedBoard(cells);
    }

    /**
     * 将屏幕左上至右下的标签重排为核心固定的红方坐标。
     */
    public ObservedBoard orientForBottom(Side bottomSide) {
        byte[] result = new byte[Board.SQUARES];
        for (int row = 0; row < Board.RANKS; row++)
            for (int column = 0; column < Board.FILES; column++) {
                int targetFile = bottomSide == Side.RED ? column : Board.FILES - 1 - column;
                int targetRank = bottomSide == Side.RED ? Board.RANKS - 1 - row : row;
                result[Board.square(targetFile, targetRank)] = cells[Board.square(column, row)];
            }
        return new ObservedBoard(result);
    }

    /**
     * 忽略具体兵种，只比较空格、红子、黑子三态。
     */
    public int structureDifference(ObservedBoard other) {
        int difference = 0;
        for (int square = 0; square < Board.SQUARES; square++)
            if (structure(cells[square]) != structure(other.cells[square]))
                difference++;
        return difference;
    }

    /**
     * 只有恰好一个红帅和一个黑将，且双方将帅都在九宫、分处两端时，才能安全重建规则局面。
     * 屏幕坐标下九宫为第 3–5 列、第 0–2 行或第 7–9 行。
     */
    public boolean canBuildPosition() {
        int redKing = -1;
        int blackKing = -1;
        for (int square = 0; square < Board.SQUARES; square++) {
            byte label = cells[square];
            if (label == 1) {
                if (redKing >= 0)
                    return false;
                redKing = square;
            } else if (label == 8) {
                if (blackKing >= 0)
                    return false;
                blackKing = square;
            }
        }
        if (redKing < 0 || blackKing < 0)
            return false;
        if (!inPalace(redKing) || !inPalace(blackKing))
            return false;
        // 将帅必须分属上下九宫，不能挤在同一端。
        return Board.rank(redKing) / 5 != Board.rank(blackKing) / 5;
    }

    /** 仅在 {@link #canBuildPosition()} 成立后调用：屏幕下方九宫是红帅则红方在下，否则黑方在下。 */
    public Side bottomSide() {
        for (int row = 7; row < Board.RANKS; row++)
            for (int column = 3; column <= 5; column++)
                if (cells[Board.square(column, row)] == 1)
                    return Side.RED;
        return Side.BLACK;
    }

    private static boolean inPalace(int square) {
        int file = Board.file(square);
        int rank = Board.rank(square);
        return file >= 3 && file <= 5 && (rank <= 2 || rank >= 7);
    }

    /**
     * 将已按核心坐标排列的观测棋盘用于首次建盘或用户同步。
     */
    public Position toPosition(Side sideToMove) {
        Board board = new Board();
        for (int square = 0; square < Board.SQUARES; square++) {
            byte label = cells[square];
            if (label == EMPTY)
                continue;
            Side side = label <= 7 ? Side.RED : Side.BLACK;
            board.put(square, pieceType(label <= 7 ? label : label - 7), side == Side.BLACK);
        }
        return Position.fromBoard(board, sideToMove);
    }

    private static int structure(byte label) {
        return label == EMPTY ? 0 : label <= 7 ? 1 : 2;
    }

    private static byte label(Side side, PieceType type) {
        int kind = switch (type) {
            case KING -> 1;
            case ADVISOR -> 2;
            case BISHOP -> 3;
            case KNIGHT -> 4;
            case ROOK -> 5;
            case CANNON -> 6;
            case PAWN -> 7;
        };
        return (byte) (side == Side.RED ? kind : kind + 7);
    }

    private static PieceType pieceType(int label) {
        return switch (label) {
            case 1 -> PieceType.KING;
            case 2 -> PieceType.ADVISOR;
            case 3 -> PieceType.BISHOP;
            case 4 -> PieceType.KNIGHT;
            case 5 -> PieceType.ROOK;
            case 6 -> PieceType.CANNON;
            case 7 -> PieceType.PAWN;
            default -> throw new IllegalStateException("模型标签无效");
        };
    }
}
