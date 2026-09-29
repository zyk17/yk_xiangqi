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

    // 应用与悬浮窗。
    private GameRuntime gameRuntime;
    private GameRuntime.Listener gameListener;
    private GameRuntime.ArrowListener arrowListener;
    private Handler main;
    private Settings settings;
    private LinkOverlay overlay;

    // 连线过程状态。仅由 worker 写入。
    private Side pendingSyncSide;
    /**
     * 每次新帧都会重置；到期时只识别最后保存的一张帧。
     */
    private final Runnable settledFrame = this::processSettledFrame;
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
        DisplayMetrics windowMetrics = getResources().getDisplayMetrics();
        Log.i(TAG, "投屏真实尺寸=" + metrics.widthPixels + 'x' + metrics.heightPixels
                + "，应用窗口=" + windowMetrics.widthPixels + 'x' + windowMetrics.heightPixels);
        createCapture(metrics);
        gameRuntime = ((XiangqiApplication) getApplication()).gameRuntime();
        try {
            recognizer = new PieceRecognizer(this);
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
                    pendingSyncSide = sideToMove;
                    Log.i(TAG, "请求使用当前帧同步：行棋=" + sideToMove);
                    if (!queueLatestFrame(reader))
                        synchronizePendingFrame(sideToMove);
                    if (pendingSyncSide != null)
                        synchronizeLastInput(sideToMove);
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
            Log.i(TAG, "开始连线：棋盘=" + geometry.left() + ',' + geometry.top() + '-' + geometry.right() + ',' + geometry.bottom() + "，底边=" + bottom);
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
     * 没有新帧时重新推理最后一次已处理的屏幕输入，不重新申请屏幕录制授权。
     */
    private void synchronizeLastInput(Side sideToMove) {
        try {
            ObservedBoard board = recognizer.recognizeLastInput();
            if (board != null)
                completeSync(linkState.synchronize(board, sideToMove), "使用最后已处理屏幕帧同步：");
        } catch (Exception error) {
            Log.e(TAG, "最近屏幕输入同步失败", error);
        }
    }

    /**
     * 手动同步优先消耗尚在等待稳定的最新帧。
     */
    private void synchronizePendingFrame(Side sideToMove) {
        Image image = takePendingFrame();
        if (image == null)
            return;
        try {
            completeSync(linkState.synchronize(recognizer.recognize(image, geometry), sideToMove), "使用等待中的屏幕帧同步：");
        } catch (Exception error) {
            Log.e(TAG, "最近帧同步失败", error);
        } finally {
            image.close();
        }
    }

    private void completeSync(LinkResult result, String message) {
        if (result == null)
            return;
        pendingSyncSide = null;
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
            pendingSyncSide = sideToMove;
            writtenPly = gameRuntime.state().moves().size();
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification("棋盘框选已更新，等待同步"));
            queueLatestFrame(reader);
        });
    }

    private void startRecognition(BoardGeometry geometry, Side bottomSide) {
        Side newGameSide = linkState != null ? linkState.newGameSide() : Side.RED;
        discardPendingFrame();
        this.geometry = geometry;
        linkState = LinkState.start(bottomSide, newGameSide);
        recognizer.reset();
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
        GesturePlan plan = GesturePlan.forMove(geometry, linkState.bottomSide(), move, settings.link().tapIntervalMs);
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
     * 接管 ImageReader 中的最新帧，并延迟到没有后续帧时再推理。
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
            replacePendingFrame(image);
            image = null;
            worker.removeCallbacks(settledFrame);
            if (pendingSyncSide != null) {
                Side side = pendingSyncSide;
                synchronizePendingFrame(side);
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

    /**
     * frameSettleMs 内没有更晚的帧时，处理暂存的最终帧。
     */
    private void processSettledFrame() {
        if (recognizer == null || stopping)
            return;
        Image image = takePendingFrame();
        if (image == null)
            return;
        try {
            reduceLink(recognizer.recognize(image, geometry));
        } catch (Exception error) {
            failRecognition(error);
        } finally {
            image.close();
        }
    }

    private void reduceLink(ObservedBoard board) {
        LinkResult result = linkState.reduce(gameRuntime.state().position(), board, SystemClock.elapsedRealtime());
        linkState = result.state();
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

    private void failRecognition(Exception error) {
        if (linkState != null)
            linkState = linkState.stopRecognition();
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

    @Override
    public void onDestroy() {
        stopping = true;
        if (worker != null)
            worker.removeCallbacks(settledFrame);
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
