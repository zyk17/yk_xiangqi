package com.yk.xiangqi.link;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;

import androidx.core.content.IntentCompat;

import com.yk.xiangqi.Settings;
import com.yk.xiangqi.GameRuntime;
import com.yk.xiangqi.XiangqiApplication;
import com.yk.xiangqi.core.GameAction;
import com.yk.xiangqi.core.Side;
import com.yk.xiangqi.ui.link.LinkOverlay;

/**
 * 连线的后台生命周期所有者。录屏、帧工作线程和普通悬浮窗将在这里统一关闭。
 */
public final class LinkForegroundService extends Service {
    private static final String TAG = "XiangqiLink";
    private static final String CHANNEL = "link";
    private static final int NOTIFICATION_ID = 17;
    private static final long SYNC_FRAME_WAIT_MS = 1_000L;
    public static final String ACTION_START = "com.yk.xiangqi.link.START";
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";

    // 录屏与帧处理资源。
    private HandlerThread workerThread;
    private Handler worker;
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private PieceRecognizer recognizer;
    private volatile LinkState linkState;
    private BoardGeometry geometry;
    /** 由 worker 持有，延迟识别完成、替换或服务关闭时释放。 */
    private Image pendingFrame;
    /** 只比较框选棋盘内的少量亮度样本，避免静态合成帧重复触发 ONNX。 */
    private final FrameGate frameGate = new FrameGate();

    // 应用与悬浮窗。
    private GameRuntime gameRuntime;
    private GameRuntime.Listener gameListener;
    private GameRuntime.ArrowListener arrowListener;
    private Handler main;
    private Settings settings;
    private LinkOverlay overlay;

