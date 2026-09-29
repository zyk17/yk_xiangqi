package com.yk.xiangqi.engine;

import com.yk.xiangqi.core.GameState;
import com.yk.xiangqi.core.Move;

import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单一串行 UCI 进程；回调在它的工作线程中发出。
 */
public final class UciEngine implements Closeable {
    public interface Listener {
        /**
         * 已收到 uciok；调用方现在可依据声明的 Option 选择配置。
         */
        void onUciOptions(List<UciOption> options);

        /**
         * 启动或配置引擎失败。
         */
        void onEngineFailed(String message);

        /**
         * 配置已收到 readyok，可以开始搜索。
         */
        void onReady();

        void onInfo(long searchId, ThinkingInfo info);

        void onBestMove(long searchId, BestMoveInfo info);

        void onSearchFailed(long searchId, String message);
    }

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Listener listener;
    private volatile Process process;
    private volatile BufferedWriter stdin;
    private volatile BufferedReader stdout;
    private final AtomicLong searchId = new AtomicLong();
    private List<UciOption> declaredOptions = List.of();

    public UciEngine(Listener listener) {
        this.listener = listener;
    }

    /**
     * 启动进程并完成 {@code uci → uciok} 握手。
     */
    public void start(File executable, File workingDir) {
        io.execute(() -> {
            try {
                stopInternal();
                declaredOptions = List.of();
                process = new ProcessBuilder(executable.getAbsolutePath()).directory(workingDir).redirectErrorStream(true).start();
                stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
                stdout = new BufferedReader(new InputStreamReader(process.getInputStream()));
                send("uci");
                List<UciOption> options = awaitUciOk();
                if (options == null)
                    throw new IOException("UCI handshake timeout");
                this.declaredOptions = options;
                listener.onUciOptions(options);
            } catch (Exception e) {
                if (!io.isShutdown())
                    listener.onEngineFailed("引擎启动失败: " + e.getMessage());
                stopInternal();
            }
        });
    }

    /**
     * 在收到 {@link Listener#onUciOptions(List)} 后调用：写入配置，并以 readyok
     * 作为配置生效的屏障。调用方应先停止正在进行的搜索。
     */
    public void configure(Map<String, String> settings) {
        Map<String, String> requested = Collections.unmodifiableMap(new LinkedHashMap<>(settings));
        io.execute(() -> {
            try {
                if (stdin == null)
                    throw new IOException("引擎尚未启动");
                for (Map.Entry<String, String> option : requested.entrySet())
                    send(declaredOption(option.getKey()).setOption(option.getValue()));
                send("isready");
                if (!awaitReadyOk())
                    throw new IOException("UCI ready timeout");
                listener.onReady();
            } catch (Exception e) {
                listener.onEngineFailed("引擎配置失败: " + e.getMessage());
            }
        });
    }

    /**
     * 触发一次 UCI button 选项，不把它当作持久化配置。
     */
    public void pressOption(String name) {
        io.execute(() -> {
            try {
                if (stdin == null)
                    throw new IOException("引擎尚未启动");
                send(declaredOption(name).press());
                send("isready");
                if (!awaitReadyOk())
                    throw new IOException("UCI ready timeout");
                listener.onReady();
            } catch (Exception e) {
                listener.onEngineFailed("引擎选项执行失败: " + e.getMessage());
            }
        });
    }

    private UciOption declaredOption(String name) {
        for (UciOption option : declaredOptions)
            if (option.name.equalsIgnoreCase(name))
                return option;
        return UciOption.unknown(name);
    }

    private boolean awaitReadyOk() throws IOException {
        BufferedReader output = stdout;
        if (output == null)
            throw new IOException("引擎尚未启动");
        long end = System.currentTimeMillis() + 3000;
        String line;
        while (System.currentTimeMillis() < end && (line = output.readLine()) != null)
            if (line.trim().equalsIgnoreCase("readyok"))
                return true;
        return false;
    }

