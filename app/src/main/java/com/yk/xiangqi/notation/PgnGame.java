package com.yk.xiangqi.notation;

import com.yk.xiangqi.core.GameState;
import com.yk.xiangqi.core.Position;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 一份 PGN 棋谱的业务表示。棋局仍由 core 的不可变 {@link GameState} 表示；
 * 标签、结果与逐手注释属于文本交换层。
 */
public final class PgnGame {
    private final GameState game;
    private final Map<String, String> tags;
    private final List<String> comments;
    private final String result;

    /** 创建没有逐手注释的棋谱。 */
    public PgnGame(GameState game, Map<String, String> tags, String result) {
        this(game, tags, Map.of(), result);
    }

    /**
     * 创建带可选逐手注释的棋谱。键是从 0 开始的 ply，未出现的键表示没有注释。
     */
    public PgnGame(GameState game, Map<String, String> tags, Map<Integer, String> comments, String result) {
        this(game, tags, expandComments(game, comments), result);
    }

    /** 解析器已天然持有与每手对齐的注释列表，不向外暴露 null 占位。 */
    static PgnGame parsed(GameState game, Map<String, String> tags, List<String> comments, String result) {
        return new PgnGame(game, tags, comments, result);
    }

    private PgnGame(GameState game, Map<String, String> tags, List<String> comments, String result) {
        this.game = Objects.requireNonNull(game, "game");
        this.result = Pgn.result(result);
        if (comments.size() != game.moves().size())
            throw new IllegalArgumentException("注释数量必须与当前主线着数一致");

        this.comments = Collections.unmodifiableList(new ArrayList<>(comments));
        for (String comment : this.comments)
            Pgn.comment(comment);
        this.tags = Collections.unmodifiableMap(normalizeTags(tags));
    }

    private static List<String> expandComments(GameState game, Map<Integer, String> source) {
        Objects.requireNonNull(game, "game");
        Objects.requireNonNull(source, "comments");
        List<String> result = new ArrayList<>(Collections.nCopies(game.moves().size(), null));
        for (Map.Entry<Integer, String> entry : source.entrySet()) {
            Integer ply = entry.getKey();
            if (ply == null || ply < 0 || ply >= result.size())
                throw new IllegalArgumentException("注释 ply 超出当前主线: " + ply);
            String comment = Objects.requireNonNull(entry.getValue(), "注释内容");
            Pgn.comment(comment);
            result.set(ply, comment);
        }
        return result;
    }

    private Map<String, String> normalizeTags(Map<String, String> source) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        if (source != null) {
            for (Map.Entry<String, String> tag : source.entrySet()) {
                String name = Pgn.tagName(tag.getKey());
                if (!"Result".equals(name) && !"Format".equals(name) && !"SetUp".equals(name) && !"FEN".equals(name))
                    result.put(name, Objects.requireNonNull(tag.getValue(), "标签值"));
            }
        }
        result.put("Result", this.result);
        result.put("Format", "ICCS");
        Position initial = game.initialPosition();
        if (!Position.START_FEN.equals(initial.toFen())) {
            result.put("SetUp", "1");
            result.put("FEN", initial.toFen());
        }
        return result;
    }

    public GameState game() {
        return game;
    }

    /** 当前主线逐手注释；没有注释的位置为 {@code null}。 */
    public List<String> comments() {
        return comments;
    }

    /** 保持读入顺序的 PGN 标签，其中 Result、Format、SetUp、FEN 由本对象维护。 */
    public Map<String, String> tags() {
        return tags;
    }

    public String result() {
        return result;
    }
}
