package com.yk.xiangqi.core;

/**
 * 棋子的兵种；颜色由 {@link Board#ours()} 与 {@link Board#theirs()}
 * 的占位位板表示。
 */
public enum PieceType {
    ROOK('R'),
    KNIGHT('N'),
    BISHOP('B'),
    ADVISOR('A'),
    KING('K'),
    CANNON('C'),
    PAWN('P');

    public final char fen;

    PieceType(char fen) { this.fen = fen; }

    public static PieceType fromFen(char value) {
        return switch (Character.toUpperCase(value)) {
            case 'R' -> ROOK;
            case 'N' -> KNIGHT;
            case 'B' -> BISHOP;
            case 'A' -> ADVISOR;
            case 'K' -> KING;
            case 'C' -> CANNON;
            case 'P' -> PAWN;
            default -> throw new IllegalArgumentException("无效的棋子类型: " + value);
        };
    }
}
