package com.yk.xiangqi.core;

/**
 * 改变棋局的唯一输入；所有 action 都由 {@link GameState#reduce(GameAction)}
 * 归约。
 */
public sealed interface GameAction permits GameAction.Play, GameAction.Reset, GameAction.Navigate {
    /**
     * 尝试在当前游标局面走一着棋。
     */
    record Play(Move move) implements GameAction {
        public Play {
            if (move == null)
                throw new IllegalArgumentException("move 不能为空");
        }
    }

    /**
     * 以一个已经校验过的局面开始新对局，丢弃旧历史。
     */
    record Reset(Position position) implements GameAction {
        public Reset {
            if (position == null)
                throw new IllegalArgumentException("position 不能为空");
        }
    }

    /**
     * 不改变棋局内容，只移动历史游标。
     */
    enum Navigate implements GameAction {PREVIOUS, NEXT, FIRST, LAST}
}
