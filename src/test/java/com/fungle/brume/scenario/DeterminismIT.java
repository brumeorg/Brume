package com.fungle.brume.scenario;

import com.fungle.brume.agent.ReplicationAgent;
import com.fungle.brume.command.CommandEnum;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #30 (A9) + #30b (A9b) — pipeline-level determinism : two consecutive
 * {@code brume execute} runs in DUMP mode with {@code --strip-timestamps} on the same
 * source must produce <strong>byte-identical</strong> dumps (raw SHA-256 match).
 *
 * <p>Out-of-the-box byte-identicality is guaranteed by {@code #30b} (livré 2026-05-13)
 * which forces {@code ORDER BY <pk>} in {@code CursorReader.streamRows} and
 * {@code readByPrimaryKeys}, and sorts parent rows by PK before writing in
 * {@code ChunkedTableProcessor.writeParentRows}. The test_brume fixture has a single-col
 * PK on every table so no fall-back is needed here.
 *
 * <p>Initial version of this IT (commit {@code d57b6b8}, #30 A9) used a canonical sort
 * of {@code COPY} block data lines because intra-table order was plan-dependent. Since
 * {@code #30b} eliminated that source of indeterminism, the test compares raw SHA-256.
 *
 * <p>Capitalises on #25c (LinkedHashMap stable table iteration order) and
 * #25d ({@code --strip-timestamps} omits the {@code -- generated_at} header line).
 *
 * <p><strong>Requires Docker</strong> : {@code brume-source} (5432) is read by Brume.
 * The target is not touched in DUMP mode but {@code replication.target.url} is still
 * required for boot wiring as of #6b (target DataSource exists for JDBC sinks).
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = {
        "brume.config-path=src/test/resources/test-config-integration.yaml",
        "brume.sink.type=DUMP",
        "brume.sink.output-path=target/determinism-it-dump.sql",
        "brume.sink.compression=NONE",
        "brume.sink.strip-timestamps=true",
        "replication.source.url=jdbc:postgresql://localhost:5432/postgres",
        "replication.target.url=jdbc:postgresql://localhost:5460/postgres",
        "replication.schema=test_brume",
        "replication.pgdump-path=docker exec -e PGPASSWORD=postgres brume-source pg_dump",
        "replication.pool-size=3"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DeterminismIT {

    private static final Path DUMP_PATH = Paths.get("target/determinism-it-dump.sql");

    @Autowired
    private ReplicationAgent agent;

    @BeforeAll
    void ensureCleanSlate() throws IOException {
        Files.deleteIfExists(DUMP_PATH);
        Files.createDirectories(DUMP_PATH.getParent());
    }

    @AfterAll
    void cleanup() throws IOException {
        Files.deleteIfExists(DUMP_PATH);
    }

    @Test
    @DisplayName("execute DUMP --strip-timestamps twice ⇒ raw SHA-256 of both dumps must match (no canonical sort needed since #30b)")
    void doubleRunDumpRawHashesMatch() throws Exception {
        agent.run(CommandEnum.EXECUTE);
        byte[] firstDump = Files.readAllBytes(DUMP_PATH);

        agent.run(CommandEnum.EXECUTE);
        byte[] secondDump = Files.readAllBytes(DUMP_PATH);

        // Sanity : neither dump may be empty — if both are empty they would trivially match
        // and we'd silently pass on a broken pipeline.
        assertThat(firstDump).as("first dump is non-empty").isNotEmpty();
        assertThat(secondDump).as("second dump is non-empty").isNotEmpty();

        String hash1 = sha256(firstDump);
        String hash2 = sha256(secondDump);

        assertThat(hash2)
                .as("Raw SHA-256 of two consecutive DUMP --strip-timestamps runs must match. "
                  + "#30b guarantees ORDER BY pk in CursorReader.streamRows + readByPrimaryKeys, "
                  + "plus a PK sort of parent rows in ChunkedTableProcessor.writeParentRows. "
                  + "Divergence here means a non-deterministic strategy, an unstable FK resolution "
                  + "order despite #30b, or drift in the dump structure (headers, DDL, COPY blocks).")
                .isEqualTo(hash1);
    }

    private static String sha256(byte[] content) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(content));
    }
}
