package com.yk.xiangqi;

import com.yk.xiangqi.core.GameAction;
import com.yk.xiangqi.core.GameState;
import com.yk.xiangqi.core.Move;
import com.yk.xiangqi.core.Side;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

/**
 * 应用进程内共享的运行期棋局。
 *
 * <p>{@link GameState} 依然是不可变纯状态；此类只在 UI、连线和引擎之间串行发布
 * action 的归约结果，并持有与棋局协作有关的 AI 开关。</p>
 */
public final class GameRuntime {
    public enum Origin {LOCAL, EXTERNAL}

    public interface Listener {
        void onGameChanged(GameState state, GameAction action, Origin origin);
    }

    public interface AiListener {
        void onAiChanged(Side side, boolean enabled);
    }

    /** 当前分析主变的轻量投影，供连线小棋盘绘制箭头，不属于核心棋局状态。 */
    public interface ArrowListener {
        void onArrowsChanged(List<Move> arrows);
    }

    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<AiListener> aiListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<ArrowListener> arrowListeners = new CopyOnWriteArrayList<>();
    private GameState state = new GameState();
    private List<Move> arrows = List.of();
    private boolean redAi;
    private boolean blackAi;

    public synchronized GameState state() {
        return state;
    }

    public boolean reduce(GameAction action, Origin origin) {
        GameState next;
        synchronized (this) {
            next = state.reduce(action);
            if (next == state)
                return false;
            state = next;
        }
        for (Listener listener : listeners)
            listener.onGameChanged(next, action, origin);
        return true;
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public synchronized boolean aiEnabled(Side side) {
        return side == Side.RED ? redAi : blackAi;
    }

    public void setAi(Side side, boolean enabled) {
        synchronized (this) {
            if ((side == Side.RED ? redAi : blackAi) == enabled)
                return;
            if (side == Side.RED)
                redAi = enabled;
            else
                blackAi = enabled;
        }
        for (AiListener listener : aiListeners)
            listener.onAiChanged(side, enabled);
    }

    public void addAiListener(AiListener listener) {
        aiListeners.add(listener);
    }

    public void removeAiListener(AiListener listener) {
        aiListeners.remove(listener);
    }

    public synchronized List<Move> arrows() {
        return arrows;
    }

    public void setArrows(List<Move> value) {
        List<Move> next = List.copyOf(value);
        synchronized (this) {
            if (arrows.equals(next))
                return;
            arrows = next;
        }
        for (ArrowListener listener : arrowListeners)
            listener.onArrowsChanged(next);
    }

    public void addArrowListener(ArrowListener listener) {
        arrowListeners.add(listener);
    }

    public void removeArrowListener(ArrowListener listener) {
        arrowListeners.remove(listener);
    }
}
