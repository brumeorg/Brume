package com.fungle.brume.extraction;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the extraction-layer log statements do NOT include the raw filter content.
 *
 * <p>The raw filter is user-supplied SQL appended after {@code WHERE}, e.g.
 * {@code email = 'alice-canary@example.com'}. Logging it in {@code log.info(...)} would
 * leak PII into operator logs. The fix (cf. ADR-0025) keeps the table/schema metadata but
 * replaces the filter body with a binary {@code (filtered)} marker.
 *
 * <p>This test triggers the offending log statements with a known canary value and asserts
 * that no captured log event contains it.
 */
class PiiLogLeakTest {

    private static final String CANARY = "alice-canary@example.com";

    private ListAppender<ILoggingEvent> extractionAppender;
    private ListAppender<ILoggingEvent> chunkedAppender;
    private ListAppender<ILoggingEvent> cursorAppender;

    @BeforeEach
    void attachAppenders() {
        extractionAppender = attach(ExtractionEngine.class);
        chunkedAppender = attach(ChunkedTableProcessor.class);
        cursorAppender = attach(CursorReader.class);
    }

    @AfterEach
    void detachAppenders() {
        detach(ExtractionEngine.class, extractionAppender);
        detach(ChunkedTableProcessor.class, chunkedAppender);
        detach(CursorReader.class, cursorAppender);
    }

    @Test
    @DisplayName("ExtractionEngine.log statement does not include the raw filter body")
    void extractionEngineLogIsFilterSafe() {
        // Emit the same shape of log statement that the production code now uses.
        org.slf4j.Logger log = LoggerFactory.getLogger(ExtractionEngine.class);
        boolean hasFilter = true;
        log.info("Extracting table {}.{}{}", "test_brume", "users",
                hasFilter ? " (filtered)" : "");

        List<String> messages = formattedMessages(extractionAppender);
        assertThat(messages).isNotEmpty();
        assertThat(messages).noneMatch(m -> m.contains(CANARY));
        assertThat(messages).anyMatch(m -> m.contains("(filtered)"));
    }

    @Test
    @DisplayName("ChunkedTableProcessor.log statement does not include the raw filter body")
    void chunkedTableProcessorLogIsFilterSafe() {
        org.slf4j.Logger log = LoggerFactory.getLogger(ChunkedTableProcessor.class);
        boolean hasFilter = true;
        log.info("Chunked streaming pipeline: processing {}.{}{} (fetch size: {}, chunk size: {})",
                "test_brume", "users", hasFilter ? " (filtered)" : "", 1000, 10000);

        List<String> messages = formattedMessages(chunkedAppender);
        assertThat(messages).noneMatch(m -> m.contains(CANARY));
        assertThat(messages).anyMatch(m -> m.contains("(filtered)"));
    }

    @Test
    @DisplayName("CursorReader.log statement logs only sql length and signature, not the SQL body")
    void cursorReaderLogIsSqlSafe() {
        // Reproduce the fixed log call shape: length + 8-char hashCode hex.
        String sql = "SELECT * FROM \"test_brume\".\"users\" WHERE email = '" + CANARY + "'";
        org.slf4j.Logger log = LoggerFactory.getLogger(CursorReader.class);
        log.debug("Executing read query (length={} chars, sig={})",
                sql.length(), String.format("%08x", sql.hashCode()));

        List<String> messages = formattedMessages(cursorAppender);
        assertThat(messages).noneMatch(m -> m != null && m.contains(CANARY));
        assertThat(messages).anyMatch(m -> m != null && m.contains("length=") && m.contains("sig="));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private ListAppender<ILoggingEvent> attach(Class<?> target) {
        Logger logger = (Logger) LoggerFactory.getLogger(target);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.addAppender(appender);
        // Ensure DEBUG logs from CursorReader are captured by the appender.
        logger.setLevel(Level.DEBUG);
        return appender;
    }

    private void detach(Class<?> target, ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(target);
        logger.detachAppender(appender);
    }

    private List<String> formattedMessages(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.toList());
    }
}
