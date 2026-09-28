package com.yk.xiangqi.bridge;

import com.yk.xiangqi.core.PieceType;
import com.yk.xiangqi.core.Position;
import com.yk.xiangqi.core.Side;

/** 将纯局面投影为 Compose 棋盘所需的绝对坐标字符数组。 */
public final class BoardProjection {
    private BoardProjection() {}

    public static char[] characters(Position position) {
        char[] result = new char[90];
        java.util.Arrays.fill(result, '.');
        position.forEachPiece((square, side, type) -> result[square] = character(side, type));
        return result;
    }

    private static char character(Side side, PieceType type) {
        char value = type.fen;
        return side == Side.RED ? value : Character.toLowerCase(value);
    }
}
