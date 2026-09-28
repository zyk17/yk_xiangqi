package com.yk.xiangqi.core;

/**
 * UCI 坐标着法；纵线以红方的 0..9 视角表示。
 */
public final class Move {
    /** 0..89 的棋盘坐标；允许 Java 自动提升为 int 用作索引。 */
    public final byte from;
    public final byte to;

    public Move(int from, int to) {
        if (from < 0 || from >= Board.SQUARES || to < 0 || to >= Board.SQUARES)
            throw new IllegalArgumentException("无效的棋盘坐标着法: " + from + "," + to);
        this.from = (byte) from;
        this.to = (byte) to;
    }

    public static boolean isUciCoordinate(String value) {
        return value != null && value.length() == 4 && isFile(value.charAt(0)) && isRank(value.charAt(1)) && isFile(value.charAt(2))
            && isRank(value.charAt(3));
    }

    private static boolean isFile(char value) { return value >= 'a' && value <= 'i'; }

    private static boolean isRank(char value) { return value >= '0' && value <= '9'; }

    public static int square(char file, char rank) { return (rank - '0') * 9 + (file - 'a'); }

    public static String name(int sq) { return "" + (char) ('a' + sq % 9) + (char) ('0' + sq / 9); }

    public String uci() { return name(from) + name(to); }
    public static Move parse(String uci) {
        if (!isUciCoordinate(uci))
            throw new IllegalArgumentException("无效的 UCI 着法: " + uci);
        return new Move(square(uci.charAt(0), uci.charAt(1)), square(uci.charAt(2), uci.charAt(3)));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Move && from == ((Move) other).from && to == ((Move) other).to;
    }

    @Override
    public int hashCode() {
        return (from << 8) | to;
    }

    @Override
    public String toString() {
        return uci();
    }
}
