/**
 * 原生安卓连线模块。
 *
 * <p>入口是 {@link com.yk.xiangqi.link.LinkForegroundService}：它取得最新录屏帧，
 * 调用 {@link com.yk.xiangqi.link.PieceRecognizer} 与 {@link com.yk.xiangqi.link.LinkState}，并将返回的 {@link com.yk.xiangqi.link.LinkAction}
 * 写回 {@code GameRuntime}。</p>
 *
 * <p>一帧路径为：{@code ImageReader -> 最新 Image -> frameSettleMs -> PieceRecognizer ->
 * ObservedBoard -> LinkState -> LinkAction}。其中 Android 帧资源仅由帧 worker 持有；
 * {@code LinkState} 与核心 {@code Position} 的比较不接触 Android。</p>
 *
 * <p>阅读顺序：先看纯 {@code LinkState}、{@code ObservedBoard}，再看 {@code PieceRecognizer}；
 * Android 生命周期、帧延迟和手势输出最后看两个 Service。</p>
 */
package com.yk.xiangqi.link;
