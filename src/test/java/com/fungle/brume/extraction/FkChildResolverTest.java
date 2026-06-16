package com.fungle.brume.extraction;

import com.fungle.brume.extraction.model.ExtractedRow;
import com.fungle.brume.extraction.model.ExtractionResult;
import com.fungle.brume.report.ExecutionReport;
import com.fungle.brume.schema.model.DatabaseSchema;
import com.fungle.brume.schema.model.ForeignKey;
import com.fungle.brume.schema.model.TableMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link FkChildResolver}.
 *
 * <p>Covers the core scenarios for bidirectional FK resolution:
 * direct child rows fetched, depth bounding, self-reference guard,
 * deduplication via pkIndex, and fail-fast on DB error.
 */
class FkChildResolverTest {

    private CursorReader cursorReader;
    private FkChildResolver resolver;
    private ExecutionReport report;

    @BeforeEach
    void setUp() {
        cursorReader = mock(CursorReader.class);
        resolver = new FkChildResolver(cursorReader);
        report = new ExecutionReport("test", "test");
    }

    /**
     * Core scenario — dossier has a FK to utilisateur.
     * Seeded utilisateur row must cause dossier children to be fetched.
     */
    @Test
    @DisplayName("Direct child rows are fetched when parent seed is in result")
    void directChildRowsFetched() {
        // Arrange — dossier.id_utilisateur → utilisateur.id
        ForeignKey fk = new ForeignKey("dossier", "id_utilisateur", "utilisateur", "id");
        TableMetadata utilisateurMeta = new TableMetadata("utilisateur", List.of(), List.of(), "id");
        TableMetadata dossierMeta = new TableMetadata("dossier", List.of(), List.of(fk), "id");
        DatabaseSchema schema = new DatabaseSchema(
                Map.of("utilisateur", utilisateurMeta, "dossier", dossierMeta));

        ExtractionResult result = new ExtractionResult();
        result.addWithPk(new ExtractedRow("utilisateur", Map.of("id", 42L, "nom", "BORNAND")), "id");

        ExtractedRow dossierRow = new ExtractedRow("dossier", Map.of("id", 100L, "id_utilisateur", 42L));
        when(cursorReader.readByPrimaryKeys(anyString(), eq("dossier"), eq("id_utilisateur"), any()))
                .thenReturn(List.of(dossierRow));

        // Act
        resolver.resolve(result, schema, "events", 1, report);

        // Assert
        assertThat(result.getRows("dossier")).hasSize(1);
        assertThat(result.containsPrimaryKey("dossier", "id", 100L)).isTrue();
    }

    /**
     * Self-referential FK (utilisateur.id_createur → utilisateur.id) must not cause
     * an infinite loop. With depth=1, only one pass runs.
     */
    @Test
    @DisplayName("Self-referential FK does not loop — bounded by maxDepth=1")
    void selfReferentialFkDoesNotLoop() {
        // Arrange — utilisateur has a self-ref FK
        ForeignKey selfRef = new ForeignKey("utilisateur", "id_createur", "utilisateur", "id");
        TableMetadata utilisateurMeta = new TableMetadata("utilisateur", List.of(), List.of(selfRef), "id");
        DatabaseSchema schema = new DatabaseSchema(Map.of("utilisateur", utilisateurMeta));

        ExtractionResult result = new ExtractionResult();
        result.addWithPk(new ExtractedRow("utilisateur", Map.of("id", 42L, "nom", "BORNAND")), "id");

        // Creator row (a different user)
        ExtractedRow creatorRow = new ExtractedRow("utilisateur", Map.of("id", 1L, "nom", "ADMIN", "id_createur", 0L));
        when(cursorReader.readByPrimaryKeys(anyString(), eq("utilisateur"), eq("id_createur"), argThat(c -> c.contains(42L))))
                .thenReturn(List.of(creatorRow));
        // Second pass: creator's creator (id_createur=0) is a new PK but returns empty
        when(cursorReader.readByPrimaryKeys(anyString(), eq("utilisateur"), eq("id_createur"), argThat(c -> c.contains(1L))))
                .thenReturn(List.of());

        // Act — depth=1: only one expansion of utilisateur PKs (the initial seed, id=42)
        resolver.resolve(result, schema, "events", 1, report);

        // Assert — only one call with the original seed PK (42); the newly added row (1) is NOT expanded
        verify(cursorReader, times(1))
                .readByPrimaryKeys(anyString(), eq("utilisateur"), eq("id_createur"), any());
        assertThat(result.getRows("utilisateur")).hasSize(2); // BORNAND + ADMIN
    }

    /**
     * A table with no inverse FK references should produce no child fetch calls.
     */
    @Test
    @DisplayName("No child tables referencing seed — no DB calls made")
    void noChildTablesForSeed() {
        // Arrange — pays has no other table pointing to it
        TableMetadata paysMeta = new TableMetadata("pays", List.of(), List.of(), "id");
        DatabaseSchema schema = new DatabaseSchema(Map.of("pays", paysMeta));

        ExtractionResult result = new ExtractionResult();
        result.addWithPk(new ExtractedRow("pays", Map.of("id", 5L, "nom", "France")), "id");

        // Act
        resolver.resolve(result, schema, "public", 3, report);

        // Assert — no DB calls at all
        verify(cursorReader, never()).readByPrimaryKeys(anyString(), anyString(), anyString(), any());
        assertThat(result.totalRowCount()).isEqualTo(1);
    }

