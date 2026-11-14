package com.fungle.brume.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BrumeVersionProviderTest {

    @Test
    @DisplayName("getVersion() emits 'brume <non-blank>' — guards against a hard-coded version drifting from pom.xml")
    void getVersionEmitsBrumePrefixAndNonBlankVersion() throws Exception {
        String[] out = new BrumeVersionProvider().getVersion();

        assertThat(out).hasSize(1);
        assertThat(out[0]).startsWith("brume ");
        assertThat(out[0].substring("brume ".length())).isNotBlank();
    }

    @Test
    @DisplayName("#127g — the version comes from the filtered brume-version.properties of target/classes, "
            + "the only source the native image has (no jar MANIFEST, no META-INF/maven) : never 'dev'")
    void versionResourceIsFilteredIntoTheClasspath() throws Exception {
        try (var in = BrumeVersionProvider.class.getResourceAsStream("/" + BrumeVersionProvider.VERSION_RESOURCE)) {
            assertThat(in).as("filtered resource on the classpath").isNotNull();
        }
        assertThat(BrumeVersionProvider.resolveVersion()).matches("\\d+\\.\\d+.*");
    }
}