    /**
     * 等待 uciok，并收集引擎实际声明的选项。
     */
    private List<UciOption> awaitUciOk() throws IOException {
        BufferedReader output = stdout;
        if (output == null)
            throw new IOException("引擎尚未启动");
        long end = System.currentTimeMillis() + 3000;
        List<UciOption> options = new ArrayList<>();
        String line;
        while (System.currentTimeMillis() < end && (line = output.readLine()) != null) {
            UciOption option = UciOption.parse(line);
            if (option != null)
                options.add(option);
            if (line.trim().equalsIgnoreCase("uciok"))
                return List.copyOf(options);
        }
        return null;
    }

    static String setOption(String name, String value) {
        if (name == null || name.isBlank() || name.indexOf('\n') >= 0 || name.indexOf('\r') >= 0)
            throw new IllegalArgumentException("无效的 UCI 选项名");
        if (value == null)
            return "setoption name " + name;
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)
            throw new IllegalArgumentException("无效的 UCI 选项值");
        return "setoption name " + name + " value " + value;
    }

    /**
     * 终止旧搜索后开始新搜索；旧搜索仍会被读取到 bestmove，避免该行泄漏给新搜索。
     */
    public synchronized long go(GameState game, GoParams params) {
        long request = searchId.incrementAndGet();
        stopSearch();
        io.execute(() -> search(request, game, params));
        return request;
    }

    private void search(long request, GameState game, GoParams params) {
        if (stdin == null)
            return;
        try {
            send(position(game));
            send(params.uci());
            readSearch(request);
        } catch (Exception e) {
            if (request == searchId.get())
                listener.onSearchFailed(request, e.getMessage());
        }
    }

    /**
     * 按 UCI 语义结束当前搜索，保留这轮随后返回的 bestmove。
     */
    public synchronized void stop() {
        stopSearch();
    }

    /**
     * 取消当前搜索，使其后续 bestmove 失效。
     */
    public synchronized void cancelSearch() {
        searchId.incrementAndGet();
        stopSearch();
    }

    private synchronized void stopSearch() {
        try {
            if (stdin != null)
                send("stop");
        } catch (Exception ignored) {
        }
    }

    private void readSearch(long expected) throws IOException {
        BufferedReader output = stdout;
        if (output == null)
            return;
        while (true) {
            String line = output.readLine();
            if (line == null)
                throw new EOFException("引擎已退出");
            if (line.startsWith("info")) {
                ThinkingInfo info = ThinkingInfo.parse(line);
                if (expected == searchId.get() && info != null && !info.pv.isEmpty())
                    listener.onInfo(expected, info);
            } else if (line.startsWith("bestmove")) {
                BestMoveInfo bestMove = BestMoveInfo.parse(line);
                if (expected == searchId.get())
                    listener.onBestMove(expected, bestMove);
                return;
            }
        }
    }

    /**
     * UCI 是文本协议；只有此进程边界才把根位棋盘局面及当前游标之前的着法序列化。
     */
    static String position(GameState game) {
        StringBuilder command = new StringBuilder("position fen ").append(game.initialPosition().toFen());
        List<Move> moves = game.moves();
        if (!moves.isEmpty()) {
            command.append(" moves");
            for (Move move : moves)
                command.append(' ').append(move.uci());
        }
        return command.toString();
    }

    private synchronized void send(String s) throws IOException {
        BufferedWriter input = stdin;
        if (input == null)
            throw new IOException("引擎尚未启动");
        input.write(s);
        input.newLine();
        input.flush();
    }

    /**
     * 释放进程及全部管道。它可以由 UI 生命周期线程调用，以打断 io 线程中的阻塞读取。
     */
    private synchronized void stopInternal() {
        Process oldProcess = process;
        BufferedWriter oldInput = stdin;
        BufferedReader oldOutput = stdout;
        process = null;
        stdin = null;
        stdout = null;
        declaredOptions = List.of();
        closeQuietly(oldInput);
        try {
            if (oldProcess != null)
                oldProcess.destroy();
        } catch (Exception ignored) {
        }
        closeQuietly(oldOutput);
    }

    private static void closeQuietly(Closeable value) {
        try {
            if (value != null)
                value.close();
        } catch (IOException ignored) {
        }
    }

    @Override
    public void close() {
        // 令正在读取旧搜索的任务失效；关闭 reader/process 会使它立即退出。
        searchId.incrementAndGet();
        io.shutdownNow();
        stopInternal();
    }
}
