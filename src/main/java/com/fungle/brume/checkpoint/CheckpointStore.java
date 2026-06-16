package com.fungle.brume.checkpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/**
 * Read/write for {@link CheckpointState} with atomic-write semantics (#25 / A19).
 *
 * <p>Write path : serialise to {@code <path>.tmp}, force fsync via
 * {@link FileChannel#force(boolean)}, then {@code Files.move ATOMIC_MOVE}. On
 * POSIX + NTFS the move is atomic ; on filesystems that refuse atomic move
 * (network shares, FAT) we fall back to a regular move with a WARN log — the
 * resulting checkpoint is still valid but a crash mid-rename leaves a stale
 * {@code .tmp} that the next write overwrites.
 *
 * <p>Read path : returns {@link Optional#empty()} if the file does not exist
 * (fresh run) ; throws {@link CheckpointIoException} on parse error or schema
 * version mismatch. The schema version field is checked early to surface
 * cross-version incompatibility before the rest of the record is parsed.
 *
 * <p>Stateless helper — instantiated by {@code CheckpointService} with a single
 * {@link ObjectMapper} shared by the entire run.
 */
public final class CheckpointStore {

    private static final Logger log = LoggerFactory.getLogger(CheckpointStore.class);

    private final Path path;
    private final ObjectMapper mapper;

    /**
     * Constructs a store for the given path using the injected mapper. The mapper
     * must support {@code Instant} serialization — the Spring-provided one (from
     * {@code JacksonConfig}) registers {@code JavaTimeModule} and disables
     * timestamp-as-millis (ADR-0012 / B5 anti-regression).
     */
    public CheckpointStore(Path path, ObjectMapper mapper) {
        this.path = path;
        this.mapper = pretty(mapper);
    }

    /**
     * Returns a copy of {@code source} with {@code INDENT_OUTPUT} enabled and
     * {@code WRITE_DATES_AS_TIMESTAMPS} disabled — checkpoint readability hint
     * (human eyes will look at this file when debugging a crash) without mutating
     * the shared Spring bean.
     */
    private static ObjectMapper pretty(ObjectMapper source) {
        ObjectMapper copy = source.copy();
        copy.enable(SerializationFeature.INDENT_OUTPUT);
        copy.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return copy;
    }

    /**
     * Reads the checkpoint file at this store's path. Returns empty when the file
     * is absent (fresh run). Throws on parse error or unsupported schema version.
     */
    public Optional<CheckpointState> read() {
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            CheckpointState state = mapper.readValue(content, CheckpointState.class);
            if (!CheckpointState.CURRENT_SCHEMA_VERSION.equals(state.schemaVersion())) {
                throw new CheckpointIoException(
                        "Checkpoint file at " + path + " has schemaVersion="
                                + state.schemaVersion() + " ; this Brume build expects "
                                + CheckpointState.CURRENT_SCHEMA_VERSION
                                + ". Re-run without --resume to start fresh.");
            }
            return Optional.of(state);
        } catch (IOException e) {
            throw new CheckpointIoException(
                    "Failed to read checkpoint file " + path + ": " + e.getMessage(), e);
        }
    }

    /**
     * Writes the state atomically. The visible {@link #path()} is left intact until
     * the move succeeds — a concurrent reader (impossible in V1 single-process, but
     * good hygiene) either sees the previous state or the new state, never a torn
     * write.
     */
    public void write(CheckpointState state) {
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            byte[] bytes = mapper.writeValueAsBytes(state);
            try (FileChannel ch = FileChannel.open(tmp,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ch.write(java.nio.ByteBuffer.wrap(bytes));
                ch.force(true);
            }
            try {
                Files.move(tmp, path,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                log.warn("Filesystem at {} does not support atomic move ; "
                                + "falling back to non-atomic replace. A crash mid-rename "
                                + "may leave a stale .tmp that the next write overwrites.",
                        path.getParent());
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // Best-effort cleanup of the tmp file ; ignore secondary failures.
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) { /* */ }
            throw new CheckpointIoException(
                    "Failed to write checkpoint file " + path + ": " + e.getMessage(), e);
        }
    }

    /** Returns the absolute path of the checkpoint file. */
    public Path path() {
        return path;
    }

    /** Wraps any I/O or parse failure during checkpoint read/write. */
    public static final class CheckpointIoException extends RuntimeException {
        public CheckpointIoException(String message) {
            super(message);
        }
        public CheckpointIoException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
