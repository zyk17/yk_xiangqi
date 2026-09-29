package com.yk.xiangqi.link;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;

/**
 * 无障碍服务只执行已经规划好的手势，不参与棋局或识别决策。
 */
public final class LinkAccessibilityService extends AccessibilityService {
    private static volatile LinkAccessibilityService active;
    private final Handler main = new Handler(Looper.getMainLooper());

    /**
     * 连线开始前确认无障碍服务已经实际连接，避免只能识别却无法回写走子。
     */
    public static boolean isActive() {
        return active != null;
    }

    @Override
    public void onServiceConnected() {
        active = this;
    }

    @Override
    public void onDestroy() {
        if (active == this)
            active = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    public static boolean dispatch(GesturePlan plan, GestureResult result) {
        LinkAccessibilityService service = active;
        if (service == null)
            return false;
        return service.dispatchFirstTap(plan, result);
    }

    /**
     * 两次点击必须是两个独立手势：部分应用会忽略同一 GestureDescription 中的第二个
     * Stroke，或将持续 1ms 的 Stroke 视作无效输入。
     */
    private boolean dispatchFirstTap(GesturePlan plan, GestureResult result) {
        return dispatchTap(plan.from(), plan.tapDurationMs(), new GestureResult() {
            @Override
            public void completed() {
                main.postDelayed(() -> dispatchSecondTap(plan, result), plan.tapIntervalMs());
            }

            @Override
            public void cancelled() {
                result.cancelled();
            }
        });
    }

    private void dispatchSecondTap(GesturePlan plan, GestureResult result) {
        if (active != this || !dispatchTap(plan.to(), plan.tapDurationMs(), result))
            result.cancelled();
    }

    private boolean dispatchTap(BoardGeometry.Point point, long durationMs, GestureResult result) {
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(stroke(point, durationMs));
        return dispatchGesture(builder.build(), new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                result.completed();
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                result.cancelled();
            }
        }, main);
    }

    private static GestureDescription.StrokeDescription stroke(BoardGeometry.Point point, long durationMs) {
        Path path = new Path();
        path.moveTo(point.x(), point.y());
        return new GestureDescription.StrokeDescription(path, 0, durationMs);
    }

    public interface GestureResult {
        void completed();

        void cancelled();
    }
}
