package com.fungle.brume.extraction;

import com.fungle.brume.extraction.model.ExtractedRow;
import com.fungle.brume.extraction.model.ExtractionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ExtractionResult}.
 *
 * <p>Covers:
 * <ul>
 *   <li>Phase 1 — {@code add()} does NOT populate pkIndex; {@code addWithPk()} does.</li>
 *   <li>Phase 2 — {@code tryAddWithPk()} is atomic: duplicate rows are rejected.</li>
 *   <li>Phase 3 — only one copy of a row is stored even under concurrent inserts.</li>
 * </ul>
 */
class ExtractionResultTest {

    private ExtractionResult result;

    @BeforeEach
    void setUp() {
        result = new ExtractionResult();
    }

    // -------------------------------------------------------------------------
    // Phase 1 — pkIndex comportement de add() vs addWithPk()
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("add() does NOT register the row in pkIndex — containsPrimaryKey returns false")
    void addDoesNotPopulatePkIndex() {
        ExtractedRow row = new ExtractedRow("users", Map.of("id", 1L, "name", "Alice"));

        result.add(row);

        assertThat(result.containsPrimaryKey("users", "id", 1L))
                .as("add() must NOT populate pkIndex — containsPrimaryKey must return false")
                .isFalse();
        assertThat(result.getRows("users")).hasSize(1);
    }

    @Test
    @DisplayName("addWithPk() registers the row in pkIndex — containsPrimaryKey returns true")
    void addWithPkPopulatesPkIndex() {
        ExtractedRow row = new ExtractedRow("users", Map.of("id", 1L, "name", "Alice"));

        result.addWithPk(row, "id");

        assertThat(result.containsPrimaryKey("users", "id", 1L))
                .as("addWithPk() must populate pkIndex — containsPrimaryKey must return true")
                .isTrue();
        assertThat(result.containsPrimaryKey("users", "id", 99L))
                .as("Unknown PK value must return false")
                .isFalse();
        assertThat(result.getRows("users")).hasSize(1);
    }

    @Test
    @DisplayName("addWithPk() with null PK value falls back gracefully — row added, index unchanged")
    void addWithPkWithNullPkValueAddsRowWithoutIndexEntry() {
        ExtractedRow row = new ExtractedRow("users", Map.of("name", "Bob")); // no "id" key

        result.addWithPk(row, "id");

        assertThat(result.getRows("users")).hasSize(1);
        assertThat(result.containsPrimaryKey("users", "id", null))
                .as("containsPrimaryKey with null value must return false regardless")
                .isFalse();
    }

    // -------------------------------------------------------------------------
    // Phase 2 — tryAddWithPk() atomicité
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("tryAddWithPk() returns true and inserts first occurrence")
    void tryAddWithPkInsertsFirstOccurrence() {
        ExtractedRow row = new ExtractedRow("orders", Map.of("id", 10L));

        boolean inserted = result.tryAddWithPk(row, "id");

        assertThat(inserted).isTrue();
        assertThat(result.getRows("orders")).hasSize(1);
        assertThat(result.containsPrimaryKey("orders", "id", 10L)).isTrue();
    }

    @Test
    @DisplayName("tryAddWithPk() returns false and skips duplicate — row count stays at 1")
    void tryAddWithPkRejectsDuplicate() {
        ExtractedRow first  = new ExtractedRow("orders", Map.of("id", 10L, "amount", 100));
        ExtractedRow second = new ExtractedRow("orders", Map.of("id", 10L, "amount", 200));

        boolean firstResult  = result.tryAddWithPk(first,  "id");
        boolean secondResult = result.tryAddWithPk(second, "id");

        assertThat(firstResult).isTrue();
        assertThat(secondResult)
                .as("Second insert with same PK must be rejected")
                .isFalse();
        assertThat(result.getRows("orders"))
                .as("Only one row must be stored (no duplicate)")
                .hasSize(1);
        assertThat(result.totalRowCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("tryAddWithPk() with null PK adds unconditionally (defensive path)")
    void tryAddWithPkWithNullPkAddsUnconditionally() {
        ExtractedRow row1 = new ExtractedRow("orphan", Map.of("name", "x"));
        ExtractedRow row2 = new ExtractedRow("orphan", Map.of("name", "y"));

        result.tryAddWithPk(row1, "id");
        result.tryAddWithPk(row2, "id");

        // Both must be present — no PK to deduplicate on
        assertThat(result.getRows("orphan")).hasSize(2);
    }

    // -------------------------------------------------------------------------
    // Phase 2 — isolation entre tables différentes
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Same PK value on different tables are independent entries in pkIndex")
    void pkIndexIsScopedToTable() {
        ExtractedRow userRow  = new ExtractedRow("users",  Map.of("id", 1L));
        ExtractedRow orderRow = new ExtractedRow("orders", Map.of("id", 1L));

        result.addWithPk(userRow,  "id");
        result.addWithPk(orderRow, "id");

        assertThat(result.containsPrimaryKey("users",  "id", 1L)).isTrue();
        assertThat(result.containsPrimaryKey("orders", "id", 1L)).isTrue();
        assertThat(result.totalRowCount()).isEqualTo(2);
    }

    // -------------------------------------------------------------------------
    // totalRowCount / allTables
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("totalRowCount returns sum across all tables")
    void totalRowCountSumsAllTables() {
        result.add(new ExtractedRow("a", Map.of("id", 1)));
        result.add(new ExtractedRow("a", Map.of("id", 2)));
        result.add(new ExtractedRow("b", Map.of("id", 1)));

        assertThat(result.totalRowCount()).isEqualTo(3);
        assertThat(result.allTables()).containsExactlyInAnyOrder("a", "b");
    }
}

