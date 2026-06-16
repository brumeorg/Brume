package com.fungle.brume.shutdown;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CancellationTokenTest {

    @Test
    @DisplayName("fresh token — checkpoint returns instantly, never throws")
    void freshToken_checkpointReturnsInstantly() {
        CancellationToken token = new CancellationToken();
        assertThat(token.isCancelled()).isFalse();
        // Hot-path call: must not throw.
        for (int i = 0; i < 10_000; i++) {
            assertThatCode(token::checkpoint).doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("after requestCancel — checkpoint throws CancellationException")
    void requestCancel_checkpointThrows() {
        CancellationToken token = new CancellationToken();
        token.requestCancel();
        assertThat(token.isCancelled()).isTrue();
        assertThatThrownBy(token::checkpoint)
                .isInstanceOf(CancellationException.class)
                .hasMessageContaining("cancelled");
    }

    @Test
    @DisplayName("requestCancel is idempotent")
    void requestCancel_idempotent() {
        CancellationToken token = new CancellationToken();
        token.requestCancel();
        token.requestCancel();
        token.requestCancel();
        assertThat(token.isCancelled()).isTrue();
    }
}
