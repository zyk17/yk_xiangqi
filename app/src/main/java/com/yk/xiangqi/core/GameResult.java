package com.yk.xiangqi.core;

/**
 * 绝对红黑结果，不受 Board 当前行棋方归一化视角影响。
 */
public enum GameResult {
    UNDECIDED,
    BLACK_WON,
    DRAW,
    RED_WON;

    public GameResult negate() {
        return switch (this) {
            case RED_WON -> BLACK_WON;
            case BLACK_WON -> RED_WON;
            default -> this;
        };
    }
}
