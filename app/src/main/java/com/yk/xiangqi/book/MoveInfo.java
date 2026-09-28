package com.yk.xiangqi.book;

public final class MoveInfo {
    public final String move;
    public final int score, win, draw, loss;

    public MoveInfo(String move, int score, int win, int draw, int loss) {
        this.move = move;
        this.score = score;
        this.win = win;
        this.draw = draw;
        this.loss = loss;
    }

    public int games() {
        return win + draw + loss;
    }

    public double winRate() {
        return games() == 0 ? 0d : (win + draw * .5d) / games();
    }
}
