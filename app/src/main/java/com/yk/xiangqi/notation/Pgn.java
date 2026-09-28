package com.yk.xiangqi.notation;

import com.yk.xiangqi.core.GameAction;
import com.yk.xiangqi.core.GameState;
import com.yk.xiangqi.core.Move;
import com.yk.xiangqi.core.Position;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** PGN 容器与 ICCS 坐标着法之间的纯文本转换。 */
public final class Pgn {
    private Pgn() {
    }

    /** 读取标签、ICCS 坐标着法、结果及落子后的 {@code {}} 注释。 */
    public static PgnGame parse(String source) {
        if (source == null)
            throw new IllegalArgumentException("PGN 不能为空");
        Reader reader = new Reader(source);
        Map<String, String> tags = reader.tags();
        String format = tags.get("Format");
        if (format != null && !"ICCS".equalsIgnoreCase(format))
            throw new IllegalArgumentException("仅支持 ICCS 坐标棋谱");

        Position initial = initialPosition(tags);
        GameState game = new GameState(initial);
        List<String> comments = new ArrayList<>();
        String result = null;
        while (true) {
            reader.skipWhitespace();
            if (reader.done())
                break;
            if (reader.current() == '{') {
                if (result != null)
                    throw reader.error("结果之后不能有注释");
                if (comments.isEmpty())
                    throw reader.error("注释必须跟在一着之后");
                int last = comments.size() - 1;
                String old = comments.get(last);
                String comment = reader.comment();
                comments.set(last, old == null ? comment : old + '\n' + comment);
                continue;
            }

            String token = reader.token();
            if (moveNumber(token) || nag(token))
                continue;
            if (isResult(token)) {
                if (result != null)
                    throw reader.error("结果重复出现");
                result = token;
                continue;
            }
            if (result != null)
                throw reader.error("结果之后不能再有着法");

            Move move = iccsMove(token);
            GameState next = game.reduce(new GameAction.Play(move));
            if (next == game)
                throw reader.error("第 " + (comments.size() + 1) + " 着非法: " + token);
            game = next;
            comments.add(null);
        }

        String tagResult = tags.get("Result");
        if (result == null)
            result = tagResult == null ? "*" : result(tagResult);
        else if (tagResult != null && !result.equals(result(tagResult)))
            throw new IllegalArgumentException("标签 Result 与着法区结果不一致");
        return PgnGame.parsed(game, tags, comments, result);
    }

    /**
     * 自定义 FEN 必须声明 SetUp "1"。为兼容常见导出器，标准初始 FEN 可以省略 SetUp。
     */
    private static Position initialPosition(Map<String, String> tags) {
        String setup = tags.get("SetUp");
        if (setup != null && !"0".equals(setup) && !"1".equals(setup))
            throw new IllegalArgumentException("SetUp 只能是 0 或 1");
        String fen = tags.get("FEN");
        if (fen == null) {
            if ("1".equals(setup))
                throw new IllegalArgumentException("SetUp 为 1 时必须提供 FEN");
            return Position.start();
        }

        Position initial = Position.fromFen(fen);
        if (!Position.START_FEN.equals(initial.toFen()) && !"1".equals(setup))
            throw new IllegalArgumentException("自定义 FEN 必须使用 SetUp \"1\"");
        return initial;
    }

    /** 将棋谱导出为带大写、连字符 ICCS 坐标的 PGN。 */
    public static String write(PgnGame pgn) {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, String> tag : pgn.tags().entrySet())
            text.append('[').append(tag.getKey()).append(" \"").append(escape(tag.getValue())).append("\"]\n");
        text.append('\n');

