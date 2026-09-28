package com.yk.xiangqi.notation;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * PGN 文件的字节边界。优先按 UTF-8 严格解码，失败时兼容中国象棋软件常见的 GBK/GB18030。
 * 调用方持有并关闭传入的流。
 */
public final class PgnIo {
    private static final Charset GB18030 = Charset.forName("GB18030");

    private PgnIo() {
    }

    public static PgnGame read(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        return parse(readBytes(input));
    }

    public static PgnGame parse(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        return Pgn.parse(decode(bytes));
    }

    /** 默认以 UTF-8（无 BOM）导出，避免继续产生依赖系统代码页的文件。 */
    public static byte[] write(PgnGame game) {
        return Pgn.write(game).getBytes(StandardCharsets.UTF_8);
    }

    /** 按调用方明确指定的字符集写出，例如与旧软件互通时使用 GB18030。 */
    public static void write(PgnGame game, OutputStream output, Charset charset) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(charset, "charset");
        output.write(encode(Pgn.write(game), charset));
    }

    private static String decode(byte[] bytes) {
        int start = hasUtf8Bom(bytes) ? 3 : 0;
        try {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
            return decoder.decode(ByteBuffer.wrap(bytes, start, bytes.length - start)).toString();
        } catch (CharacterCodingException ignored) {
            return new String(bytes, start, bytes.length - start, GB18030);
        }
    }

    private static boolean hasUtf8Bom(byte[] bytes) {
        return bytes.length >= 3 && bytes[0] == (byte) 0xef && bytes[1] == (byte) 0xbb && bytes[2] == (byte) 0xbf;
    }

    private static byte[] encode(String text, Charset charset) throws IOException {
        try {
            ByteBuffer encoded = charset.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(text));
            byte[] result = new byte[encoded.remaining()];
            encoded.get(result);
            return result;
        } catch (CharacterCodingException error) {
            throw new IOException("PGN 无法使用 " + charset.displayName() + " 编码", error);
        }
    }

    private static byte[] readBytes(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int count; (count = input.read(buffer)) >= 0;)
            output.write(buffer, 0, count);
        return output.toByteArray();
    }
}
