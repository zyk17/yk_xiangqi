package com.yk.xiangqi.engine;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.UUID;

/** 将内置引擎安装、并把用户选择的资源导入应用私有目录。 */
public final class EngineFiles {
    private EngineFiles() {}

    public static File installBundled(Context context) throws IOException {
        File directory = new File(context.getFilesDir(), "engines/pikafish");
        ensureDirectory(directory);

        File executable = new File(directory, "pikafish");
        copyAsset(context, "engine/pikafish", executable);
        copyAsset(context, "engine/pikafish.nnue", new File(directory, "pikafish.nnue"));
        requireArm64Elf(executable);
        makeExecutable(executable);
        return directory;
    }

    public static File importBundle(Context context, Uri engine, Uri nnue) throws IOException {
        File directory = newCustomDirectory(context);
        boolean complete = false;
        try {
            File executable = new File(directory, "engine");
            copyUri(context, engine, executable);
            requireArm64Elf(executable);
            makeExecutable(executable);
            if (nnue != null)
                copyUri(context, nnue, new File(directory, "network.nnue"));
            complete = true;
            return directory;
        } finally {
            if (!complete)
                deleteIncompleteBundle(directory);
        }
    }

    /** 将可选 NNUE 放入已导入引擎的工作目录。 */
    public static File importNetwork(Context context, Uri nnue, File directory) throws IOException {
        Objects.requireNonNull(directory, "directory");
        if (!new File(directory, "engine").isFile())
            throw new IOException("尚未导入自定义引擎");
        File network = new File(directory, "network.nnue");
        copyUri(context, nnue, network);
        return network;
    }

    private static File newCustomDirectory(Context context) throws IOException {
        File parent = new File(context.getFilesDir(), "engines");
        ensureDirectory(parent);
        File directory = new File(parent, "custom-" + UUID.randomUUID());
        ensureDirectory(directory);
        return directory;
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (directory.isDirectory())
            return;
        if (directory.exists() || !directory.mkdirs())
            throw new IOException("无法创建引擎目录: " + directory);
    }

    private static void copyAsset(Context context, String asset, File output) throws IOException {
        if (output.isFile() && output.length() > 0)
            return;
        try (InputStream input = context.getAssets().open(asset)) {
            copy(input, output);
        }
    }

    private static void copyUri(Context context, Uri uri, File output) throws IOException {
        InputStream input = context.getContentResolver().openInputStream(uri);
        if (input == null)
            throw new IOException("无法读取文件");
        try (input) {
            copy(input, output);
        }
    }

    /** 先写同目录临时文件，再替换目标，避免中断时留下半个资源。 */
    private static void copy(InputStream input, File output) throws IOException {
        File directory = output.getParentFile();
        if (directory == null)
            throw new IOException("资源目标没有父目录");
        ensureDirectory(directory);

        File temporary = File.createTempFile(output.getName() + ".", ".tmp", directory);
        try {
            try (OutputStream stream = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[32 * 1024];
                for (int read; (read = input.read(buffer)) >= 0;)
                    stream.write(buffer, 0, read);
            }
            replace(temporary, output);
        } finally {
            if (temporary.exists())
                temporary.delete();
        }
    }

    private static void replace(File temporary, File output) throws IOException {
        try {
            Files.move(temporary.toPath(), output.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void makeExecutable(File executable) throws IOException {
        if (!executable.setExecutable(true, true) || !executable.canExecute())
            throw new IOException("无法设置引擎可执行权限");
    }

    private static void requireArm64Elf(File executable) throws IOException {
        try (InputStream input = new FileInputStream(executable)) {
            byte[] header = new byte[20];
            int size = 0;
            while (size < header.length) {
                int read = input.read(header, size, header.length - size);
                if (read < 0)
                    break;
                size += read;
            }
            boolean valid = size == header.length
                && header[0] == 0x7f && header[1] == 'E' && header[2] == 'L' && header[3] == 'F'
                && header[4] == 2 && header[5] == 1 && header[6] == 1
                && header[18] == (byte) 0xb7 && header[19] == 0;
            if (!valid)
                throw new IOException("仅支持 arm64-v8a ELF 可执行文件");
        }
    }

    private static void deleteIncompleteBundle(File directory) {
        new File(directory, "network.nnue").delete();
        new File(directory, "engine").delete();
        directory.delete();
    }
}
