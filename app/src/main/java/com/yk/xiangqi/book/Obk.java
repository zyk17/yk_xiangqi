package com.yk.xiangqi.book;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;

import com.yk.xiangqi.core.Position;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 一个已打开的只读 bhobk 开局库。
 *
 * <p>页面持有并关闭本对象。旁路索引无效时自动退回原库；原库无法打开或结构不符时，
 * 通过 {@link ObkException} 报告给页面。</p>
 */
public final class Obk implements AutoCloseable {
    private static final String BOOK_COLUMNS = "vmove,vscore,vwin,vdraw,vlost";
    private static final String VALID_MOVE = " AND vvalid=1";

    public static final class OptimizeResult {
        public final int indexedRows;
        public final int skippedRows;

        private OptimizeResult(int indexedRows, int skippedRows) {
            this.indexedRows = indexedRows;
            this.skippedRows = skippedRows;
        }
    }

    private final File path;
    private final Zobrist zobrist;
    private final SQLiteDatabase source;
    private final SQLiteDatabase sidecar;
    private final boolean needsOptimization;
    private boolean closed;

    private Obk(File path, Zobrist zobrist, SQLiteDatabase source, SQLiteDatabase sidecar, boolean needsOptimization) {
        this.path = path;
        this.zobrist = zobrist;
        this.source = source;
        this.sidecar = sidecar;
        this.needsOptimization = needsOptimization;
    }

    /** 查询当前局面的候选着，结果按评分从高到低排列。 */
    public synchronized List<MoveInfo> query(Position position) throws ObkException {
        Objects.requireNonNull(position, "position");
        ensureOpen();
        try {
            List<MoveInfo> candidates = new ArrayList<>();
            readKey(zobrist.key(position), false, candidates);
            readKey(zobrist.mirroredKey(position), true, candidates);
            candidates.sort((left, right) -> Integer.compare(right.score(), left.score()));
            return candidates;
        } catch (Exception error) {
            throw new ObkException("读取 bhobk 开局库失败", error);
        }
    }

    /** 同一个 vkey 要么直接查询 bhobk，要么先用旁路索引取出 bhobk.id。 */
    private void readKey(long key, boolean mirror, List<MoveInfo> candidates) {
        if (sidecar == null) {
            readCandidates("SELECT " + BOOK_COLUMNS + " FROM bhobk WHERE vkey=?" + VALID_MOVE,
                new String[] {Long.toString(key)}, mirror, candidates);
            return;
        }

        List<String> ids = readIds(key);
        if (ids.isEmpty())
            return;
        String sql = "SELECT " + BOOK_COLUMNS + " FROM bhobk WHERE id IN (" + placeholders(ids.size()) + ")" + VALID_MOVE;
        readCandidates(sql, ids.toArray(new String[0]), mirror, candidates);
    }

    /** 旁路索引只保存原 bhobk 的 id，完整候选仍从 bhobk 读取。 */
    private List<String> readIds(long key) {
        List<String> ids = new ArrayList<>();
        try (Cursor cursor = sidecar.rawQuery("SELECT id FROM key_index WHERE vkey=?", new String[] {Long.toString(key)})) {
            while (cursor.moveToNext())
                ids.add(Long.toString(cursor.getLong(0)));
        }
        return ids;
    }

    private void readCandidates(String sql, String[] arguments, boolean mirror, List<MoveInfo> candidates) {
        try (Cursor cursor = source.rawQuery(sql, arguments)) {
            while (cursor.moveToNext()) {
                candidates.add(new MoveInfo(zobrist.move(cursor.getInt(0), mirror), cursor.getInt(1), cursor.getInt(2),
                    cursor.getInt(3), cursor.getInt(4)));
            }
        }
    }

    private static String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    /** 创建同名 .idx；REAL vkey 以 double 的原始位型标准化为 int64 Zobrist 键。 */
    public synchronized OptimizeResult optimize() throws ObkException {
        ensureOpen();
        File output = sidecarPath(path);
        File temporary = new File(output + ".tmp");
        if (temporary.exists() && !temporary.delete())
            throw new ObkException("无法清理旧的临时旁路索引");

        boolean published = false;
        try {
            OptimizeResult result = writeSidecar(temporary);
            replaceSidecar(temporary, output);
            published = true;
            return result;
        } catch (ObkException error) {
            throw error;
        } catch (Exception error) {
            throw new ObkException("建立旁路索引失败", error);
        } finally {
            if (!published && temporary.exists())
                temporary.delete();
        }
    }

