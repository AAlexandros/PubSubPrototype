package org.pubsub.prototype.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.pubsub.prototype.util.Validators.requireNonNull;

/** Shared UTF-8 text-file operations with parent-directory creation and unchecked I/O failures. */
public final class TextFiles {
    private TextFiles() {
    }

    /** Creates the parent directory for a file and returns the supplied path. */
    public static Path prepare(Path file) {
        requireNonNull(file, "file");
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            return file;
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to prepare file " + file, ex);
        }
    }

    /** Reads an entire UTF-8 text file. */
    public static String read(Path file) {
        requireNonNull(file, "file");
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read text file " + file, ex);
        }
    }

    /** Creates missing parent directories and writes an entire UTF-8 text file. */
    public static Path write(Path file, String content) {
        requireNonNull(content, "content");
        prepare(file);
        try {
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return file;
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to write text file " + file, ex);
        }
    }

    /** Creates missing parent directories and appends UTF-8 text, creating the file when necessary. */
    public static Path append(Path file, String content) {
        requireNonNull(content, "content");
        prepare(file);
        try {
            Files.writeString(
                    file,
                    content,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
            return file;
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to append text file " + file, ex);
        }
    }
}
