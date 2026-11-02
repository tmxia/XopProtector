package com.yqsh.protector.packer;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;

/**
 * Java 11 Files.writeString/readString 的 Java 8 兼容实现。
 * Android 的 java.nio.file.Files 只有 Java 8 方法集，打包端须走这里。
 */
final class CompatFiles {
    private CompatFiles() {
    }

    static Path writeString(Path path, CharSequence csq, Charset cs, OpenOption... options)
            throws IOException {
        byte[] bytes = csq.toString().getBytes(cs);
        return Files.write(path, bytes, options);
    }

    static String readString(Path path, Charset cs) throws IOException {
        return new String(Files.readAllBytes(path), cs);
    }
}