    private OptimizeResult writeSidecar(File temporary) {
        int indexedRows = 0;
        int skippedRows = 0;
        try (SQLiteDatabase database = SQLiteDatabase.openOrCreateDatabase(temporary, null)) {
            // id 是原 bhobk 表的主键，不是 key_index 自己的行号；字段名保持兼容既有 idx。
            database.execSQL("CREATE TABLE key_index(id INTEGER NOT NULL,vkey INTEGER NOT NULL)");
            database.beginTransaction();
            try {
                try (SQLiteStatement insert = database.compileStatement("INSERT INTO key_index VALUES(?,?)")) {
                    try (Cursor rows = source.rawQuery("SELECT id,vkey FROM bhobk WHERE vkey IS NOT NULL", null)) {
                        while (rows.moveToNext()) {
                            int type = rows.getType(1);
                            if (type != Cursor.FIELD_TYPE_INTEGER && type != Cursor.FIELD_TYPE_FLOAT) {
                                skippedRows++;
                                continue;
                            }
                            insert.clearBindings();
                            insert.bindLong(1, rows.getLong(0));
                            insert.bindLong(2, type == Cursor.FIELD_TYPE_INTEGER
                                ? rows.getLong(1)
                                : Double.doubleToRawLongBits(rows.getDouble(1)));
                            insert.executeInsert();
                            indexedRows++;
                        }
                    }
                }
                database.setTransactionSuccessful();
            } finally {
                if (database.inTransaction())
                    database.endTransaction();
            }
            database.execSQL("CREATE INDEX idx_key_index_vkey ON key_index(vkey)");
        }
        return new OptimizeResult(indexedRows, skippedRows);
    }

    private static void replaceSidecar(File temporary, File output) throws ObkException {
        if (output.exists() && !output.delete())
            throw new ObkException("无法替换旧的旁路索引");
        if (!temporary.renameTo(output))
            throw new ObkException("无法创建旁路索引");
    }

    /** 打开并校验 bhobk；同名 .idx 可用时一并打开。 */
    public static Obk open(File path, Zobrist zobrist) throws ObkException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(zobrist, "zobrist");
        if (!path.isFile())
            throw new ObkException("开局库文件不存在");

        SQLiteDatabase source = null;
        try {
            source = openReadOnly(path);
            if (!isBookSchema(source))
                throw new ObkException("不是有效的 bhobk 开局库");

            SQLiteDatabase sidecar = openSidecar(path);
            boolean needsOptimization = sidecar == null && (!hasVkeyIndex(source) || hasRealKey(source));
            return new Obk(path, zobrist, source, sidecar, needsOptimization);
        } catch (ObkException error) {
            closeQuietly(source);
            throw error;
        } catch (Exception error) {
            closeQuietly(source);
            throw new ObkException("无法打开 bhobk 开局库", error);
        }
    }

    /** 无效的旁路索引可安全忽略，后续仍可重新建立。 */
    private static SQLiteDatabase openSidecar(File book) {
        File path = sidecarPath(book);
        if (!path.isFile())
            return null;

        SQLiteDatabase database = null;
        try {
            database = openReadOnly(path);
            if (isSidecarSchema(database))
                return database;
        } catch (Exception ignored) {
            // 旁路索引只是优化，错误不影响原 bhobk 的读取。
        }
        closeQuietly(database);
        return null;
    }

    private static SQLiteDatabase openReadOnly(File path) {
        return SQLiteDatabase.openDatabase(path.getPath(), null, SQLiteDatabase.OPEN_READONLY);
    }

    private static boolean isBookSchema(SQLiteDatabase database) {
        try (Cursor cursor = database.rawQuery("PRAGMA table_info(bhobk)", null)) {
            Set<String> columns = new HashSet<>();
            while (cursor.moveToNext())
                columns.add(cursor.getString(1));
            return columns.containsAll(Arrays.asList("id", "vkey", "vmove", "vscore", "vwin", "vdraw", "vlost", "vvalid"));
        }
    }

    private static boolean isSidecarSchema(SQLiteDatabase database) {
        try (Cursor cursor = database.rawQuery("PRAGMA table_info(key_index)", null)) {
            boolean id = false;
            boolean key = false;
            while (cursor.moveToNext()) {
                String name = cursor.getString(1);
                id |= "id".equals(name);
                key |= "vkey".equals(name);
            }
            return id && key;
        }
    }

    private static boolean hasVkeyIndex(SQLiteDatabase database) {
        try (Cursor indexes = database.rawQuery("PRAGMA index_list(bhobk)", null)) {
            while (indexes.moveToNext()) {
                try (Cursor columns = database.rawQuery("PRAGMA index_info(" + indexes.getString(1) + ")", null)) {
                    while (columns.moveToNext()) {
                        if ("vkey".equals(columns.getString(2)))
                            return true;
                    }
                }
            }
        }
        return false;
    }

    /** 历史 bhobk 可能把负 Zobrist 键作为 IEEE-754 REAL 写入，必须使用旁路索引。 */
    private static boolean hasRealKey(SQLiteDatabase database) {
        try (Cursor cursor = database.rawQuery("SELECT 1 FROM bhobk WHERE typeof(vkey)='real' LIMIT 1", null)) {
            return cursor.moveToFirst();
        }
    }

    public File path() {
        return path;
    }

    public boolean needsOptimization() {
        return needsOptimization;
    }

    private static File sidecarPath(File book) {
        return new File(book.getPath() + ".idx");
    }

    private static void closeQuietly(SQLiteDatabase database) {
        if (database != null)
            database.close();
    }

    @Override
    public synchronized void close() {
        if (closed)
            return;
        closed = true;
        closeQuietly(sidecar);
        closeQuietly(source);
    }

    private void ensureOpen() throws ObkException {
        if (closed)
            throw new ObkException("开局库已关闭");
    }
}
