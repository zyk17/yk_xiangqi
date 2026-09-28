package com.yk.xiangqi.engine;

import java.util.ArrayList;
import java.util.List;

/** UCI 握手中引擎声明的一项配置。 */
public final class UciOption {
    public final String name;
    public final String type;
    public final String defaultValue;
    /** 仅 {@code spin} 使用；协议未声明时为 {@code null}。 */
    public final Long min;
    /** 仅 {@code spin} 使用；协议未声明时为 {@code null}。 */
    public final Long max;
    /** 仅 {@code combo} 使用；顺序与引擎声明一致。 */
    public final List<String> vars;

    private UciOption(String name, String type, String defaultValue, Long min, Long max, List<String> vars) {
        this.name = name;
        this.type = type;
        this.defaultValue = defaultValue;
        this.min = min;
        this.max = max;
        this.vars = List.copyOf(vars);
    }

    /** 解析一行标准 {@code option name ... type ...} 声明。 */
    static UciOption parse(String line) {
        String[] words = line.trim().split("\\s+");
        if (words.length < 5 || !"option".equals(words[0]) || !"name".equals(words[1]))
            return null;

        int type = indexOf(words, "type", 2);
        if (type < 3 || type == words.length - 1)
            return null;
        String name = join(words, 2, type);
        int values = type + 2;
        int value = indexOf(words, "default", values);
        String defaultValue = value < 0 ? null : joinUntilOptionKeyword(words, value + 1);
        Long min = numberAfter(words, "min", values);
        Long max = numberAfter(words, "max", values);
        return new UciOption(name, words[type + 1], defaultValue, min, max, vars(words, values));
    }

    /**
     * 按声明类型校验并构造一条 {@code setoption}。button 没有值，传入值会被忽略。
     */
    public String setOption(String value) {
        if ("button".equals(type))
            return UciEngine.setOption(name, null);
        if ("check".equals(type) && !"true".equals(value) && !"false".equals(value))
            throw new IllegalArgumentException(name + " 只能设置为 true 或 false");
        if ("spin".equals(type))
            checkSpin(value);
        if ("combo".equals(type) && !vars.isEmpty() && !vars.contains(value))
            throw new IllegalArgumentException(value + " 不是 " + name + " 的候选值");
        return UciEngine.setOption(name, value);
    }

    /** 引擎未声明、但仍需按 UCI 原样发送的兼容选项。 */
    static UciOption unknown(String name) { return new UciOption(name, "string", null, null, null, List.of()); }

    private void checkSpin(String value) {
        long number;
        try {
            number = Long.parseLong(value);
        } catch (Exception ignored) {
            throw new IllegalArgumentException(name + " 必须是整数");
        }
        if ((min != null && number < min) || (max != null && number > max))
            throw new IllegalArgumentException(name + " 必须在 " + min + " 到 " + max + " 之间");
    }

    private static int indexOf(String[] words, String target, int start) {
        for (int index = start; index < words.length; index++)
            if (target.equals(words[index]))
                return index;
        return -1;
    }

    private static String joinUntilOptionKeyword(String[] words, int start) {
        int end = words.length;
        for (int index = start; index < words.length; index++) {
            String word = words[index];
            if ("min".equals(word) || "max".equals(word) || "var".equals(word)) {
                end = index;
                break;
            }
        }
        return join(words, start, end);
    }

    private static Long numberAfter(String[] words, String marker, int start) {
        int index = indexOf(words, marker, start);
        if (index < 0 || index == words.length - 1)
            return null;
        try {
            return Long.valueOf(words[index + 1]);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static List<String> vars(String[] words, int start) {
        List<String> vars = new ArrayList<>();
        for (int index = start; index < words.length; index++) {
            if (!"var".equals(words[index]))
                continue;
            int end = indexOf(words, "var", index + 1);
            if (end < 0)
                end = words.length;
            if (index + 1 < end)
                vars.add(join(words, index + 1, end));
            index = end - 1;
        }
        return vars;
    }

    private static String join(String[] words, int start, int end) {
        StringBuilder value = new StringBuilder();
        for (int index = start; index < end; index++) {
            if (index != start)
                value.append(' ');
            value.append(words[index]);
        }
        return value.toString();
    }
}