    // 连线过程状态。仅由 worker 写入。
    private Side pendingSyncSide;
    /** 棋盘区域出现实质视觉变化时重置；到期时只识别最后保存的一张帧。 */
    private final Runnable settledFrame = this::processSettledFrame;
    /** 当前候选棋盘变化的首尾时刻，仅在最终产生连线动作时输出分段耗时。 */
    private long firstChangeMs = -1L;
    private long lastChangeMs = -1L;
    /** 手动同步只等待下一张真实投屏帧，超时后不复用旧帧。 */
    private final Runnable syncFrameTimeout = () -> {
        if (pendingSyncSide == null)
            return;
        pendingSyncSide = null;
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID,
                notification("未收到新的屏幕帧，请稍后再试"));
    };
    private int writtenPly = -1;
    private volatile boolean stopping;

    @Override
    public void onCreate() {
        super.onCreate();
        main = new Handler(Looper.getMainLooper());
        settings = ((XiangqiApplication) getApplication()).settings();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "象棋连线", NotificationManager.IMPORTANCE_LOW));
        startForeground(NOTIFICATION_ID, notification("点击应用可查看棋局，通知可用于保持连线服务。"));
    }

    private Notification notification(String text) {
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("YK 象棋连线运行中")
                .setContentText(text)
                .setOngoing(true)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_START.equals(intent.getAction()))
            begin(intent);
        return START_NOT_STICKY;
    }

    private void begin(Intent intent) {
        if (projection != null)
            return;
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent.class);
        if (data == null)
            return;
        workerThread = new HandlerThread("xiangqi-link");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        projection = manager.getMediaProjection(resultCode, data);
        Log.i(TAG, "屏幕录制已授权，启动帧读取");
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                stopSelf();
            }
        }, worker);
        DisplayMetrics metrics = captureMetrics();
        createCapture(metrics);
        gameRuntime = ((XiangqiApplication) getApplication()).gameRuntime();
        try {
            recognizer = new PieceRecognizer(this, settings.link().modelThreads);
        } catch (Exception error) {
            stopSelf();
            return;
        }
        gameListener = (state, action, origin) -> {
            refreshMiniBoard(state);
            if (origin == GameRuntime.Origin.LOCAL && action instanceof GameAction.Play && worker != null)
                worker.post(() -> writeLocalMove(state));
        };
        arrowListener = ignored -> refreshMiniBoard(gameRuntime.state());
        gameRuntime.addListener(gameListener);
        gameRuntime.addArrowListener(arrowListener);
        overlay = new LinkOverlay(this, new LinkOverlay.Listener() {
            @Override
            public void onConfigured(BoardGeometry geometry) {
                configure(geometry);
            }

            @Override
            public void onReconfigured(BoardGeometry geometry, Side sideToMove) {
                reconfigure(geometry, sideToMove);
            }

            @Override
            public void onSelectionCancelled() {
                overlay.showReady();
            }

            @Override
            public boolean onAiToggled() {
                LinkState state = linkState;
                if (state == null)
                    return false;
                Side side = state.bottomSide();
                boolean enabled = !gameRuntime.aiEnabled(side);
                if (enabled)
                    gameRuntime.setAi(side.opposite(), false);
                gameRuntime.setAi(side, enabled);
                Log.i(TAG, "自动走子：" + side + '=' + enabled + "，当前行棋="
                        + gameRuntime.state().position().sideToMove());
                return enabled;
            }

            @Override
            public void onNewGameSideChanged(Side sideToMove) {
                postToWorker(() -> {
                    if (linkState != null)
                        linkState = linkState.withNewGameSide(sideToMove);
                });
            }

            @Override
            public void onSynchronize(Side sideToMove) {
                postToWorker(() -> {
                    Log.i(TAG, "请求使用当前帧同步：行棋=" + sideToMove);
                    requestSyncFrame(sideToMove);
                });
            }

            @Override
            public void onStop() {
                stopSelf();
            }
        });
        overlay.showReady();
    }

    private void configure(BoardGeometry geometry) {
        configure(geometry, Side.RED);
    }

    private void configure(BoardGeometry geometry, Side bottom) {
        overlay.showControls(gameRuntime.aiEnabled(bottom));
        refreshMiniBoard(gameRuntime.state());
        postToWorker(() -> {
            worker.removeCallbacks(settledFrame);
            startRecognition(geometry, bottom);
            Log.i(TAG, "开始连线，底边=" + bottom);
            writtenPly = gameRuntime.state().moves().size();
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification("连线已开始"));
            queueLatestFrame(reader);
        });
    }

    /**
     * MediaProjection 镜像的是完整物理显示，而应用 DisplayMetrics 可能排除状态栏、
     * 导航栏。两者尺寸不同时，系统会等比缩放并在两侧补黑边，导致框选坐标失真。
     */
    @SuppressWarnings("deprecation")
    private DisplayMetrics captureMetrics() {
        DisplayMetrics metrics = new DisplayMetrics();
        Display display = getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        if (display == null)
            throw new IllegalStateException("无法取得主屏幕尺寸");
        display.getRealMetrics(metrics);
        return metrics;
    }

    /**
     * 创建一个与完整物理屏幕同尺寸的投屏缓冲区。
     */
    private void createCapture(DisplayMetrics metrics) {
        reader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, android.graphics.PixelFormat.RGBA_8888, 3);
        display = projection.createVirtualDisplay("xiangqi-link", metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, worker);
        reader.setOnImageAvailableListener(this::queueLatestFrame, worker);
    }

    /**
     * 手动同步只接受当前 reader 中尚未读取的最新帧，或随后到达的下一帧；不重建录屏、不复用旧帧。
     */
    private void requestSyncFrame(Side sideToMove) {
        pendingSyncSide = sideToMove;
        worker.removeCallbacks(settledFrame);
        worker.removeCallbacks(syncFrameTimeout);
        if (!queueLatestFrame(reader) && pendingFrame != null)
            processSyncFrame(sideToMove);
        if (pendingSyncSide != null)
            worker.postDelayed(syncFrameTimeout, SYNC_FRAME_WAIT_MS);
    }

    private void completeSync(LinkResult result, String message) {
        if (result == null)
            return;
        pendingSyncSide = null;
        worker.removeCallbacks(syncFrameTimeout);
        linkState = result.state();
        Log.i(TAG, message + result.action().kind());
        apply(result.action());
    }

    /**
     * 重新框选后不等待自动首帧判定，而是用下一帧强制同步当前局面。
     */
    private void reconfigure(BoardGeometry geometry, Side sideToMove) {
        postToWorker(() -> {
            Log.i(TAG, "重新框选后请求同步：行棋方=" + sideToMove);
            worker.removeCallbacks(settledFrame);
            LinkState state = linkState;
            Side bottom = state != null ? state.bottomSide() : Side.RED;
            startRecognition(geometry, bottom);
            writtenPly = gameRuntime.state().moves().size();
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification("棋盘框选已更新，等待同步"));
            requestSyncFrame(sideToMove);
        });
    }

    private void startRecognition(BoardGeometry geometry, Side bottomSide) {
        Side newGameSide = linkState != null ? linkState.newGameSide() : Side.RED;
        discardPendingFrame();
        this.geometry = geometry;
        linkState = LinkState.start(bottomSide, newGameSide);
        frameGate.reset();
        firstChangeMs = -1L;
        lastChangeMs = -1L;
    }

    /**
     * 仅在帧 worker 写入连线状态与走子注入状态。
     */
    private void postToWorker(Runnable task) {
        if (worker != null)
            worker.post(task);
    }

    /**
     * 所有 WindowManager 操作都回到主线程；棋局与箭头可来自 UI 或帧 worker。
     */
    private void refreshMiniBoard(com.yk.xiangqi.core.GameState state) {
        if (main != null)
            main.post(() -> {
                if (overlay != null)
                    overlay.showMiniBoard(state, gameRuntime.arrows());
            });
    }

    private void writeLocalMove(com.yk.xiangqi.core.GameState state) {
        if (geometry == null || linkState == null)
            return;
        java.util.List<com.yk.xiangqi.core.Move> moves = state.moves();
        if (moves.isEmpty() || moves.size() == writtenPly)
            return;
        writtenPly = moves.size();
        com.yk.xiangqi.core.Move move = moves.get(moves.size() - 1);
        // 临时测速：移除无障碍点击本身的等待，测量引擎与外部棋盘的极限延迟。
        GesturePlan plan = GesturePlan.forMove(geometry, linkState.bottomSide(), move,
                1, 1);
        linkState = linkState.awaitWriteback(SystemClock.elapsedRealtime());
        boolean dispatched = LinkAccessibilityService.dispatch(plan, new LinkAccessibilityService.GestureResult() {
            @Override
            public void completed() {
                Log.i(TAG, "已回写走子：" + move.uci());
            }

            @Override
            public void cancelled() {
                postToWorker(() -> linkState = linkState.cancelWriteback());
                Log.w(TAG, "回写走子被系统取消：" + move.uci());
            }
        });
        if (!dispatched) {
            linkState = linkState.cancelWriteback();
            Log.w(TAG, "无法回写走子：无障碍服务未启用，走子=" + move.uci());
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID,
                    notification("无法自动走子：请在系统无障碍设置中启用 YK 象棋"));
        } else {
            Log.i(TAG, "请求回写走子：" + move.uci());
        }
    }

    /**
     * 接管 ImageReader 的最新帧。只有棋盘区域的轻量指纹发生实质变化，才保存该帧并
     * 重置稳定等待；静态合成帧直接释放，不进入预处理或 ONNX。
     *
     * @return 是否实际消费了一张投屏帧。
     */
    private boolean queueLatestFrame(ImageReader source) {
        if (source != reader || recognizer == null || geometry == null || linkState == null || stopping)
            return false;
        Image image = null;
        try {
            image = source.acquireLatestImage();
            if (image == null)
                return false;
            if (pendingSyncSide == null) {
                if (!frameGate.changed(image, geometry)) {
                    image.close();
                    return true;
                }
                long now = SystemClock.elapsedRealtime();
                if (pendingFrame == null)
                    firstChangeMs = now;
                lastChangeMs = now;
                frameGate.accept();
            } else {
                frameGate.accept(image, geometry);
            }
            replacePendingFrame(image);
            image = null;
            worker.removeCallbacks(settledFrame);
            if (pendingSyncSide != null) {
                Side side = pendingSyncSide;
                processSyncFrame(side);
            } else {
                worker.postDelayed(settledFrame, settings.link().frameSettleMs);
            }
            return true;
        } catch (Exception error) {
            if (image != null)
                image.close();
            failRecognition(error);
            return true;
        }
    }

    /** frameSettleMs 内没有更晚的实质棋盘变化时，处理暂存的最终帧。 */
    private void processSettledFrame() {
        if (recognizer == null || stopping)
            return;
        Image image = takePendingFrame();
        if (image == null)
            return;
        try {
            long inferenceStartedMs = SystemClock.elapsedRealtime();
            reduceLink(recognizer.recognize(image, geometry), inferenceStartedMs);
        } catch (Exception error) {
            failRecognition(error);
        } finally {
            image.close();
        }
    }

    private void reduceLink(ObservedBoard board, long inferenceStartedMs) {
        LinkResult result = linkState.reduce(gameRuntime.state().position(), board, SystemClock.elapsedRealtime());
        linkState = result.state();
        long now = SystemClock.elapsedRealtime();
        if (result.action().kind() != LinkAction.Kind.NONE && firstChangeMs >= 0L)
            Log.i(TAG, "识别链路：首差异→末差异=" + (lastChangeMs - firstChangeMs)
                + "ms，末差异→推理=" + (inferenceStartedMs - lastChangeMs)
                + "ms，推理与归约=" + (now - inferenceStartedMs) + "ms，动作=" + result.action().kind());
        firstChangeMs = -1L;
        lastChangeMs = -1L;
        apply(result.action());
    }

    private void replacePendingFrame(Image image) {
        discardPendingFrame();
        pendingFrame = image;
    }

    private Image takePendingFrame() {
        Image image = pendingFrame;
        pendingFrame = null;
        return image;
    }

    private void discardPendingFrame() {
        Image image = takePendingFrame();
        if (image != null)
            image.close();
    }

    /** 同步帧绕过门控，直接将当前识别结果作为新的应用局面。 */
    private void processSyncFrame(Side sideToMove) {
        Image image = takePendingFrame();
        if (image == null)
            return;
        try {
            completeSync(linkState.synchronize(recognizer.recognize(image, geometry), sideToMove), "使用当前屏幕帧同步：");
        } catch (Exception error) {
            Log.e(TAG, "当前帧同步失败", error);
        } finally {
            image.close();
        }
    }

    private void failRecognition(Exception error) {
        if (linkState != null)
            linkState = linkState.stopRecognition();
        pendingSyncSide = null;
        worker.removeCallbacks(syncFrameTimeout);
        discardPendingFrame();
        Log.e(TAG, "帧处理失败", error);
        if (!stopping)
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification("连线识别失败，请同步局面"));
    }

    /**
     * 将连线纯归约输出明确写回应用棋局；外部来源不会再次注入无障碍点击。
     */
    private void apply(LinkAction action) {
        if (stopping || action == null)
            return;
        switch (action.kind()) {
            case PLAY -> {
                Log.i(TAG, "识别到外部走子：" + action.move().uci());
                gameRuntime.reduce(new GameAction.Play(action.move()), GameRuntime.Origin.EXTERNAL);
            }
            case RESET -> {
                gameRuntime.reduce(new GameAction.Reset(action.position()), GameRuntime.Origin.EXTERNAL);
                writtenPly = 0;
                Log.i(TAG, "已同步棋盘：行棋=" + action.position().sideToMove()
                        + "，棋子=" + pieceCount(action.position()));
            }
            case DESYNCED ->
                    getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification("连线失步，请同步局面"));
            case NONE -> {
            }
        }
    }

    private static int pieceCount(com.yk.xiangqi.core.Position position) {
        final int[] count = {0};
        position.forEachPiece((square, side, type) -> count[0]++);
        return count[0];
    }

    /**
     * ImageReader 只能提供最新合成帧，不能判断棋盘是否真的改变。这里以每个交叉点
     * 周围的五个亮度样本做固定阈值比较；它远轻于预处理和 ONNX，只负责决定是否重置
     * 稳定等待，不负责识别棋子。
     */
    private static final class FrameGate {
        private static final float[] OFFSETS = {0f, 0f, -.18f, 0f, .18f, 0f, 0f, -.18f, 0f, .18f};
        private static final int SAMPLE_COUNT = BoardGeometry.FILES * BoardGeometry.RANKS * (OFFSETS.length / 2);
        private static final int LUMA_DELTA = 20;
        private static final int CHANGED_SAMPLES = 3;
        private final byte[] previous = new byte[SAMPLE_COUNT];
        private final byte[] sampled = new byte[SAMPLE_COUNT];
        private boolean initialized;

        void reset() {
            initialized = false;
        }

        boolean changed(Image image, BoardGeometry geometry) {
            sample(image, geometry);
            if (!initialized)
                return true;
            int changed = 0;
            for (int index = 0; index < SAMPLE_COUNT; index++) {
                int difference = Math.abs(Byte.toUnsignedInt(sampled[index]) - Byte.toUnsignedInt(previous[index]));
                if (difference >= LUMA_DELTA && ++changed >= CHANGED_SAMPLES)
                    return true;
            }
            return false;
        }

        void accept() {
            System.arraycopy(sampled, 0, previous, 0, SAMPLE_COUNT);
            initialized = true;
        }

        void accept(Image image, BoardGeometry geometry) {
            sample(image, geometry);
            accept();
        }

        private void sample(Image image, BoardGeometry geometry) {
            Image.Plane plane = image.getPlanes()[0];
            java.nio.ByteBuffer pixels = plane.getBuffer();
            int width = image.getWidth();
            int height = image.getHeight();
            int rowStride = plane.getRowStride();
            int pixelStride = plane.getPixelStride();
            int index = 0;
            for (int row = 0; row < BoardGeometry.RANKS; row++)
                for (int column = 0; column < BoardGeometry.FILES; column++)
                    for (int offset = 0; offset < OFFSETS.length; offset += 2) {
                        int x = clamp(Math.round(geometry.left() + (column + OFFSETS[offset]) * geometry.stepX()), 0, width - 1);
                        int y = clamp(Math.round(geometry.top() + (row + OFFSETS[offset + 1]) * geometry.stepY()), 0, height - 1);
                        int at = y * rowStride + x * pixelStride;
                        int red = pixels.get(at) & 255;
                        int green = pixels.get(at + 1) & 255;
                        int blue = pixels.get(at + 2) & 255;
                        sampled[index++] = (byte) ((red * 77 + green * 150 + blue * 29) >>> 8);
                    }
        }

        private static int clamp(int value, int lower, int upper) {
            return Math.max(lower, Math.min(upper, value));
        }
    }

    @Override
    public void onDestroy() {
        stopping = true;
        if (worker != null) {
            worker.removeCallbacks(settledFrame);
            worker.removeCallbacks(syncFrameTimeout);
        }
        if (overlay != null) overlay.close();
        if (gameRuntime != null && gameListener != null) gameRuntime.removeListener(gameListener);
        if (gameRuntime != null && arrowListener != null)
            gameRuntime.removeArrowListener(arrowListener);
        if (worker != null && worker.getLooper().getThread().isAlive())
            worker.post(this::closeCapture);
        else
            closeCapture();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    /**
     * 录屏帧、ONNX 会话均由帧 worker 使用，因此也必须在该线程关闭。
     * onDestroy 不等待正在运行的推理；本任务会排在它之后执行。
     */
    private void closeCapture() {
        discardPendingFrame();
        if (reader != null) {
            reader.close();
            reader = null;
        }
        if (display != null) {
            display.release();
            display = null;
        }
        if (recognizer != null) {
            recognizer.close();
            recognizer = null;
        }
        if (projection != null) {
            projection.stop();
            projection = null;
        }
        if (workerThread != null)
            workerThread.quitSafely();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static void start(Context context, int resultCode, Intent resultData) {
        Intent intent = new Intent(context, LinkForegroundService.class).setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode).putExtra(EXTRA_RESULT_DATA, resultData);
        context.startForegroundService(intent);
    }

}
