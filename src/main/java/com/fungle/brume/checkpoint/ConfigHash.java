package com.fungle.brume.checkpoint;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Computes the file-level SHA-256 of {@code config.yaml} for checkpoint drift
 * detection (#25 / ADR-0037).
 *
 * <p>Hashes the raw file bytes <strong>after CR/LF normalisation</strong> — so a
 * round-trip through different line-ending conventions (commit checked out on
 * Windows then Linux) does not change the hash. The hash is intentionally
 * file-level (not post-loader struct) so that internal Brume default changes
 * across versions do not invalidate in-flight checkpoints.
 *
 * <p>Trade-off documented in ADR-0037 : permuting two entries in {@code
 * linked_columns} changes the file hash even though the semantics are identical
 * — we accept this false positive (refuses resume on a semantically-equivalent
 * config) to avoid the much worse false negative (resumes on a
 * semantically-different config).
 *
 * <p>Stateless utility — no Spring bean.
 */
public final class ConfigHash {

    private ConfigHash() {}

    /**
     * Computes the SHA-256 hex digest of {@code configPath} contents with CR/LF
     * normalised to LF. Returns a lowercase 64-char hex string.
     *
     * @throws CheckpointStore.CheckpointIoException if the file cannot be read or
     *                                               SHA-256 is unavailable on this JVM
     */
    public static String of(Path configPath) {
        try {
            String text = Files.readString(configPath, StandardCharsets.UTF_8);
            String normalised = text.replace("\r\n", "\n");
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha.digest(normalised.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (IOException e) {
            throw new CheckpointStore.CheckpointIoException(
                    "Failed to read config file for hashing: " + configPath, e);
        } catch (NoSuchAlgorithmException e) {
            throw new CheckpointStore.CheckpointIoException(
                    "SHA-256 not available on this JVM", e);
        }
    }
}
