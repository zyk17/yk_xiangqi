package com.yk.xiangqi.link;

import android.content.Context;
import android.media.Image;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import com.yk.xiangqi.core.Board;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Collections;

/** 内置 FP32 MobileNetV3 的 ONNX Runtime CPU 推理器。 */
public final class PieceRecognizer implements AutoCloseable {
    private static final int INPUT_SIZE = 48;
    private static final int CHANNELS = 3;
    private static final int PIXELS_PER_CELL = INPUT_SIZE * INPUT_SIZE;
    private static final long[] SHAPE = {90, CHANNELS, INPUT_SIZE, INPUT_SIZE};
    private static final int INPUT_FLOATS = Board.SQUARES * CHANNELS * PIXELS_PER_CELL;
    private static final float[] MEAN = {.485f, .456f, .406f};
    private static final float[] INV_STD = {1f / .229f, 1f / .224f, 1f / .225f};
    private final OrtEnvironment environment = OrtEnvironment.getEnvironment();
    private final OrtSession session;
    /** 重用的 ONNX 输入缓冲；识别调用仅发生在帧 worker。 */
    private final FloatBuffer modelInput = ByteBuffer.allocateDirect(INPUT_FLOATS * Float.BYTES)
        .order(ByteOrder.nativeOrder()).asFloatBuffer();
    /** Java 预处理先写入普通连续数组，完成后一次性复制到 ONNX 的 direct buffer。 */
    private final float[] preparedInput = new float[INPUT_FLOATS];
    /** 静止画面手动同步时重跑的最后模型输入。 */
    private final FloatBuffer lastInput = ByteBuffer.allocateDirect(INPUT_FLOATS * Float.BYTES)
        .order(ByteOrder.nativeOrder()).asFloatBuffer();
    /** 框选和投屏缓冲尺寸不变时复用，避免每帧重新计算双线性采样坐标。 */
    private SamplingPlan samplingPlan;
    private boolean hasLastInput;

    public PieceRecognizer(Context context, int modelThreads) throws Exception {
        if (modelThreads < 1 || modelThreads > 8)
            throw new IllegalArgumentException("模型线程数必须在 1 到 8 之间");
        byte[] model;
        try (InputStream input = context.getAssets().open("link/piece_classifier.onnx");
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[32 * 1024];
            for (int read; (read = input.read(buffer)) >= 0; )
                output.write(buffer, 0, read);
            model = output.toByteArray();
        }
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setIntraOpNumThreads(modelThreads);
        session = environment.createSession(model, options);
    }

    public void reset() {
        hasLastInput = false;
    }

    public ObservedBoard recognize(Image image, BoardGeometry geometry) throws Exception {
        FloatBuffer input = prepare(image, geometry);
        lastInput.clear();
        lastInput.put(input);
        lastInput.flip();
        hasLastInput = true;
        return recognizeInput(input);
    }

    public ObservedBoard recognizeLastInput() throws Exception {
        if (!hasLastInput)
            return null;
        return recognizeInput(lastInput);
    }

    private ObservedBoard recognizeInput(FloatBuffer input) throws Exception {
        input.rewind();
        try (OnnxTensor tensor = OnnxTensor.createTensor(environment, input, SHAPE);
             OrtSession.Result output = session.run(Collections.singletonMap("input", tensor))) {
            float[][] logits = (float[][]) output.get("logits").get().getValue();
            float[] flat = new float[90 * ObservedBoard.LABELS];
            for (int index = 0; index < logits.length; index++)
                System.arraycopy(logits[index], 0, flat, index * ObservedBoard.LABELS, ObservedBoard.LABELS);
            return ObservedBoard.fromLogits(flat);
        }
    }

    /** 直接从投屏 RGBA 图像填充 90 格模型输入，不创建 Bitmap 或单格对象。 */
    private FloatBuffer prepare(Image image, BoardGeometry geometry) {
        Image.Plane plane = image.getPlanes()[0];
        int width = image.getWidth();
        int height = image.getHeight();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        if (samplingPlan == null || !samplingPlan.matches(geometry, width, height, rowStride, pixelStride))
            samplingPlan = new SamplingPlan(geometry, width, height, rowStride, pixelStride);
        samplingPlan.fill(plane.getBuffer(), preparedInput);
        modelInput.clear();
        modelInput.put(preparedInput);
        modelInput.rewind();
        return modelInput;
    }

    private static int clamp(int value, int lower, int upper) {
        return Math.max(lower, Math.min(upper, value));
    }

    /**
     * 一个固定棋盘几何对应的双线性采样表。横向和纵向坐标彼此独立，故只需缓存
     * 9×48 与 10×48 项；填充阶段不再计算坐标、floor 或边界裁剪。
     */
    static final class SamplingPlan {
        private final float left, top, stepX, stepY;
        private final BoardGeometry geometry;
        private final int width, height, rowStride, pixelStride;
        private final int[] x0 = new int[Board.FILES * INPUT_SIZE];
        private final int[] x1 = new int[Board.FILES * INPUT_SIZE];
        private final int[] y0 = new int[Board.RANKS * INPUT_SIZE];
        private final int[] y1 = new int[Board.RANKS * INPUT_SIZE];
        private final float[] dx = new float[Board.FILES * INPUT_SIZE];
        private final float[] dy = new float[Board.RANKS * INPUT_SIZE];
        /** 覆盖全部采样点的紧凑 RGBA 区域；每帧只覆写内容，不重新分配。 */
        private final byte[] roi;
        private final int sourceOffset, roiRowBytes, roiRows;

