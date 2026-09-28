package com.yk.xiangqi.link;

import android.content.Context;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.Collections;

/** 内置 FP32 MobileNetV3 的单线程 ONNX Runtime CPU 推理器。 */
public final class PieceRecognizer implements AutoCloseable {
    private static final long[] SHAPE = {90, 3, 48, 48};
    private final OrtEnvironment environment = OrtEnvironment.getEnvironment();
    private final OrtSession session;

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

    public ObservedBoard recognize(FloatBuffer input) throws Exception {
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

    @Override
    public void close() {
        try {
            session.close();
        } catch (Exception ignored) {
            // 关闭阶段不应阻止前台服务继续释放其余 Android 资源。
        }
    }
}
