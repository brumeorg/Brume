package com.fungle.brume.replicator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SubprocessArgsTest {

    @Test
    @DisplayName("#26d regression — a non-ASCII argument goes through a pure-ASCII command line and reaches "
            + "the child byte for byte (was \"E2E ?dition\" for pg_dump under a non-UTF-8 locale)")
    void nonAsciiArgumentsReachTheChildIntact() throws Exception {
        String tricky = "\"E2E Édition\" it's 100% back\\slash 😀";
        List<String> command = SubprocessArgs.portable(List.of("printf", "%s|%s", "ascii 'quoted'", tricky));

        assertThat(String.join(" ", command)).as("nothing for the platform encoding to alter").matches("\\p{ASCII}*");
        Process process = new ProcessBuilder(command).start();
        byte[] out = process.getInputStream().readAllBytes();
        assertThat(process.waitFor()).isZero();
        assertThat(out).isEqualTo(("ascii 'quoted'|" + tricky).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void asciiCommandIsLeftAlone() {
        List<String> command = List.of("pg_dump", "-n", "\"Account\"", "-h", "localhost");
        assertThat(SubprocessArgs.portable(command)).isSameAs(command);
    }
}
