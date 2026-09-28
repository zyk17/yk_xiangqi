package com.yk.xiangqi.link;

import com.yk.xiangqi.Settings;
/**
 * 只处理缩小后的灰度图。每次明显运动后重新等待静止，静止阶段仅放行一帧识别。
 */
public final class MotionGate {
    private float[] previous;
    private long stableSince;
    private boolean recognized;

    public void reset() {
        previous = null;
        stableSince = 0;
        recognized = false;
    }

    public boolean allow(float[] luma, long nowMs, Settings.Link config) {
        if (luma == null || luma.length == 0)
            return false;
        if (previous == null || previous.length != luma.length) {
            previous = luma.clone();
            stableSince = nowMs;
            recognized = false;
            return false;
        }
        float difference = difference(previous, luma);
        System.arraycopy(luma, 0, previous, 0, luma.length);
        if (difference > config.motionThreshold) {
            stableSince = nowMs;
            recognized = false;
            return false;
        }
        if (recognized || nowMs - stableSince < config.settleMs)
            return false;
        recognized = true;
        return true;
    }

    static float difference(float[] first, float[] second) {
        float sum = 0f;
        for (int index = 0; index < first.length; index++)
            sum += Math.abs(first[index] - second[index]);
        return sum / first.length;
    }
}
