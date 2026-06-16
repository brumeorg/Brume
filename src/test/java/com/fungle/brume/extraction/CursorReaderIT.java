package com.fungle.brume.extraction;

import com.fungle.brume.agent.ReplicationAgent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "brume.config-path=src/test/resources/test-config-integration.yaml",
        "brume.pipeline-mode=STREAMING",
        "replication.source.url=jdbc:postgresql://localhost:5432/postgres",
        "replication.target.url=jdbc:postgresql://localhost:5460/postgres"
})
class CursorReaderIT {

    @MockitoBean
    private ReplicationAgent replicationAgent;

    @Autowired
    private CursorReader cursorReader;

    @Test
    @DisplayName("streamRows lit ligne par ligne avec un petit fetch size")
    void shouldStreamFilteredRowsWithSmallFetchSize() {
        List<Long> orderIds = new ArrayList<>();

        cursorReader.streamRows(
                "test_brume",
                "orders",
                "created_at >= '2025-01-01'",
                2,
                row -> orderIds.add((Long) row.data().get("id"))
        );

        assertThat(orderIds)
                .containsExactlyInAnyOrder(103L, 104L, 105L, 106L, 107L, 108L, 109L, 110L)
                .hasSize(8);
    }

    @Test
    @DisplayName("readDistinctColumnValues retourne uniquement les FK distinctes de la fenêtre filtrée")
    void shouldCollectDistinctFkValuesForFilteredTable() {
        Set<Object> userIds = cursorReader.readDistinctColumnValues(
                "test_brume",
                "orders",
                "user_id",
                "created_at >= '2025-01-01'"
        );

        assertThat(userIds)
                .containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L)
                .hasSize(5);
    }

    @Test
    @DisplayName("readChunked découpe une lecture en chunks explicites")
    void shouldReadRowsInExplicitChunks() {
        List<Integer> chunkSizes = new ArrayList<>();
        List<Long> ids = new ArrayList<>();

        cursorReader.readChunked("test_brume", "products", null, 2, chunk -> {
            chunkSizes.add(chunk.size());
            chunk.forEach(row -> ids.add((Long) row.data().get("id")));
        });

        assertThat(chunkSizes).containsExactly(2, 2, 1);
        assertThat(ids).containsExactlyInAnyOrder(10L, 11L, 12L, 13L, 14L);
    }

    @Test
    @DisplayName("readChunked invoque le consumer au fil de l'eau")
    void shouldInvokeChunkConsumerIncrementally() {
        AtomicInteger callbackCount = new AtomicInteger();
        List<Integer> observedSizes = new ArrayList<>();

        cursorReader.readChunked("test_brume", "products", null, 2, chunk -> {
            observedSizes.add(chunk.size());
            callbackCount.incrementAndGet();
            assertThat(callbackCount.get()).isLessThanOrEqualTo(3);
        });

        assertThat(callbackCount.get()).isEqualTo(3);
        assertThat(observedSizes).containsExactly(2, 2, 1);
    }
}
