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

/** 内置 FP32 MobileNetV3 的单线程 ONNX Runtime CPU 推理器。 */
public final class PieceRecognizer implements AutoCloseable {
    private static final long[] SHAPE = {90, 3, 48, 48};
    private static final int INPUT_FLOATS = Board.SQUARES * 3 * 48 * 48;
    private static final float[] MEAN = {.485f, .456f, .406f};
    private static final float[] INV_STD = {1f / .229f, 1f / .224f, 1f / .225f};
    private final OrtEnvironment environment = OrtEnvironment.getEnvironment();
    private final OrtSession session;
    /** 重用的 ONNX 输入缓冲；识别调用仅发生在帧 worker。 */
    private final FloatBuffer modelInput = ByteBuffer.allocateDirect(INPUT_FLOATS * Float.BYTES)
        .order(ByteOrder.nativeOrder()).asFloatBuffer();
    /** 静止画面手动同步时重跑的最后模型输入。 */
    private final FloatBuffer lastInput = ByteBuffer.allocateDirect(INPUT_FLOATS * Float.BYTES)
        .order(ByteOrder.nativeOrder()).asFloatBuffer();
    private boolean hasLastInput;

    public PieceRecognizer(Context context) throws Exception {
        byte[] model;
        try (InputStream input = context.getAssets().open("link/piece_classifier.onnx");
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[32 * 1024];
            for (int read; (read = input.read(buffer)) >= 0; )
                output.write(buffer, 0, read);
            model = output.toByteArray();
        }
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setIntraOpNumThreads(1);
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
        return hasLastInput ? recognizeInput(lastInput) : null;
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
        ByteBuffer pixels = plane.getBuffer();
        int width = image.getWidth();
        int height = image.getHeight();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        modelInput.clear();
        for (int row = 0; row < Board.RANKS; row++)
            for (int column = 0; column < Board.FILES; column++) {
                BoardGeometry.Rect cell = geometry.cell(column, row);
                for (int channel = 0; channel < 3; channel++)
                    for (int y = 0; y < 48; y++)
                        for (int x = 0; x < 48; x++) {
                            float sourceX = cell.left() + (x + .5f) * (cell.right() - cell.left()) / 48f;
                            float sourceY = cell.top() + (y + .5f) * (cell.bottom() - cell.top()) / 48f;
                            modelInput.put((sample(pixels, rowStride, pixelStride, width, height, sourceX, sourceY, channel) / 255f - MEAN[channel]) * INV_STD[channel]);
                        }
            }
        modelInput.rewind();
        return modelInput;
    }

    private static float sample(ByteBuffer data, int rowStride, int pixelStride, int width, int height, float x, float y, int channel) {
        int x0 = clamp((int) Math.floor(x), 0, width - 1), y0 = clamp((int) Math.floor(y), 0, height - 1);
        int x1 = Math.min(x0 + 1, width - 1), y1 = Math.min(y0 + 1, height - 1);
        float dx = x - x0, dy = y - y0;
        float a = pixel(data, rowStride, pixelStride, x0, y0, channel), b = pixel(data, rowStride, pixelStride, x1, y0, channel);
        float c = pixel(data, rowStride, pixelStride, x0, y1, channel), d = pixel(data, rowStride, pixelStride, x1, y1, channel);
        return (a + (b - a) * dx) + ((c + (d - c) * dx) - (a + (b - a) * dx)) * dy;
    }

    private static int pixel(ByteBuffer data, int rowStride, int pixelStride, int x, int y, int channel) {
        return data.get(y * rowStride + x * pixelStride + channel) & 255;
    }

    private static int clamp(int value, int lower, int upper) {
        return Math.max(lower, Math.min(upper, value));
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
