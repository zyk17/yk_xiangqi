package com.yk.xiangqi;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.*;

/**
 * 应用可持久化配置。引擎和书库都以“配置列表 + 当前 id”管理。
 */
public final class Settings {
    public static final String BUNDLED_ENGINE_ID = "pikafish";

    public static final class Engine {
        public final String id, name, directory;
        public final int threads, hashMb;
        public final Map<String, String> options;

        public Engine(String id, String name, String directory, int threads, int hashMb, Map<String, String> options) {
            if (id == null || id.isEmpty() || name == null || name.isEmpty() || threads <= 0 || hashMb <= 0)
                throw new IllegalArgumentException("无效的引擎配置");
            this.id = id;
            this.name = name;
            this.directory = directory;
            this.threads = threads;
            this.hashMb = hashMb;
            this.options = Collections.unmodifiableMap(new LinkedHashMap<>(options));
        }
    }

    public static final class Book {
        public enum Order {MAX_SCORE, RANDOM}

        public final String id, name, path;
        public final boolean enabled;
        public final Order order;

        public Book(String id, String name, String path, boolean enabled, Order order) {
            if (id == null || id.isEmpty() || name == null || name.isEmpty() || path == null || path.isEmpty() || order == null)
                throw new IllegalArgumentException("无效的书库配置");
            this.id = id;
            this.name = name;
            this.path = path;
            this.enabled = enabled;
            this.order = order;
        }
    }

    public static final class Link {
        /** 最后一张投屏帧到达后，等待多久再识别，避免动画中间态。 */
        public final long frameSettleMs;
        /** 一次无障碍点击按下的持续时间。 */
        public final long tapDurationMs;
        /** 第一次点击完成后，第二次点击前的等待时间。 */
        public final long tapIntervalMs;
        /** ONNX Runtime 单次推理可使用的 CPU 线程数。 */
        public final int modelThreads;

        public Link(long frameSettleMs, long tapDurationMs, long tapIntervalMs, int modelThreads) {
            if (frameSettleMs < 0 || tapDurationMs < 1 || tapDurationMs > 100
                    || tapIntervalMs < 0 || tapIntervalMs > 500
                    || modelThreads < 1 || modelThreads > 8)
                throw new IllegalArgumentException("无效的连线参数");
            this.frameSettleMs = frameSettleMs;
            this.tapDurationMs = tapDurationMs;
            this.tapIntervalMs = tapIntervalMs;
            this.modelThreads = modelThreads;
        }
    }

    private static final String NAME = "settings", ENGINE_IDS = "engine.ids", ACTIVE_ENGINE = "engine.active", BOOK_IDS = "book.ids", ACTIVE_BOOK = "book.active";
    private final SharedPreferences preferences;

