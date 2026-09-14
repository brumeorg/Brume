package com.fungle.brume;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the pre-Spring subcommand gate added by {@link BrumeApplication#hasKnownSubcommand}.
 *
 * <p>Regression net for the bug where {@code brume -v} (or any invocation with only
 * global flags / no args at all) booted the full Spring context, then crashed on
 * {@code ReplicationPropertiesValidator} because the user hadn't configured
 * {@code replication.target.url} yet. Root cause : {@code main()} only intercepted
 * {@code --help/-h/--version/-V/init} pre-boot, so anything else fell through to
 * {@code SpringApplication.run} — including bare flags that only end up in picocli's
 * usage-print fallback.
 *
 * <p>The gate must return {@code false} for arg sets that would only reach
 * {@link com.fungle.brume.command.BrumeCommand#call()} (usage-print), and {@code true}
 * for any real subcommand invocation so Spring still boots normally in production.
 */
class BrumeApplicationSubcommandGateTest {

    @BeforeEach
    @AfterEach
    void clearAotMarker() {
        System.clearProperty("spring.aot.processing");
    }

    @Test
    @DisplayName("bare -v / --verbose is NOT a subcommand invocation → Spring boot skipped")
    void bareVerboseFlagIsNotASubcommand() {
        assertThat(BrumeApplication.hasKnownSubcommand(List.of("-v"))).isFalse();
        assertThat(BrumeApplication.hasKnownSubcommand(List.of("--verbose"))).isFalse();
    }

    @Test
    @DisplayName("bare -q / --quiet / --json is NOT a subcommand invocation → Spring boot skipped")
    void bareGlobalOutputFlagsAreNotSubcommands() {
        assertThat(BrumeApplication.hasKnownSubcommand(List.of("-q"))).isFalse();
        assertThat(BrumeApplication.hasKnownSubcommand(List.of("--quiet"))).isFalse();
        assertThat(BrumeApplication.hasKnownSubcommand(List.of("--json"))).isFalse();
    }

    @Test
    @DisplayName("no args at all is NOT a subcommand invocation → Spring boot skipped")
    void emptyArgsIsNotASubcommand() {
        assertThat(BrumeApplication.hasKnownSubcommand(List.of())).isFalse();
    }

    @Test
    @DisplayName("every real subcommand (execute, plan, dry-run, diag, audit, init) is detected → Spring boot proceeds")
    void allProductionSubcommandsAreDetected() {
        // If this ever fails, a subcommand was added to BrumeCommand without updating
        // BrumeApplication.SUBCOMMANDS — that would silently break Spring boot for the
        // new command, mirroring the pre-fix behaviour of `brume -v`.
        for (String sub : List.of("execute", "plan", "dry-run", "diag", "audit", "init")) {
            assertThat(BrumeApplication.hasKnownSubcommand(List.of(sub)))
                    .as("subcommand '%s' must trigger Spring boot", sub)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("subcommand combined with global flags is still detected (e.g. `execute -v`)")
    void subcommandWithGlobalFlagsIsDetected() {
        assertThat(BrumeApplication.hasKnownSubcommand(List.of("execute", "-v"))).isTrue();
        assertThat(BrumeApplication.hasKnownSubcommand(List.of("-v", "plan"))).isTrue();
        assertThat(BrumeApplication.hasKnownSubcommand(
                List.of("--json", "dry-run", "--brume.sink.type=NULL"))).isTrue();
    }

    @Test
    @DisplayName("spring.aot.processing=true → AOT phase detected so main() lets Spring boot even with empty args")
    void aotProcessingMarkerIsDetected() {
        // Regression net for the native build failure : spring-boot-maven-plugin:process-aot
        // invokes BrumeApplication.main() with empty args and expects SpringApplication.run()
        // to run so it can emit target/spring-aot/main/sources. If the subcommand gate
        // short-circuits during AOT phase, the native compile fails with
        // "Failed to execute goal ... process-aot ... target/spring-aot/main/sources".
        assertThat(BrumeApplication.isAotProcessingPhase())
                .as("marker must default to false so normal runs (empty args) short-circuit")
                .isFalse();

        System.setProperty("spring.aot.processing", "true");
        assertThat(BrumeApplication.isAotProcessingPhase())
                .as("marker must be true when Spring's AbstractAotProcessor sets it → main() lets Spring boot")
                .isTrue();

        System.setProperty("spring.aot.processing", "false");
        assertThat(BrumeApplication.isAotProcessingPhase())
                .as("marker parsed as boolean — 'false' string means we're not in AOT phase")
                .isFalse();
    }
}
