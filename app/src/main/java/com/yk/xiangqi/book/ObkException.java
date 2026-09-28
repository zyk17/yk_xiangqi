package com.yk.xiangqi.book;

/** OBK 文件或其 SQLite 数据库不可用。 */
public final class ObkException extends Exception {
    ObkException(String message) {
        super(message);
    }

    ObkException(String message, Throwable cause) {
        super(message, cause);
    }
}
