package com.yk.xiangqi.engine;

import java.util.ArrayList;
import java.util.List;

/** UCI {@code go} 参数。字段为空或 false 时不写入命令。 */
public final class GoParams {
    public Long wtime;
    public Long btime;
    public Long winc;
    public Long binc;
    public Integer movesToGo;
    public Integer depth;
    public Integer mate;
    public Long nodes;
    public Long moveTime;
    public boolean infinite;
    public boolean ponder;
    public final List<String> searchMoves = new ArrayList<>();

    public static GoParams infinite(List<String> searchMoves) {
        GoParams params = new GoParams();
        params.infinite = true;
        if (searchMoves != null)
            params.searchMoves.addAll(searchMoves);
        return params;
    }

    /** 按 UCI 规定的 go 参数顺序组装命令。 */
    public String uci() {
        StringBuilder command = new StringBuilder("go");
        append(command, "wtime", wtime);
        append(command, "btime", btime);
        append(command, "winc", winc);
        append(command, "binc", binc);
        append(command, "movestogo", movesToGo);
        append(command, "depth", depth);
        append(command, "mate", mate);
        append(command, "nodes", nodes);
        append(command, "movetime", moveTime);
        if (!searchMoves.isEmpty())
            command.append(" searchmoves ").append(String.join(" ", searchMoves));
        if (ponder)
            command.append(" ponder");
        if (infinite)
            command.append(" infinite");
        return command.toString();
    }

    private static void append(StringBuilder command, String name, Number value) {
        if (value != null)
            command.append(' ').append(name).append(' ').append(value);
    }
}
