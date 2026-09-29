package com.yk.xiangqi.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 当前搜索已合并的 UCI {@code info} 分析信息。 */
public final class ThinkingInfo {
    /** UCI {@code wdl} 的胜、和、负三个计数。 */
    public static final class Wdl {
        public final long win;
        public final long draw;
        public final long loss;

        private Wdl(long win, long draw, long loss) {
            this.win = win;
            this.draw = draw;
            this.loss = loss;
        }
    }

    public final Integer score;
    public final Integer mate;
    public final Integer depth;
    public final Integer selDepth;
    public final Long timeMs;
    public final Long nodes;
    public final Long nps;
    public final Long eps;
    public final Wdl wdl;
    public final List<String> pv;
    public final String comment;

    private ThinkingInfo(Integer score, Integer mate, Integer depth, Integer selDepth, Long timeMs, Long nodes, Long nps,
        Long eps, Wdl wdl, List<String> pv, String comment) {
        this.score = score;
        this.mate = mate;
        this.depth = depth;
        this.selDepth = selDepth;
        this.timeMs = timeMs;
        this.nodes = nodes;
        this.nps = nps;
        this.eps = eps;
        this.wdl = wdl;
        this.pv = pv == null ? Collections.emptyList() : Collections.unmodifiableList(pv);
        this.comment = comment;
    }

    /** 解析一行独立的 UCI info。 */
    public static ThinkingInfo parse(String line) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length == 0 || !"info".equals(parts[0]))
            return null;

        Integer depth = integer(parts, "depth");
        Integer selDepth = integer(parts, "seldepth");
        Integer score = null;
        Integer mate = null;
        Long time = number(parts, "time");
        Long nodes = number(parts, "nodes");
        Long nps = number(parts, "nps");
        Long eps = number(parts, "eps");
        for (int i = 0; i + 1 < parts.length; i++) {
            if (!"score".equals(parts[i]))
                continue;
            if ("mate".equals(parts[i + 1]) && i + 2 < parts.length)
                mate = integer(parts[i + 2]);
            else if ("cp".equals(parts[i + 1]) && i + 2 < parts.length)
                score = integer(parts[i + 2]);
            else
                score = integer(parts[i + 1]);
        }

        Wdl wdl = wdl(parts);
        List<String> pv = pv(parts);
        String comment = comment(parts);
        return new ThinkingInfo(score, mate, depth, selDepth, time, nodes, nps, eps, wdl, pv, comment);
    }

    private static Wdl wdl(String[] parts) {
        for (int i = 0; i + 3 < parts.length; i++) {
            if (!"wdl".equals(parts[i]))
                continue;
            Long win = number(parts[i + 1]);
            Long draw = number(parts[i + 2]);
            Long loss = number(parts[i + 3]);
            if (win != null && draw != null && loss != null)
                return new Wdl(win, draw, loss);
        }
        return null;
    }

    private static List<String> pv(String[] parts) {
        for (int i = 0; i < parts.length; i++) {
            if ("pv".equals(parts[i]))
                return new ArrayList<>(Arrays.asList(parts).subList(i + 1, parts.length));
        }
        return Collections.emptyList();
    }

    private static String comment(String[] parts) {
        for (int i = 0; i < parts.length; i++) {
            if ("string".equals(parts[i]))
                return String.join(" ", Arrays.asList(parts).subList(i + 1, parts.length));
        }
        return null;
    }

    private static Integer integer(String[] parts, String name) {
        Long value = number(parts, name);
        return value == null ? null : value.intValue();
    }

    private static Integer integer(String value) {
        Long number = number(value);
        return number == null ? null : number.intValue();
    }

    private static Long number(String[] parts, String name) {
        for (int i = 0; i + 1 < parts.length; i++) {
            if (name.equals(parts[i]))
                return number(parts[i + 1]);
        }
        return null;
    }

    private static Long number(String value) {
        try {
            int end = value.length();
            if (end > 0 && (value.charAt(end - 1) == ',' || value.charAt(end - 1) == ';'))
                end--;
            return Long.parseLong(value, 0, end, 10);
        } catch (Exception ignored) {
            return null;
        }
    }

}