    public Settings(Context context) {
        preferences = context.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public List<Engine> engines() {
        List<Engine> out = new ArrayList<>();
        out.add(readEngine(BUNDLED_ENGINE_ID));
        for (String id : ids(ENGINE_IDS)) out.add(readEngine(id));
        return Collections.unmodifiableList(out);
    }

    public Engine activeEngine() {
        String id = preferences.getString(ACTIVE_ENGINE, BUNDLED_ENGINE_ID);
        for (Engine value : engines()) if (value.id.equals(id)) return value;
        return engines().get(0);
    }

    public void saveEngine(Engine value, boolean active) {
        Set<String> ids = ids(ENGINE_IDS);
        if (!BUNDLED_ENGINE_ID.equals(value.id)) ids.add(value.id);
        String p = "engine." + value.id + ".";
        SharedPreferences.Editor e = preferences.edit().putStringSet(ENGINE_IDS, ids).putString(p + "name", value.name).putString(p + "directory", value.directory).putInt(p + "threads", value.threads).putInt(p + "hash", value.hashMb).putStringSet(p + "optionNames", new LinkedHashSet<>(value.options.keySet())).remove(p + "multiPv").remove(p + "option.MultiPV");
        for (Map.Entry<String, String> option : value.options.entrySet()) e.putString(p + "option." + option.getKey(), option.getValue());
        if (active) e.putString(ACTIVE_ENGINE, value.id);
        e.apply();
    }

    public void selectEngine(String id) {
        for (Engine value : engines()) {
            if (value.id.equals(id)) {
                preferences.edit().putString(ACTIVE_ENGINE, id).apply();
                return;
            }
        }
        throw new IllegalArgumentException("未知引擎配置");
    }

    /** 删除自定义引擎配置；内置 Pikafish 始终保留。 */
    public void removeEngine(String id) {
        if (BUNDLED_ENGINE_ID.equals(id))
            throw new IllegalArgumentException("不能删除内置引擎");
        Set<String> ids = ids(ENGINE_IDS);
        if (!ids.remove(id))
            throw new IllegalArgumentException("未知引擎配置");
        String p = "engine." + id + ".";
        SharedPreferences.Editor e = preferences.edit().putStringSet(ENGINE_IDS, ids)
            .remove(p + "name").remove(p + "directory").remove(p + "threads").remove(p + "hash")
            .remove(p + "multiPv").remove(p + "optionNames").remove(p + "option.MultiPV");
        if (id.equals(preferences.getString(ACTIVE_ENGINE, BUNDLED_ENGINE_ID)))
            e.putString(ACTIVE_ENGINE, BUNDLED_ENGINE_ID);
        e.apply();
    }

    public List<Book> books() {
        List<Book> out = new ArrayList<>();
        for (String id : ids(BOOK_IDS)) out.add(readBook(id));
        return Collections.unmodifiableList(out);
    }

    public Book activeBook() {
        String id = preferences.getString(ACTIVE_BOOK, null);
        if (id == null) return null;
        for (Book value : books()) if (value.id.equals(id)) return value;
        return null;
    }

    public void saveBook(Book value, boolean active) {
        Set<String> ids = ids(BOOK_IDS);
        ids.add(value.id);
        String p = "book." + value.id + ".";
        SharedPreferences.Editor e = preferences.edit().putStringSet(BOOK_IDS, ids).putString(p + "name", value.name).putString(p + "path", value.path).putBoolean(p + "enabled", value.enabled).putString(p + "order", value.order.name());
        if (active) e.putString(ACTIVE_BOOK, value.id);
        e.apply();
    }

    public void selectBook(String id) {
        for (Book value : books()) {
            if (value.id.equals(id)) {
                preferences.edit().putString(ACTIVE_BOOK, id).apply();
                return;
            }
        }
        throw new IllegalArgumentException("未知书库配置");
    }

    public void clearActiveBook() {
        preferences.edit().remove(ACTIVE_BOOK).apply();
    }

    /** 删除书库配置；书库文件由业务层在关闭连接后清理。 */
    public void removeBook(String id) {
        Set<String> ids = ids(BOOK_IDS);
        if (!ids.remove(id))
            throw new IllegalArgumentException("未知书库配置");
        String p = "book." + id + ".";
        SharedPreferences.Editor e = preferences.edit().putStringSet(BOOK_IDS, ids)
            .remove(p + "name").remove(p + "path").remove(p + "enabled").remove(p + "order");
        if (id.equals(preferences.getString(ACTIVE_BOOK, null)))
            e.remove(ACTIVE_BOOK);
        e.apply();
    }

    public Link link() {
        return new Link(preferences.getLong("link.frameSettleMs", 100),
                preferences.getLong("link.tapDurationMs", 30),
                preferences.getLong("link.tapIntervalMs", 50),
                preferences.getInt("link.modelThreads", 4));
    }

    public void setLink(Link value) {
        preferences.edit().putLong("link.frameSettleMs", value.frameSettleMs)
            .putLong("link.tapDurationMs", value.tapDurationMs)
            .putLong("link.tapIntervalMs", value.tapIntervalMs)
            .putInt("link.modelThreads", value.modelThreads)
            .remove("link.scanIntervalMs").remove("link.settleMs").remove("link.motionThreshold")
            .apply();
    }

    private Engine readEngine(String id) {
        String p = "engine." + id + ".";
        Map<String, String> options = new LinkedHashMap<>();
        for (String name : ids(p + "optionNames")) {
            String value = preferences.getString(p + "option." + name, null);
            if (value != null && !"MultiPV".equalsIgnoreCase(name)) options.put(name, value);
        }
        String name = preferences.getString(p + "name", BUNDLED_ENGINE_ID.equals(id) ? "Pikafish" : id);
        return new Engine(id, name, preferences.getString(p + "directory", null), preferences.getInt(p + "threads", 1), preferences.getInt(p + "hash", 64), options);
    }

    private Book readBook(String id) {
        String p = "book." + id + ".";
        Book.Order order;
        try {
            order = Book.Order.valueOf(preferences.getString(p + "order", Book.Order.MAX_SCORE.name()));
        } catch (IllegalArgumentException ignored) {
            order = Book.Order.MAX_SCORE;
        }
        return new Book(id, preferences.getString(p + "name", id), preferences.getString(p + "path", ""), preferences.getBoolean(p + "enabled", false), order);
    }

    private Set<String> ids(String key) {
        return new LinkedHashSet<>(preferences.getStringSet(key, Collections.emptySet()));
    }
}
