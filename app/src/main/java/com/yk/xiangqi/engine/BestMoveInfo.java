package com.yk.xiangqi.engine;

/** UCI {@code bestmove} 行；ponder 着可为空。 */
public final class BestMoveInfo {
    public final String bestMove;
    public final String ponderMove;

    private BestMoveInfo(String bestMove, String ponderMove) {
        this.bestMove = bestMove;
        this.ponderMove = ponderMove;
    }

    /** 解析已识别为 {@code bestmove} 的 UCI 行。 */
    public static BestMoveInfo parse(String line) {
        String[] parts = line.trim().split("\\s+");
        String ponder = parts.length >= 4 && "ponder".equals(parts[2]) ? parts[3] : null;
        return new BestMoveInfo(parts[1], ponder);
    }
}
