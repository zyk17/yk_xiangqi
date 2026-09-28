package com.yk.xiangqi.link;

import com.yk.xiangqi.core.Board;

/**
 * 外部屏幕棋盘的固定几何。框选端点是左上、右下交叉点中心，而不是外边框。
 */
public final class BoardGeometry {
    public static final int FILES = Board.FILES;
    public static final int RANKS = Board.RANKS;

    private final float left;
    private final float top;
    private final float stepX;
    private final float stepY;

    public BoardGeometry(float left, float top, float right, float bottom) {
        if (!(right > left) || !(bottom > top))
            throw new IllegalArgumentException("框选区域无效");
        stepX = (right - left) / (FILES - 1);
        stepY = (bottom - top) / (RANKS - 1);
        if (stepX < 4f || stepY < 4f)
            throw new IllegalArgumentException("棋盘格过小");
        this.left = left;
        this.top = top;
    }

    public float left() { return left; }
    public float top() { return top; }
    public float right() { return left + stepX * (FILES - 1); }
    public float bottom() { return top + stepY * (RANKS - 1); }
    public float stepX() { return stepX; }
    public float stepY() { return stepY; }

    /** 屏幕行列坐标的交叉点中心。行从上到下，列从左到右。 */
    public Point center(int column, int row) {
        if (column < 0 || column >= FILES || row < 0 || row >= RANKS)
            throw new IllegalArgumentException("棋盘坐标无效");
        return new Point(left + column * stepX, top + row * stepY);
    }

    /** 以交叉点为中心，向四周扩半格得到模型采样区域。 */
    public Rect cell(int column, int row) {
        Point point = center(column, row);
        return new Rect(point.x - stepX / 2f, point.y - stepY / 2f,
            point.x + stepX / 2f, point.y + stepY / 2f);
    }

    public record Point(float x, float y) {}
    public record Rect(float left, float top, float right, float bottom) {}
}
