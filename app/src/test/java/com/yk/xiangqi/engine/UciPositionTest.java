package com.yk.xiangqi.engine;

import static org.junit.Assert.assertEquals;

import com.yk.xiangqi.core.GameAction;
import com.yk.xiangqi.core.GameState;
import com.yk.xiangqi.core.Move;
import com.yk.xiangqi.core.Position;
import org.junit.Test;

public final class UciPositionTest {
    @Test
    public void writesRootFenAndMovesBeforeCurrentCursor() {
        Position initial = Position.start();
        Move first = initial.legalMoves().get(0);
        GameState game = new GameState(initial).reduce(new GameAction.Play(first));
        Move second = game.position().legalMoves().get(0);
        game = game.reduce(new GameAction.Play(second));

        assertEquals("position fen " + initial.toFen() + " moves " + first.uci() + " " + second.uci(),
            UciEngine.position(game));
        assertEquals("position fen " + initial.toFen() + " moves " + first.uci(),
            UciEngine.position(game.reduce(GameAction.Navigate.PREVIOUS)));
    }
}
