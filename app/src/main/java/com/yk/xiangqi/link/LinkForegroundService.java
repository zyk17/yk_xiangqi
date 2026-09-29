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
import android.util.DisplayMetrics;

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
    private static final String CHANNEL = "link";
    private static final int NOTIFICATION_ID = 17;
    public static final String ACTION_START = "com.yk.xiangqi.link.START";
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";

    private HandlerThread workerThread;
    private Handler worker;
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private LinkLoop loop;
    private Side pendingSyncSide;
    private GameRuntime gameRuntime;
    private GameRuntime.Listener gameListener;
    private GameRuntime.ArrowListener arrowListener;
    private Handler main;
    private Settings settings;
    private int writtenPly = -1;
    private LinkOverlay overlay;

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
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                stopSelf();
            }
        }, worker);
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        reader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, android.graphics.PixelFormat.RGBA_8888, 2);
        display = projection.createVirtualDisplay("xiangqi-link", metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, worker);
        reader.setOnImageAvailableListener(this::onImage, worker);
        gameRuntime = ((XiangqiApplication) getApplication()).gameRuntime();
        try {
            loop = new LinkLoop(new PieceRecognizer(this));
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
            public void onSelectionCancelled() {
                overlay.showReady();
            }

            @Override
            public boolean onAiToggled() {
                Side side = loop.bottomSide();
                boolean enabled = !gameRuntime.aiEnabled(side);
                gameRuntime.setAi(side, enabled);
                return enabled;
            }

            @Override
            public void onNewGameSideChanged(Side sideToMove) {
                postToWorker(() -> loop.setNewGameSide(sideToMove));
            }

            @Override
            public void onSynchronize(Side sideToMove) {
                postToWorker(() -> pendingSyncSide = sideToMove);
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
            loop.start(geometry, bottom);
            writtenPly = gameRuntime.state().moves().size();
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification("连线已开始"));
        });
    }

    /** 仅在帧 worker 写入 LinkLoop 与走子注入状态。 */
    private void postToWorker(Runnable task) {
        if (worker != null)
            worker.post(task);
    }

    /** 所有 WindowManager 操作都回到主线程；棋局与箭头可来自 UI 或帧 worker。 */
    private void refreshMiniBoard(com.yk.xiangqi.core.GameState state) {
        if (main != null)
            main.post(() -> {
                if (overlay != null)
                    overlay.showMiniBoard(state, gameRuntime.arrows());
            });
    }

    private void writeLocalMove(com.yk.xiangqi.core.GameState state) {
        if (loop == null || loop.geometry() == null)
            return;
        java.util.List<com.yk.xiangqi.core.Move> moves = state.moves();
        if (moves.isEmpty() || moves.size() == writtenPly)
            return;
        writtenPly = moves.size();
        GesturePlan plan = GesturePlan.forMove(loop.geometry(), loop.bottomSide(), moves.get(moves.size() - 1), settings.link().tapIntervalMs);
        LinkAccessibilityService.dispatch(plan, new LinkAccessibilityService.GestureResult() {
            @Override
            public void completed() {
            }

            @Override
            public void cancelled() {
            }
        });
    }

    private void onImage(ImageReader ignored) {
        try (Image image = reader.acquireLatestImage()) {
            if (image == null || loop == null || loop.geometry() == null)
                return;
            if (pendingSyncSide != null) {
                Side side = pendingSyncSide;
                pendingSyncSide = null;
                apply(loop.synchronize(image, side));
            } else {
                apply(loop.processFrame(image, gameRuntime.state().position(), settings.link()));
            }
        } catch (Exception ignoredError) {
            if (loop != null)
                loop.stop();
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification("连线识别失败，请同步局面"));
        }
    }

    /** 将连线纯归约输出明确写回应用棋局；外部来源不会再次注入无障碍点击。 */
    private void apply(LinkAction action) {
        if (action == null)
            return;
        switch (action.kind()) {
            case PLAY -> gameRuntime.reduce(new GameAction.Play(action.move()), GameRuntime.Origin.EXTERNAL);
            case RESET -> gameRuntime.reduce(new GameAction.Reset(action.position()), GameRuntime.Origin.EXTERNAL);
            case DESYNCED -> getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification("连线失步，请同步局面"));
            case NONE -> {
            }
        }
    }

    @Override
    public void onDestroy() {
        if (overlay != null) overlay.close();
        if (reader != null) reader.close();
        if (gameRuntime != null && gameListener != null) gameRuntime.removeListener(gameListener);
        if (gameRuntime != null && arrowListener != null) gameRuntime.removeArrowListener(arrowListener);
        if (display != null) display.release();
        if (projection != null) projection.stop();
        if (loop != null) postToWorker(loop::close);
        if (workerThread != null) workerThread.quitSafely();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static void start(Context context) {
        Intent intent = new Intent(context, LinkForegroundService.class);
        context.startForegroundService(intent);
    }

    public static void start(Context context, int resultCode, Intent resultData) {
        Intent intent = new Intent(context, LinkForegroundService.class).setAction(ACTION_START)
            .putExtra(EXTRA_RESULT_CODE, resultCode).putExtra(EXTRA_RESULT_DATA, resultData);
        context.startForegroundService(intent);
    }

}
