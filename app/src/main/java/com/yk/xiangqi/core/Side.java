package com.yk.xiangqi.core;

public enum Side {
    RED,
    BLACK;

    public Side opposite() {
        return this == RED ? BLACK : RED;
    }

    public char fen() {
        return this == RED ? 'w' : 'b';
    }
}
