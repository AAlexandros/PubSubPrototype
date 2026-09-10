package org.pubsub.prototype.telemetry;

import org.apache.parquet.io.OutputFile;
import org.apache.parquet.io.PositionOutputStream;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

final class LocalOutputFile implements OutputFile {
    private final Path path;
    LocalOutputFile(Path path) { this.path = path; }
    @Override public PositionOutputStream create(long blockSizeHint) throws IOException {
        return stream(StandardOpenOption.CREATE_NEW);
    }
    @Override public PositionOutputStream createOrOverwrite(long blockSizeHint) throws IOException {
        return stream(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }
    @Override public boolean supportsBlockSize() { return false; }
    @Override public long defaultBlockSize() { return 0; }
    private PositionOutputStream stream(StandardOpenOption... options) throws IOException {
        Files.createDirectories(path.getParent());
        return new Positioned(Files.newOutputStream(path, options));
    }
    private static final class Positioned extends PositionOutputStream {
        private final OutputStream delegate;
        private long position;
        private Positioned(OutputStream delegate) { this.delegate = delegate; }
        @Override public long getPos() { return position; }
        @Override public void write(int b) throws IOException { delegate.write(b); position++; }
        @Override public void write(byte[] b, int off, int len) throws IOException { delegate.write(b, off, len); position += len; }
        @Override public void flush() throws IOException { delegate.flush(); }
        @Override public void close() throws IOException { delegate.close(); }
    }
}