        SamplingPlan(BoardGeometry geometry, int width, int height, int rowStride, int pixelStride) {
            this.geometry = geometry;
            this.left = geometry.left();
            this.top = geometry.top();
            this.stepX = geometry.stepX();
            this.stepY = geometry.stepY();
            this.width = width;
            this.height = height;
            this.rowStride = rowStride;
            this.pixelStride = pixelStride;
            for (int column = 0; column < Board.FILES; column++) {
                BoardGeometry.Rect cell = geometry.cell(column, 0);
                for (int pixel = 0; pixel < INPUT_SIZE; pixel++)
                    sampleAxis(cell.left() + (pixel + .5f) * (cell.right() - cell.left()) / INPUT_SIZE,
                        width, pixelStride, x0, x1, dx, column * INPUT_SIZE + pixel);
            }
            for (int row = 0; row < Board.RANKS; row++) {
                BoardGeometry.Rect cell = geometry.cell(0, row);
                for (int pixel = 0; pixel < INPUT_SIZE; pixel++)
                    sampleAxis(cell.top() + (pixel + .5f) * (cell.bottom() - cell.top()) / INPUT_SIZE,
                        height, rowStride, y0, y1, dy, row * INPUT_SIZE + pixel);
            }
            int minX = min(x0, x1), maxX = max(x0, x1);
            int minY = min(y0, y1), maxY = max(y0, y1);
            sourceOffset = minY + minX;
            roiRowBytes = maxX - minX + pixelStride;
            roiRows = (maxY - minY) / rowStride + 1;
            roi = new byte[Math.multiplyExact(roiRowBytes, roiRows)];
            for (int index = 0; index < x0.length; index++) {
                x0[index] -= minX;
                x1[index] -= minX;
            }
            for (int index = 0; index < y0.length; index++) {
                y0[index] = (y0[index] - minY) / rowStride * roiRowBytes;
                y1[index] = (y1[index] - minY) / rowStride * roiRowBytes;
            }
        }

        boolean matches(BoardGeometry geometry, int width, int height, int rowStride, int pixelStride) {
            return left == geometry.left() && top == geometry.top()
                && stepX == geometry.stepX() && stepY == geometry.stepY()
                && this.width == width && this.height == height
                && this.rowStride == rowStride && this.pixelStride == pixelStride;
        }

        /** 一次坐标/插值计算同时填充 RGB 三通道，输出仍为每格 NCHW。 */
        void fill(ByteBuffer pixels, float[] output) {
            copyRoi(pixels);
            for (int row = 0; row < Board.RANKS; row++)
                for (int column = 0; column < Board.FILES; column++) {
                    int base = (row * Board.FILES + column) * CHANNELS * PIXELS_PER_CELL;
                    for (int y = 0; y < INPUT_SIZE; y++) {
                        int yi = row * INPUT_SIZE + y;
                        for (int x = 0; x < INPUT_SIZE; x++) {
                            int xi = column * INPUT_SIZE + x;
                            int a = y0[yi] + x0[xi], b = y0[yi] + x1[xi];
                            int c = y1[yi] + x0[xi], d = y1[yi] + x1[xi];
                            int index = y * INPUT_SIZE + x;
                            for (int channel = 0; channel < CHANNELS; channel++) {
                                float va = pixel(roi, a + channel), vb = pixel(roi, b + channel);
                                float vc = pixel(roi, c + channel), vd = pixel(roi, d + channel);
                                float top = va + (vb - va) * dx[xi];
                                float bottom = vc + (vd - vc) * dx[xi];
                                float value = top + (bottom - top) * dy[yi];
                                output[base + channel * PIXELS_PER_CELL + index] =
                                    (value / 255f - MEAN[channel]) * INV_STD[channel];
                            }
                        }
                    }
            }
        }

        private void copyRoi(ByteBuffer pixels) {
            for (int row = 0; row < roiRows; row++) {
                pixels.position(sourceOffset + row * rowStride);
                pixels.get(roi, row * roiRowBytes, roiRowBytes);
            }
        }

        private static void sampleAxis(float value, int limit, int stride, int[] first, int[] second,
                                       float[] fraction, int index) {
            int lower = clamp((int) Math.floor(value), 0, limit - 1);
            first[index] = lower * stride;
            second[index] = Math.min(lower + 1, limit - 1) * stride;
            fraction[index] = value - lower;
        }

        private static int pixel(byte[] pixels, int offset) {
            return pixels[offset] & 255;
        }

        private static int min(int[] first, int[] second) {
            int result = Integer.MAX_VALUE;
            for (int value : first) result = Math.min(result, value);
            for (int value : second) result = Math.min(result, value);
            return result;
        }

        private static int max(int[] first, int[] second) {
            int result = Integer.MIN_VALUE;
            for (int value : first) result = Math.max(result, value);
            for (int value : second) result = Math.max(result, value);
            return result;
        }
    }

    @Override
    public void close() {
        try {
            session.close();
        } catch (Exception ignored) {
            // 关闭阶段不应阻止前台服务继续释放其余 Android 资源。
        }
    }
}