        List<Move> moves = pgn.game().moves();
        List<String> comments = pgn.comments();
        for (int index = 0; index < moves.size(); index++) {
            if (index > 0)
                text.append(index % 2 == 0 ? '\n' : ' ');
            if (index % 2 == 0)
                text.append(index / 2 + 1).append(". ");
            text.append(iccs(moves.get(index)));
            String comment = comments.get(index);
            if (comment != null)
                text.append(" {").append(comment).append('}');
        }
        if (!moves.isEmpty())
            text.append('\n');
        return text.append(pgn.result()).append('\n').toString();
    }

    static String result(String value) {
        if (!isResult(value))
            throw new IllegalArgumentException("无效的 PGN 结果: " + value);
        return value;
    }

    static String comment(String value) {
        if (value == null)
            return null;
        if (value.indexOf('}') >= 0)
            throw new IllegalArgumentException("PGN 注释不能包含 } ");
        return value;
    }

    static String tagName(String value) {
        if (value == null || value.isEmpty())
            throw new IllegalArgumentException("PGN 标签名不能为空");
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            if (!(c >= 'A' && c <= 'Z') && !(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9') && c != '_')
                throw new IllegalArgumentException("无效的 PGN 标签名: " + value);
        }
        return value;
    }

    private static boolean isResult(String value) {
        return "1-0".equals(value) || "0-1".equals(value) || "1/2-1/2".equals(value) || "*".equals(value);
    }

    private static boolean moveNumber(String token) {
        int index = 0;
        while (index < token.length() && token.charAt(index) >= '0' && token.charAt(index) <= '9')
            index++;
        if (index == 0 || index == token.length())
            return false;
        while (index < token.length() && token.charAt(index) == '.')
            index++;
        return index == token.length();
    }

    private static boolean nag(String token) {
        if (token.length() < 2 || token.charAt(0) != '$')
            return false;
        for (int index = 1; index < token.length(); index++)
            if (token.charAt(index) < '0' || token.charAt(index) > '9')
                return false;
        return true;
    }

    /** ICCS 使用 A0-A1；内部 core 始终使用小写无连字符的 UCI 坐标。 */
    private static Move iccsMove(String token) {
        if (token.length() == 5 && token.charAt(2) == '-')
            token = token.substring(0, 2) + token.substring(3);
        if (token.length() != 4)
            throw new IllegalArgumentException("无效的 ICCS 着法: " + token);
        char[] coordinate = token.toCharArray();
        coordinate[0] = Character.toLowerCase(coordinate[0]);
        coordinate[2] = Character.toLowerCase(coordinate[2]);
        return Move.parse(new String(coordinate));
    }

    private static String iccs(Move move) {
        String uci = move.uci();
        return ("" + Character.toUpperCase(uci.charAt(0)) + uci.charAt(1) + '-' + Character.toUpperCase(uci.charAt(2)) + uci.charAt(3));
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static final class Reader {
        private final String source;
        private int index;

        Reader(String source) {
            this.source = source;
        }

        Map<String, String> tags() {
            LinkedHashMap<String, String> result = new LinkedHashMap<>();
            skipWhitespace();
            while (!done() && current() == '[') {
                index++;
                skipWhitespace();
                int start = index;
                while (!done() && tagCharacter(current()))
                    index++;
                String name = tagName(source.substring(start, index));
                skipWhitespace();
                if (done() || current() != '\"')
                    throw error("标签值必须使用双引号");
                index++;
                String value = quoted();
                skipWhitespace();
                if (done() || current() != ']')
                    throw error("标签缺少 ]");
                index++;
                if (result.put(name, value) != null)
                    throw error("标签重复: " + name);
                skipWhitespace();
            }
            return result;
        }

        String quoted() {
            StringBuilder value = new StringBuilder();
            while (!done()) {
                char c = source.charAt(index++);
                if (c == '\"')
                    return value.toString();
                if (c == '\\') {
                    if (done())
                        throw error("标签转义不完整");
                    value.append(source.charAt(index++));
                } else
                    value.append(c);
            }
            throw error("标签字符串未结束");
        }

        String comment() {
            index++;
            int start = index;
            while (!done() && current() != '}')
                index++;
            if (done())
                throw error("注释未结束");
            String value = source.substring(start, index);
            index++;
            return value;
        }

        String token() {
            int start = index;
            while (!done() && !Character.isWhitespace(current()) && current() != '{' && current() != '}')
                index++;
            if (start == index)
                throw error("无法识别的 PGN 字符");
            return source.substring(start, index);
        }

        void skipWhitespace() {
            while (!done() && Character.isWhitespace(current()))
                index++;
        }

        boolean done() {
            return index == source.length();
        }

        char current() {
            return source.charAt(index);
        }

        IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + "（位置 " + index + "）");
        }

        private static boolean tagCharacter(char c) {
            return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
        }
    }
}
