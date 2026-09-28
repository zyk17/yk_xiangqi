package com.yk.xiangqi.link;

import com.yk.xiangqi.core.Move;
import com.yk.xiangqi.core.Position;

/**
 * 连线循环交给 Android 业务层的最终动作。
 */
public record LinkAction(Kind kind, Move move, Position position) {
    public enum Kind {NONE, PLAY, RESET, DESYNCED}

    public static final LinkAction NONE = new LinkAction(Kind.NONE, null, null);
    public static final LinkAction DESYNCED = new LinkAction(Kind.DESYNCED, null, null);

    public static LinkAction play(Move move) {
        return new LinkAction(Kind.PLAY, move, null);
    }

    public static LinkAction reset(Position position) {
        return new LinkAction(Kind.RESET, null, position);
    }
}