    /**
     * A child row already present in pkIndex (via a previous resolution path) must not be duplicated.
     */
    @Test
    @DisplayName("Child row already in pkIndex is not duplicated")
    void childAlreadyInPkIndexIsNotDuplicated() {
        ForeignKey fk = new ForeignKey("dossier", "id_utilisateur", "utilisateur", "id");
        TableMetadata utilisateurMeta = new TableMetadata("utilisateur", List.of(), List.of(), "id");
        TableMetadata dossierMeta = new TableMetadata("dossier", List.of(), List.of(fk), "id");
        DatabaseSchema schema = new DatabaseSchema(
                Map.of("utilisateur", utilisateurMeta, "dossier", dossierMeta));

        ExtractionResult result = new ExtractionResult();
        result.addWithPk(new ExtractedRow("utilisateur", Map.of("id", 42L)), "id");
        // dossier 100 already loaded (e.g. as a direct seed)
        result.addWithPk(new ExtractedRow("dossier", Map.of("id", 100L, "id_utilisateur", 42L)), "id");

        ExtractedRow dossierRow = new ExtractedRow("dossier", Map.of("id", 100L, "id_utilisateur", 42L));
        when(cursorReader.readByPrimaryKeys(anyString(), eq("dossier"), eq("id_utilisateur"), any()))
                .thenReturn(List.of(dossierRow));

        resolver.resolve(result, schema, "events", 1, report);

        // Still exactly 1 dossier row
        assertThat(result.getRows("dossier")).hasSize(1);
        assertThat(result.totalRowCount()).isEqualTo(2);
    }

    /**
     * Regression guard — a DB error inside a child fetch task must abort the pipeline.
     * A partial result with missing child FK references would be silently inconsistent.
     */
    @Test
    @DisplayName("DB error during child fetch aborts the pipeline (fail-fast)")
    void dbErrorDuringChildFetchAbortsPipeline() {
        ForeignKey fk = new ForeignKey("dossier", "id_utilisateur", "utilisateur", "id");
        TableMetadata utilisateurMeta = new TableMetadata("utilisateur", List.of(), List.of(), "id");
        TableMetadata dossierMeta = new TableMetadata("dossier", List.of(), List.of(fk), "id");
        DatabaseSchema schema = new DatabaseSchema(
                Map.of("utilisateur", utilisateurMeta, "dossier", dossierMeta));

        ExtractionResult result = new ExtractionResult();
        result.addWithPk(new ExtractedRow("utilisateur", Map.of("id", 42L)), "id");

        when(cursorReader.readByPrimaryKeys(anyString(), eq("dossier"), eq("id_utilisateur"), any()))
                .thenThrow(new org.springframework.dao.QueryTimeoutException("simulated timeout"));

        assertThatThrownBy(() -> resolver.resolve(result, schema, "events", 1, report))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("FK child resolution failed")
                .hasMessageContaining("simulated timeout");
    }

    /**
     * With depth=2, children of children are also fetched.
     * Verifies that the expanded-PK guard correctly limits each table to one expansion per depth level.
     */
    @Test
    @DisplayName("Depth=2 fetches grandchildren after children are added in depth=1 pass")
    void depthTwoFetchesGrandchildren() {
        // utilisateur ← dossier ← piece_jointe (two levels)
        ForeignKey fkDossier   = new ForeignKey("dossier",      "id_utilisateur", "utilisateur", "id");
        ForeignKey fkPieceJointe = new ForeignKey("piece_jointe", "id_dossier",     "dossier",     "id");

        TableMetadata utilisateurMeta  = new TableMetadata("utilisateur",  List.of(), List.of(), "id");
        TableMetadata dossierMeta      = new TableMetadata("dossier",      List.of(), List.of(fkDossier), "id");
        TableMetadata pieceJointeMeta  = new TableMetadata("piece_jointe", List.of(), List.of(fkPieceJointe), "id");
        DatabaseSchema schema = new DatabaseSchema(Map.of(
                "utilisateur", utilisateurMeta,
                "dossier",     dossierMeta,
                "piece_jointe", pieceJointeMeta));

        ExtractionResult result = new ExtractionResult();
        result.addWithPk(new ExtractedRow("utilisateur", Map.of("id", 42L)), "id");

        // Depth-1 fetch: dossier
        when(cursorReader.readByPrimaryKeys(anyString(), eq("dossier"), eq("id_utilisateur"), any()))
                .thenReturn(List.of(new ExtractedRow("dossier", Map.of("id", 100L, "id_utilisateur", 42L))));
        // Depth-2 fetch: piece_jointe
        when(cursorReader.readByPrimaryKeys(anyString(), eq("piece_jointe"), eq("id_dossier"), any()))
                .thenReturn(List.of(new ExtractedRow("piece_jointe", Map.of("id", 200L, "id_dossier", 100L))));

        resolver.resolve(result, schema, "events", 2, report);

        assertThat(result.getRows("dossier")).hasSize(1);
        assertThat(result.getRows("piece_jointe")).hasSize(1);
        assertThat(result.totalRowCount()).isEqualTo(3); // utilisateur + dossier + piece_jointe
    }
}
