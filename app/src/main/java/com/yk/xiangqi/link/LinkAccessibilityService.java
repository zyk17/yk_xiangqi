package com.yk.xiangqi.link;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.view.accessibility.AccessibilityEvent;

/**
 * 无障碍服务只执行已经规划好的手势，不参与棋局或识别决策。
 */
public final class LinkAccessibilityService extends AccessibilityService {
    private static volatile LinkAccessibilityService active;

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
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(stroke(plan.from(), 0));
        builder.addStroke(stroke(plan.to(), plan.intervalMs()));
        return service.dispatchGesture(builder.build(), new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                result.completed();
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                result.cancelled();
            }
        }, null);
    }

    private static GestureDescription.StrokeDescription stroke(BoardGeometry.Point point, long startMs) {
        Path path = new Path();
        path.moveTo(point.x(), point.y());
        return new GestureDescription.StrokeDescription(path, startMs, 1);
    }

    public interface GestureResult {
        void completed();

        void cancelled();
    }
}
