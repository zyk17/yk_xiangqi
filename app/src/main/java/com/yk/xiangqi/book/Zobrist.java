package com.yk.xiangqi.book;

import com.yk.xiangqi.core.PieceType;
import com.yk.xiangqi.core.Position;
import com.yk.xiangqi.core.Side;
import java.io.*;
import java.util.*;

/**
 * bhobk 所用 Zobrist 表及局面、着法转换；
 */
public final class Zobrist {
    private static final long PLAYER = 0xA0CE2AF90C452F58L;
    private static final int[] C90 = {0x33, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x3b, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49, 0x4a,
        0x4b, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5a, 0x5b, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6a, 0x6b, 0x73, 0x74, 0x75,
        0x76, 0x77, 0x78, 0x79, 0x7a, 0x7b, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89, 0x8a, 0x8b, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99,
        0x9a, 0x9b, 0xa3, 0xa4, 0xa5, 0xa6, 0xa7, 0xa8, 0xa9, 0xaa, 0xab, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xbb, 0xc3, 0xc4,
        0xc5, 0xc6, 0xc7, 0xc8, 0xc9, 0xca, 0xcb};
    private final long[] table;

    private Zobrist(long[] table) { this.table = table; }

    /**
     * 从调用方提供的文本流读取固定长度的 Zobrist 表。
     */
    public static Zobrist read(InputStream input) throws IOException { return new Zobrist(readTable(input)); }

    long key(Position position) { return computeKey(position, false); }

    long mirroredKey(Position position) { return computeKey(position, true); }

    private long computeKey(Position position, boolean mirror) {
        long[] z = {0};
        position.forEachPiece((square, side, type) -> {
            int file = square % 9;
            int rank = square / 9;
            int at = (mirror ? 8 - file : file) + (9 - rank) * 9;
            z[0] ^= table[index(side, type) * 256 + C90[at]];
        });
        return position.sideToMove() == Side.RED ? z[0] ^ PLAYER : z[0];
    }
    String move(int vmove, boolean mirror) {
        int a = (vmove >> 8) & 255, b = vmove & 255;
        if (mirror) {
            a = (a & ~15) | (14 - a % 16);
            b = (b & ~15) | (14 - b % 16);
        }
        return coordinate(a) + coordinate(b);
    }

    private String coordinate(int byteValue) {
        for (int i = 0; i < C90.length; i++)
            if (C90[i] == byteValue)
                return "" + (char) ('a' + i % 9) + (char) ('9' - i / 9);
        throw new IllegalArgumentException("无效的着法坐标: " + byteValue);
    }

    private int index(Side side, PieceType type) {
        int offset = side == Side.RED ? 0 : 7;
        return offset + switch (type) {
            case KING -> 0;
            case ADVISOR -> 1;
            case BISHOP -> 2;
            case KNIGHT -> 3;
            case ROOK -> 4;
            case CANNON -> 5;
            case PAWN -> 6;
        };
    }

    private static long[] readTable(InputStream input) throws IOException {
        long[] result = new long[3584];
        int index = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String value = line.trim();
                if (value.isEmpty())
                    continue;
                if (index == result.length)
                    throw new IOException("Zobrist 表数据过多");
                result[index++] = Long.parseUnsignedLong(value, 16);
            }
        }
        if (index != result.length)
            throw new IOException("Zobrist 表长度错误: " + index);
        return result;
    }
}
