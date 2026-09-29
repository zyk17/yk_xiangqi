package com.yk.xiangqi.book;

public record MoveInfo(String move, int score, int win, int draw, int loss) {

    public int games() {
        return win + draw + loss;
    }

    public double winRate() {
        return games() == 0 ? 0d : (win + draw * .5d) / games();
    }
}
