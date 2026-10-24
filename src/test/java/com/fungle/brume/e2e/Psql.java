package com.fungle.brume.e2e;

import com.github.luben.zstd.ZstdInputStream;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

/**
 * Feeds a SQL file to {@code psql}, the way an operator restores a brume dump or loads a
 * sample database (ADR-0051). Plain, {@code .gz} and {@code .zst} files are accepted.
 */
final class Psql {

    /**
     * Error printed when the local {@code pg_dump} is newer than the server : PostgreSQL 17+
     * dumps start with {@code SET transaction_timeout}, unknown to a 16 server. Harmless — CI
     * pairs pg_dump 16 with postgres:16 and never sees it.
     */
    private static final String NEWER_PG_DUMP_NOISE = "unrecognized configuration parameter \"transaction_timeout\"";

    private Psql() {
    }

    /** Runs {@code file} against {@code db} and returns the {@code ERROR} lines psql printed. */
    static List<String> run(E2eSettings.Db db, Path file) {
        try (InputStream in = open(file)) {
            return run(db, in, file.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    /** Runs the {@code sql} script against {@code db} and returns the {@code ERROR} lines psql printed. */
    static List<String> run(E2eSettings.Db db, String sql, String label) {
        return run(db, new ByteArrayInputStream(sql.getBytes(StandardCharsets.UTF_8)), label);
    }

    private static List<String> run(E2eSettings.Db db, InputStream script, String label) {
        ProcessBuilder pb = new ProcessBuilder("psql", "-X", "-q", "-v", "ON_ERROR_STOP=0",
                "-h", db.host(), "-p", String.valueOf(db.port()), "-U", db.user(), "-d", db.database())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.environment().put("PGPASSWORD", db.password());
        try {
            Process psql = pb.start();
            ByteArrayOutputStream errors = new ByteArrayOutputStream();
            Thread drain = Thread.ofVirtual().start(() -> {
                try (InputStream err = psql.getErrorStream()) {
                    err.transferTo(errors);
                } catch (IOException ignored) {
                    // psql ended — whatever was read is kept
                }
            });
            try (OutputStream stdin = psql.getOutputStream()) {
                script.transferTo(stdin);
            }
            if (!psql.waitFor(5, TimeUnit.MINUTES)) {
                psql.destroyForcibly();
                throw new AssertionError("psql did not finish loading " + label);
            }
            drain.join();
            return errors.toString(StandardCharsets.UTF_8).lines()
                    .filter(line -> line.contains("ERROR"))
                    .filter(line -> !line.contains(NEWER_PG_DUMP_NOISE))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot run psql on " + label, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    static InputStream open(Path file) throws IOException {
        InputStream raw = new BufferedInputStream(Files.newInputStream(file));
        String name = file.getFileName().toString();
        if (name.endsWith(".gz")) return new GZIPInputStream(raw);
        if (name.endsWith(".zst")) return new ZstdInputStream(raw);
        return raw;
    }
}
