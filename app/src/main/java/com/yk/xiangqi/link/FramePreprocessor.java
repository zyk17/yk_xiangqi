package com.yk.xiangqi.link;

import android.media.Image;

import com.yk.xiangqi.core.Board;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * 从投屏 RGBA 图像直接填充 ONNX 输入，不创建 Bitmap 或单格对象。
 */
public final class FramePreprocessor {
    public static final int GATE_WIDTH = 90, GATE_HEIGHT = 100;
    private static final float[] MEAN = {.485f, .456f, .406f};
    private static final float[] INV_STD = {1f / .229f, 1f / .224f, 1f / .225f};
    private final FloatBuffer input = ByteBuffer.allocateDirect(90 * 3 * 48 * 48 * Float.BYTES)
        .order(ByteOrder.nativeOrder()).asFloatBuffer();

    /**
     * 以屏幕左上至右下的固定顺序填充 90 格输入。
     *
     * <p>模型逐格分类，朝向不属于模型输入；识别后只重排 90 个标签即可。</p>
     */
    public FloatBuffer input(Image image, BoardGeometry geometry) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer pixels = plane.getBuffer();
        int width = image.getWidth();
        int height = image.getHeight();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        input.clear();
        for (int row = 0; row < Board.RANKS; row++)
            for (int column = 0; column < Board.FILES; column++) {
                BoardGeometry.Rect cell = geometry.cell(column, row);
                for (int channel = 0; channel < 3; channel++)
                    for (int y = 0; y < 48; y++)
                        for (int x = 0; x < 48; x++) {
                            float sourceX = cell.left() + (x + .5f) * (cell.right() - cell.left()) / 48f;
                            float sourceY = cell.top() + (y + .5f) * (cell.bottom() - cell.top()) / 48f;
                            input.put((sample(pixels, rowStride, pixelStride, width, height, sourceX, sourceY, channel) / 255f - MEAN[channel]) * INV_STD[channel]);
                        }
            }
        input.rewind();
        return input;
    }

    public void luma(Image image, BoardGeometry geometry, float[] out) {
        if (out.length != GATE_WIDTH * GATE_HEIGHT)
            throw new IllegalArgumentException("灰度采样尺寸无效");
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer pixels = plane.getBuffer();
        BoardGeometry.Rect outer = new BoardGeometry.Rect(geometry.cell(0, 0).left(), geometry.cell(0, 0).top(), geometry.cell(8, 9).right(), geometry.cell(8, 9).bottom());
        for (int y = 0; y < GATE_HEIGHT; y++)
            for (int x = 0; x < GATE_WIDTH; x++) {
                int px = clamp(Math.round(outer.left() + (x + .5f) * (outer.right() - outer.left()) / GATE_WIDTH), 0, image.getWidth() - 1);
                int py = clamp(Math.round(outer.top() + (y + .5f) * (outer.bottom() - outer.top()) / GATE_HEIGHT), 0, image.getHeight() - 1);
                int offset = py * plane.getRowStride() + px * plane.getPixelStride();
                out[y * GATE_WIDTH + x] = ((pixels.get(offset) & 255) + (pixels.get(offset + 1) & 255) + (pixels.get(offset + 2) & 255)) / (3f * 255f);
            }
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
}
