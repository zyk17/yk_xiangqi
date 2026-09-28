package com.yk.xiangqi.link;

import com.yk.xiangqi.core.Board;
import com.yk.xiangqi.core.Move;
import com.yk.xiangqi.core.Side;

/**
 * 从规范棋盘着法投影到外部屏幕的两个点击。
 */
public record GesturePlan(BoardGeometry.Point from, BoardGeometry.Point to, long intervalMs) {
    public static GesturePlan forMove(BoardGeometry geometry, Side bottomSide, Move move, long intervalMs) {
        return new GesturePlan(point(geometry, bottomSide, move.from), point(geometry, bottomSide, move.to), intervalMs);
    }

    private static BoardGeometry.Point point(BoardGeometry geometry, Side bottomSide, int square) {
        int file = Board.file(square);
        int rank = Board.rank(square);
        int column = bottomSide == Side.RED ? file : 8 - file;
        int row = bottomSide == Side.RED ? 9 - rank : rank;
        return geometry.center(column, row);
    }
}
