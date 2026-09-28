/**
 * 原生安卓连线模块。
 *
 * <p>入口是 {@link com.yk.xiangqi.link.LinkForegroundService}：它取得最新录屏帧，
 * 调用 {@link com.yk.xiangqi.link.LinkLoop}，并将返回的 {@link com.yk.xiangqi.link.LinkAction}
 * 写回 {@code GameRuntime}。</p>
 *
 * <p>一帧路径为：{@code Image -> FramePreprocessor -> MotionGate -> PieceRecognizer ->
 * ObservedBoard -> LinkLoop -> LinkAction}。其中 {@code LinkLoop} 只由帧 worker 写入；
 * {@code ObservedBoard} 与核心 {@code Position} 的比较不接触 Android。</p>
 *
 * <p>阅读顺序：先看 {@code LinkLoop}，再看 {@code ObservedBoard}、{@code FramePreprocessor}
 * 与 {@code MotionGate}；Android 生命周期和手势输出最后看两个 Service。</p>
 */
package com.yk.xiangqi.link;
