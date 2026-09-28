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
        public final int threads, hashMb, multiPv;
        public final Map<String, String> options;

        public Engine(String id, String name, String directory, int threads, int hashMb, int multiPv, Map<String, String> options) {
            if (id == null || id.isEmpty() || name == null || name.isEmpty() || threads <= 0 || hashMb <= 0 || multiPv <= 0)
                throw new IllegalArgumentException("无效的引擎配置");
            this.id = id;
            this.name = name;
            this.directory = directory;
            this.threads = threads;
            this.hashMb = hashMb;
            this.multiPv = multiPv;
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
        public final long settleMs, tapIntervalMs;
        public final float motionThreshold;

        public Link(long settleMs, float motionThreshold, long tapIntervalMs) {
            if (settleMs < 0 || !(motionThreshold >= 0 && motionThreshold <= 1) || tapIntervalMs < 0 || tapIntervalMs > 200)
                throw new IllegalArgumentException("无效的连线参数");
            this.settleMs = settleMs;
            this.motionThreshold = motionThreshold;
            this.tapIntervalMs = tapIntervalMs;
        }
    }

    private static final String NAME = "settings", ENGINE_IDS = "engine.ids", ACTIVE_ENGINE = "engine.active", BOOK_IDS = "book.ids", ACTIVE_BOOK = "book.active";
    private final SharedPreferences preferences;

    public Settings(Context context) {
        preferences = context.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public List<Engine> engines() {
        List<Engine> out = new ArrayList<>();
        out.add(new Engine(BUNDLED_ENGINE_ID, "Pikafish", null, 1, 64, 1, Collections.emptyMap()));
        for (String id : ids(ENGINE_IDS)) out.add(readEngine(id));
        return Collections.unmodifiableList(out);
    }

    public Engine activeEngine() {
        String id = preferences.getString(ACTIVE_ENGINE, BUNDLED_ENGINE_ID);
        for (Engine value : engines()) if (value.id.equals(id)) return value;
        return engines().get(0);
    }

    public void saveEngine(Engine value, boolean active) {
        if (BUNDLED_ENGINE_ID.equals(value.id)) throw new IllegalArgumentException("内置引擎不可覆盖");
        Set<String> ids = ids(ENGINE_IDS);
        ids.add(value.id);
        String p = "engine." + value.id + ".";
        SharedPreferences.Editor e = preferences.edit().putStringSet(ENGINE_IDS, ids).putString(p + "name", value.name).putString(p + "directory", value.directory).putInt(p + "threads", value.threads).putInt(p + "hash", value.hashMb).putInt(p + "multiPv", value.multiPv).putStringSet(p + "optionNames", new LinkedHashSet<>(value.options.keySet()));
        for (Map.Entry<String, String> option : value.options.entrySet()) e.putString(p + "option." + option.getKey(), option.getValue());
        if (active) e.putString(ACTIVE_ENGINE, value.id);
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

    public Link link() {
        return new Link(preferences.getLong("link.settleMs", 100), preferences.getFloat("link.motionThreshold", .02f), preferences.getLong("link.tapIntervalMs", 50));
    }

    public void setLink(Link value) {
        preferences.edit().putLong("link.settleMs", value.settleMs).putFloat("link.motionThreshold", value.motionThreshold).putLong("link.tapIntervalMs", value.tapIntervalMs).apply();
    }

    private Engine readEngine(String id) {
        String p = "engine." + id + ".";
        Map<String, String> options = new LinkedHashMap<>();
        for (String name : ids(p + "optionNames")) {
            String value = preferences.getString(p + "option." + name, null);
            if (value != null) options.put(name, value);
        }
        return new Engine(id, preferences.getString(p + "name", id), preferences.getString(p + "directory", null), preferences.getInt(p + "threads", 1), preferences.getInt(p + "hash", 64), preferences.getInt(p + "multiPv", 1), options);
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
