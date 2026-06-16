package com.fungle.brume.anonymization.strategies;

import com.fungle.brume.anonymization.HmacSeeder;
import com.fungle.brume.anonymization.SubstitutionDictionary;
import com.fungle.brume.config.BrumeProperties;
import com.fungle.brume.config.model.SemanticType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for all {@link AnonymizationStrategy} implementations.
 *
 * <p>No Spring context is needed — all dependencies are instantiated directly.
 */
class AnonymizationStrategiesTest {

    /** A fixed test secret long enough for both HMAC and FPE (16+ bytes). */
    private static final String SECRET = "test-secret-1234";

    private BrumeProperties brumeProperties;
    private SubstitutionDictionary dictionary;

    @BeforeEach
    void setUp() {
        brumeProperties = new BrumeProperties("config.yaml", SECRET, SECRET, "HmacSHA256", "fr",  0.0,new BrumeProperties.SubstitutionDictProperties(1_000_000L), new BrumeProperties.ReportProperties("", "", ""));
        dictionary = new SubstitutionDictionary(brumeProperties);
    }

    // -------------------------------------------------------------------------
    // HmacSeeder
    // -------------------------------------------------------------------------

    @Nested
    class HmacSeederTest {

        @Test
        void seedFrom_sameInputs_returnsSameSeed() throws Exception {
            long seed1 = HmacSeeder.seedFrom("value", SECRET, "HmacSHA256");
            long seed2 = HmacSeeder.seedFrom("value", SECRET, "HmacSHA256");
            assertThat(seed1).isEqualTo(seed2);
        }

        @Test
        void seedFrom_differentValues_returnsDifferentSeeds() throws Exception {
            long seed1 = HmacSeeder.seedFrom("alice", SECRET, "HmacSHA256");
            long seed2 = HmacSeeder.seedFrom("bob", SECRET, "HmacSHA256");
            assertThat(seed1).isNotEqualTo(seed2);
        }
    }

    // -------------------------------------------------------------------------
    // FakeStrategy
    // -------------------------------------------------------------------------

    @Nested
    class FakeStrategyTest {

        private FakeStrategy strategy;

        @BeforeEach
        void setUp() {
            strategy = new FakeStrategy(brumeProperties);
        }

        // --- Basic non-null / type contracts ---

        @Test
        void anonymize_email_returnsNonNullString() {
            Object result = strategy.anonymize("real@example.com", SemanticType.EMAIL, "users.email", dictionary);
            assertThat(result).isNotNull().isInstanceOf(String.class);
        }

        @Test
        void anonymize_firstName_returnsNonNullString() {
            Object result = strategy.anonymize("Alice", SemanticType.FIRST_NAME, "users.first_name", dictionary);
            assertThat(result).isNotNull().isInstanceOf(String.class);
        }

        @Test
        void anonymize_lastName_returnsNonNullString() {
            Object result = strategy.anonymize("Smith", SemanticType.LAST_NAME, "users.last_name", dictionary);
            assertThat(result).isNotNull().isInstanceOf(String.class);
        }

        @Test
        void anonymize_phone_returnsNonNullString() {
            Object result = strategy.anonymize("+33612345678", SemanticType.PHONE, "users.phone", dictionary);
            assertThat(result).isNotNull().isInstanceOf(String.class);
        }

        @Test
        void anonymize_address_returnsNonNullString() {
            Object result = strategy.anonymize("1 rue de Paris", SemanticType.ADDRESS, "users.address", dictionary);
            assertThat(result).isNotNull().isInstanceOf(String.class);
        }

        @Test
        void anonymize_null_returnsNull() {
            Object result = strategy.anonymize(null, SemanticType.EMAIL, "users.email", dictionary);
            assertThat(result).isNull();
        }

        @Test
        void anonymize_jsonbType_throwsIllegalArgumentException() {
            assertThatThrownBy(() ->
                    strategy.anonymize("{}", SemanticType.JSONB, "table.col", dictionary))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        // --- Determinism ---

        @Test
        void anonymize_sameInput_returnsSameOutput() {
            // Even with a fresh dictionary (forces HMAC re-computation), the output must be identical.
            Object r1 = strategy.anonymize("real@example.com", SemanticType.EMAIL, "users.email", dictionary);
            SubstitutionDictionary freshDict = new SubstitutionDictionary(brumeProperties);
            Object r2 = strategy.anonymize("real@example.com", SemanticType.EMAIL, "users.email", freshDict);
            assertThat(r1).isEqualTo(r2);
        }

        // --- FIX 1: Case normalization ---
        // "Alice@Test.COM" and "alice@test.com" must produce the same fake value,
        // both via the dictionary cache AND via independent HMAC re-computation.

        @Test
        void anonymize_email_caseVariants_returnSameFakeValue_viaDictionary() {
            // Same dictionary: the second call must hit the cache even though the raw string differs in case.
            Object upper = strategy.anonymize("Alice@Test.COM", SemanticType.EMAIL, "users.email", dictionary);
            Object lower = strategy.anonymize("alice@test.com", SemanticType.EMAIL, "users.email", dictionary);
            assertThat(upper).isEqualTo(lower);
        }

        @Test
        void anonymize_email_caseVariants_returnSameFakeValue_withFreshDictionaries() {
            // Different dictionaries: each call recomputes from the HMAC seed.
            // Both must produce the same result because the seed derivation is case-insensitive.
            SubstitutionDictionary dict1 = new SubstitutionDictionary(brumeProperties);
            SubstitutionDictionary dict2 = new SubstitutionDictionary(brumeProperties);
            Object upper = strategy.anonymize("Alice@Test.COM", SemanticType.EMAIL, "users.email", dict1);
            Object lower = strategy.anonymize("alice@test.com", SemanticType.EMAIL, "users.email", dict2);
            assertThat(upper).isEqualTo(lower);
        }

        // --- FIX 3a: EMAIL format — seed-derived hex suffix ---
        // Generated emails must match "localpart.XXXXXX@domain" where XXXXXX is exactly 6 hex chars.

        @Test
        void anonymize_email_containsHexSuffix() {
            String result = (String) strategy.anonymize("real@example.com", SemanticType.EMAIL, "users.email", dictionary);
            // Matches "<something>.<6 hex chars>@<domain>"
            assertThat(result).matches("(?i).+\\.[0-9a-f]{6}@.+");
        }

        @Test
        void anonymize_differentEmails_produceDifferentFakeEmails() {
            // Fix 3 guarantees the hex suffix is derived from the seed, so distinct real values produce distinct fake emails.
            SubstitutionDictionary dict1 = new SubstitutionDictionary(brumeProperties);
            SubstitutionDictionary dict2 = new SubstitutionDictionary(brumeProperties);
            Object fake1 = strategy.anonymize("alice@example.com", SemanticType.EMAIL, "users.email", dict1);
            Object fake2 = strategy.anonymize("bob@example.com",   SemanticType.EMAIL, "users.email", dict2);
            assertThat(fake1).isNotEqualTo(fake2);
        }

        // --- FIX 3b: IBAN — no whitespace ---
        // Datafaker may emit formatted IBANs with spaces; they must be stripped.

        @Test
        void anonymize_iban_doesNotContainWhitespace() {
            String result = (String) strategy.anonymize("FR7630006000011234567890189", SemanticType.IBAN, "users.iban", dictionary);
            assertThat(result).isNotNull().doesNotContain(" ");
        }

        @Test
        void anonymize_iban_returnsNonNullString() {
            Object result = strategy.anonymize("FR7630006000011234567890189", SemanticType.IBAN, "users.iban", dictionary);
            assertThat(result).isNotNull().isInstanceOf(String.class);
        }

        // --- FIX 3c: IP_ADDRESS — octets derived from seed bits ---
        // The result must be a valid dotted-quad with constrained octet ranges.

        @Test
        void anonymize_ipAddress_isValidDottedQuad() {
            String result = (String) strategy.anonymize("192.168.1.1", SemanticType.IP_ADDRESS, "devices.ip", dictionary);
            assertThat(result).matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");
        }

        @Test
        void anonymize_ipAddress_firstOctetInValidRange() {
            // First octet must be 1-223 (reserves 0.x and 224+ multicast/reserved).
            String result = (String) strategy.anonymize("10.0.0.1", SemanticType.IP_ADDRESS, "devices.ip", dictionary);
            int firstOctet = Integer.parseInt(result.split("\\.")[0]);
            assertThat(firstOctet).isBetween(1, 223);
        }

        @Test
        void anonymize_ipAddress_lastOctetInValidRange() {
            // Last octet must be 1-254 (reserves .0 network and .255 broadcast).
            String result = (String) strategy.anonymize("172.16.0.1", SemanticType.IP_ADDRESS, "devices.ip", dictionary);
            int lastOctet = Integer.parseInt(result.split("\\.")[3]);
            assertThat(lastOctet).isBetween(1, 254);
        }

        @Test
        void anonymize_differentIpAddresses_produceDifferentFakeIps() {
            // Each distinct real IP must map to a distinct fake IP (seed derived from real value).
            SubstitutionDictionary dict1 = new SubstitutionDictionary(brumeProperties);
            SubstitutionDictionary dict2 = new SubstitutionDictionary(brumeProperties);
            Object fake1 = strategy.anonymize("192.168.1.1", SemanticType.IP_ADDRESS, "devices.ip", dict1);
            Object fake2 = strategy.anonymize("10.0.0.2",    SemanticType.IP_ADDRESS, "devices.ip", dict2);
            assertThat(fake1).isNotEqualTo(fake2);
        }
    }

    // -------------------------------------------------------------------------
    // FpeUuidStrategy
    // -------------------------------------------------------------------------

    @Nested
    class FpeUuidStrategyTest {

        private static final String REAL_UUID_1 = "550e8400-e29b-41d4-a716-446655440000";
        private static final String REAL_UUID_2 = "6ba7b810-9dad-11d1-80b4-00c04fd430c8";

        private FpeUuidStrategy strategy;

        @BeforeEach
        void setUp() {
            strategy = new FpeUuidStrategy(brumeProperties);
        }

        @Test
        void anonymize_null_returnsNull() {
            assertThat(strategy.anonymize(null, null, "table.id", dictionary)).isNull();
        }

        @Test
        void anonymize_returnsUuidInstance() {
            // FpeUuidStrategy must return a java.util.UUID so JDBC can bind it directly
            // to a PostgreSQL uuid column without a type mismatch.
            Object result = strategy.anonymize(REAL_UUID_1, null, "table.id", dictionary);
            assertThat(result).isNotNull().isInstanceOf(UUID.class);
        }

        @Test
        void anonymize_outputDiffersFromInput() {
            // The fake UUID must not be the same as the real UUID.
            UUID result = (UUID) strategy.anonymize(REAL_UUID_1, null, "table.id", dictionary);
            assertThat(result).isNotEqualTo(UUID.fromString(REAL_UUID_1));
        }

        @Test
        void anonymize_isVersion4() {
            // The output must look like a version-4 UUID (bit 12-15 of time_hi = 0100).
            UUID result = (UUID) strategy.anonymize(REAL_UUID_1, null, "table.id", dictionary);
            assertThat(result.version()).isEqualTo(4);
        }

        @Test
        void anonymize_isVariant2() {
            // The output must use RFC 4122 variant (10xx).
            UUID result = (UUID) strategy.anonymize(REAL_UUID_1, null, "table.id", dictionary);
            assertThat(result.variant()).isEqualTo(2);
        }

        @Test
        void anonymize_deterministic_sameInputSameOutput() {
            // Two invocations with the same input must produce the same UUID.
            UUID r1 = (UUID) strategy.anonymize(REAL_UUID_1, null, "table.id", dictionary);
            UUID r2 = (UUID) strategy.anonymize(REAL_UUID_1, null, "table.id", new SubstitutionDictionary(brumeProperties));
            assertThat(r1).isEqualTo(r2);
        }

        @Test
        void anonymize_differentInputs_produceDifferentUuids() {
            // Two distinct real UUIDs must map to two distinct fake UUIDs.
            UUID r1 = (UUID) strategy.anonymize(REAL_UUID_1, null, "table.id", dictionary);
            UUID r2 = (UUID) strategy.anonymize(REAL_UUID_2, null, "table.id", dictionary);
            assertThat(r1).isNotEqualTo(r2);
        }

        @Test
        void anonymize_caseInsensitive_sameOutput() {
            // UUID hex digits may be upper- or lower-case — the output must be identical.
            UUID lower = (UUID) strategy.anonymize(REAL_UUID_1.toLowerCase(), null, "table.id", dictionary);
            UUID upper = (UUID) strategy.anonymize(REAL_UUID_1.toUpperCase(), null, "table.id",
                    new SubstitutionDictionary(brumeProperties));
            assertThat(lower).isEqualTo(upper);
        }

        @Test
        void anonymize_fkPropagation_sameUuidAcrossTables() {
            // A UUID used as PK in table A and FK in table B must anonymize to the same fake UUID.
            // (same real value + same strategy = same output — determinism guarantees this)
            UUID pkResult = (UUID) strategy.anonymize(REAL_UUID_1, null, "users.id", dictionary);
            UUID fkResult = (UUID) strategy.anonymize(REAL_UUID_1, null, "orders.user_id",
                    new SubstitutionDictionary(brumeProperties));
            // FpeUuidStrategy ignores semanticKey — output is driven solely by the value + secret.
            assertThat(pkResult).isEqualTo(fkResult);
        }
    }

    // -------------------------------------------------------------------------
    // FpeIdStrategy
    // -------------------------------------------------------------------------

    @Nested
    class FpeIdStrategyTest {

        private FpeIdStrategy strategy;

        @BeforeEach
        void setUp() {
            strategy = new FpeIdStrategy(brumeProperties);
        }

        @Test
        void anonymize_numericId_returnsLong() {
            Object result = strategy.anonymize("42", null, "users.id", dictionary);
            // FpeIdStrategy returns a Long so JDBC can bind it to a bigint column directly.
            // Returning a String would cause a type mismatch error in PostgreSQL.
            assertThat(result).isNotNull().isInstanceOf(Long.class);
            assertThat((Long) result).isGreaterThanOrEqualTo(0L);
        }

        @Test
        void anonymize_deterministic_sameInputSameOutput() {
            Object r1 = strategy.anonymize("12345", null, "users.id", dictionary);
            Object r2 = strategy.anonymize("12345", null, "users.id", dictionary);
            assertThat(r1).isEqualTo(r2);
        }

        @Test
        void anonymize_differentInputs_differentOutputs() {
            Object r1 = strategy.anonymize("1", null, "users.id", dictionary);
            Object r2 = strategy.anonymize("2", null, "users.id", dictionary);
            assertThat(r1).isNotEqualTo(r2);
        }

        @Test
        void anonymize_null_returnsNull() {
            Object result = strategy.anonymize(null, null, "users.id", dictionary);
            assertThat(result).isNull();
        }

        @Test
        void anonymize_differentKeyLengths_produceDifferentOutputs() {
            // Test that AES-128, AES-192, and AES-256 produce distinct encrypted values
            // for the same input. This verifies that the key is used at its native length
            // and not truncated to 16 bytes.
            String inputId = "12345";

            // AES-128 (16 bytes)
            BrumeProperties props16 = new BrumeProperties("config.yaml", SECRET, "0123456789abcdef",
                    "HmacSHA256", "fr", 0.0, new BrumeProperties.SubstitutionDictProperties(1_000_000L), new BrumeProperties.ReportProperties("", "", ""));
            FpeIdStrategy strategy16 = new FpeIdStrategy(props16);
            SubstitutionDictionary dict16 = new SubstitutionDictionary(props16);
            Long result16 = (Long) strategy16.anonymize(inputId, null, "users.id", dict16);

            // AES-192 (24 bytes)
            BrumeProperties props24 = new BrumeProperties("config.yaml", SECRET, "0123456789abcdef01234567",
                    "HmacSHA256", "fr", 0.0, new BrumeProperties.SubstitutionDictProperties(1_000_000L), new BrumeProperties.ReportProperties("", "", ""));
            FpeIdStrategy strategy24 = new FpeIdStrategy(props24);
            SubstitutionDictionary dict24 = new SubstitutionDictionary(props24);
            Long result24 = (Long) strategy24.anonymize(inputId, null, "users.id", dict24);

            // AES-256 (32 bytes)
            BrumeProperties props32 = new BrumeProperties("config.yaml", SECRET, "0123456789abcdef0123456789abcdef",
                    "HmacSHA256", "fr", 0.0, new BrumeProperties.SubstitutionDictProperties(1_000_000L), new BrumeProperties.ReportProperties("", "", ""));
            FpeIdStrategy strategy32 = new FpeIdStrategy(props32);
            SubstitutionDictionary dict32 = new SubstitutionDictionary(props32);
            Long result32 = (Long) strategy32.anonymize(inputId, null, "users.id", dict32);

            // All three results must be different (different key lengths = different encryption outputs)
            assertThat(result16).isNotEqualTo(result24);
            assertThat(result16).isNotEqualTo(result32);
            assertThat(result24).isNotEqualTo(result32);
        }

        @Test
        void anonymize_24ByteKey_deterministicOutput() {
            // Verify that a 24-byte key (AES-192) produces deterministic output
            BrumeProperties props24 = new BrumeProperties("config.yaml", SECRET, "0123456789abcdef01234567",
                    "HmacSHA256", "fr", 0.0, new BrumeProperties.SubstitutionDictProperties(1_000_000L), new BrumeProperties.ReportProperties("", "", ""));
            FpeIdStrategy strategy24 = new FpeIdStrategy(props24);
            SubstitutionDictionary dict1 = new SubstitutionDictionary(props24);
            SubstitutionDictionary dict2 = new SubstitutionDictionary(props24);

            Long result1 = (Long) strategy24.anonymize("99999", null, "users.id", dict1);
            Long result2 = (Long) strategy24.anonymize("99999", null, "users.id", dict2);
            assertThat(result1).isEqualTo(result2);
        }

        @Test
        void anonymize_32ByteKey_deterministicOutput() {
            // Verify that a 32-byte key (AES-256) produces deterministic output
            BrumeProperties props32 = new BrumeProperties("config.yaml", SECRET, "0123456789abcdef0123456789abcdef",
                    "HmacSHA256", "fr", 0.0, new BrumeProperties.SubstitutionDictProperties(1_000_000L), new BrumeProperties.ReportProperties("", "", ""));
            FpeIdStrategy strategy32 = new FpeIdStrategy(props32);
            SubstitutionDictionary dict1 = new SubstitutionDictionary(props32);
            SubstitutionDictionary dict2 = new SubstitutionDictionary(props32);

            Long result1 = (Long) strategy32.anonymize("88888", null, "users.id", dict1);
            Long result2 = (Long) strategy32.anonymize("88888", null, "users.id", dict2);
            assertThat(result1).isEqualTo(result2);
        }

        @Test
        void anonymize_negativeValue_throwsException() {
            // FPE_ID does not support negative IDs — they must fail explicitly
            assertThatThrownBy(() -> strategy.anonymize("-123", null, "users.id", dictionary))
                    .isInstanceOf(com.fungle.brume.error.AnonymizationException.class)
                    .hasMessageContaining("does not support negative values");
        }

        @Test
        void anonymize_valueExceedsMaxInput_throwsException() {
            // MAX_INPUT = 10^18 - 1; Long.MAX_VALUE > 10^18 - 1, so it must fail
            assertThatThrownBy(() -> strategy.anonymize(String.valueOf(Long.MAX_VALUE), null, "users.id", dictionary))
                    .isInstanceOf(com.fungle.brume.error.AnonymizationException.class)
                    .hasMessageContaining("exceeds the supported range");
        }

        @Test
        void anonymize_nonNumericValue_throwsException() {
            // Non-numeric input should produce a clear error message
            assertThatThrownBy(() -> strategy.anonymize("abc", null, "users.id", dictionary))
                    .isInstanceOf(com.fungle.brume.error.AnonymizationException.class)
                    .hasMessageContaining("FPE_ID expects a numeric value");
        }

        @Test
        void anonymize_maxInputValue_succeeds() {
            // MAX_INPUT = 999_999_999_999_999_999L (18 digits) must be accepted
            long maxInput = 999_999_999_999_999_999L;
            Object result = strategy.anonymize(String.valueOf(maxInput), null, "users.id", dictionary);
            assertThat(result).isNotNull().isInstanceOf(Long.class);
        }

        @Test
        void anonymize_largeValue_succeeds() {
            // Verify that 18-digit IDs far larger than the old 9-digit limit are accepted
            long largeId = 123_456_789_012_345_678L; // 18 digits
            Object result = strategy.anonymize(String.valueOf(largeId), null, "users.id", dictionary);
            assertThat(result).isNotNull().isInstanceOf(Long.class);
        }

        @Test
        void anonymize_largeValue_deterministic() {
            // Verify determinism for large IDs
            long largeId = 999_999_999_999_999_000L;
            Object r1 = strategy.anonymize(String.valueOf(largeId), null, "users.id", dictionary);
            Object r2 = strategy.anonymize(String.valueOf(largeId), null, "users.id", dictionary);
            assertThat(r1).isEqualTo(r2);
        }
    }

    // -------------------------------------------------------------------------
    // MaskStrategy
    // -------------------------------------------------------------------------

    @Nested
    class MaskStrategyTest {

        private MaskStrategy strategy;

        @BeforeEach
        void setUp() {
            strategy = new MaskStrategy();
        }

        @Test
        void anonymize_phone_masksMiddleDigits() {
            Object result = strategy.anonymize("+33612345678", SemanticType.PHONE, "users.phone", dictionary);
            assertThat(result).isNotNull().isInstanceOf(String.class);
            String s = result.toString();
            assertThat(s).contains("*");
        }

        @Test
        void anonymize_ipAddress_keepsFirstTwoOctets() {
            Object result = strategy.anonymize("192.168.1.1", SemanticType.IP_ADDRESS, "devices.ip", dictionary);
            assertThat(result).isNotNull().isInstanceOf(String.class);
            assertThat(result.toString()).startsWith("192.168");
            assertThat(result.toString()).endsWith("*.*");
        }

        @Test
        void anonymize_email_masksWithStars() {
            Object result = strategy.anonymize("alice@example.com", SemanticType.EMAIL, "users.email", dictionary);
            assertThat(result).isNotNull().isInstanceOf(String.class);
            assertThat(result.toString()).contains("*");
        }

        @Test
        void anonymize_null_returnsNull() {
            Object result = strategy.anonymize(null, SemanticType.EMAIL, "users.email", dictionary);
            assertThat(result).isNull();
        }
    }

    // -------------------------------------------------------------------------
    // HashStrategy
    // -------------------------------------------------------------------------

    @Nested
    class HashStrategyTest {

        private HashStrategy strategy;

        @BeforeEach
        void setUp() {
            strategy = new HashStrategy(brumeProperties);
        }

        @Test
        void anonymize_returnsHexString() {
            Object result = strategy.anonymize("sensitive-value", null, "table.col", dictionary);
            assertThat(result).isNotNull().isInstanceOf(String.class);
            // HmacSHA256 produces a 64-char hex string (32 bytes × 2 hex digits)
            assertThat(result.toString()).hasSize(64).matches("[0-9a-f]+");
        }

        @Test
        void anonymize_deterministic() {
            Object r1 = strategy.anonymize("same-value", null, "table.col", dictionary);
            Object r2 = strategy.anonymize("same-value", null, "table.col", dictionary);
            assertThat(r1).isEqualTo(r2);
        }

        @Test
        void anonymize_differentValues_differentHashes() {
            Object r1 = strategy.anonymize("value-a", null, "table.col", dictionary);
            Object r2 = strategy.anonymize("value-b", null, "table.col", dictionary);
            assertThat(r1).isNotEqualTo(r2);
        }

        @Test
        void anonymize_null_returnsNull() {
            Object result = strategy.anonymize(null, null, "table.col", dictionary);
            assertThat(result).isNull();
        }

        @Test
        void anonymize_hmacSHA512_producesLongerHash() {
            // HmacSHA512 should produce a 128-character hex string (64 bytes × 2)
            BrumeProperties props512 = new BrumeProperties("config.yaml", SECRET, SECRET,
                    "HmacSHA512", "fr", 0.0, new BrumeProperties.SubstitutionDictProperties(1_000_000L), new BrumeProperties.ReportProperties("", "", ""));
            HashStrategy strategy512 = new HashStrategy(props512);

            Object result = strategy512.anonymize("test-value", null, "table.col", dictionary);
            assertThat(result).isNotNull().isInstanceOf(String.class);
            assertThat(result.toString()).hasSize(128).matches("[0-9a-f]+");
        }

        @Test
        void anonymize_differentAlgorithms_produceDifferentHashes() {
            // Same value with HmacSHA256 vs HmacSHA512 must produce different hashes
            String testValue = "test-value";

            // HmacSHA256 (default)
            Object resultSHA256 = strategy.anonymize(testValue, null, "table.col", dictionary);

            // HmacSHA512
            BrumeProperties props512 = new BrumeProperties("config.yaml", SECRET, SECRET,
                    "HmacSHA512", "fr", 0.0, new BrumeProperties.SubstitutionDictProperties(1_000_000L), new BrumeProperties.ReportProperties("", "", ""));
            HashStrategy strategy512 = new HashStrategy(props512);
            Object resultSHA512 = strategy512.anonymize(testValue, null, "table.col", dictionary);

            // Different algorithms must produce different outputs
            assertThat(resultSHA256).isNotEqualTo(resultSHA512);
            // And different lengths
            assertThat(resultSHA256.toString()).hasSize(64);
            assertThat(resultSHA512.toString()).hasSize(128);
        }

        @Test
        void anonymize_differentSecrets_produceDifferentHashes() {
            // Same value with different secrets must produce different hashes
            String testValue = "test-value";
            String differentSecret = "another-secret-1234567890";

            Object result1 = strategy.anonymize(testValue, null, "table.col", dictionary);

            BrumeProperties differentProps = new BrumeProperties("config.yaml", differentSecret, SECRET,
                    "HmacSHA256", "fr", 0.0, new BrumeProperties.SubstitutionDictProperties(1_000_000L), new BrumeProperties.ReportProperties("", "", ""));
            HashStrategy differentStrategy = new HashStrategy(differentProps);
            SubstitutionDictionary differentDict = new SubstitutionDictionary(differentProps);
            Object result2 = differentStrategy.anonymize(testValue, null, "table.col", differentDict);

            assertThat(result1).isNotEqualTo(result2);
        }

        @Test
        void anonymize_stability_sameInputProducesSameOutput() {
            // Verify long-term stability: multiple calls with fixed secret/value/algo produce identical output
            String fixedValue = "stable-test-value";
            Object result1 = strategy.anonymize(fixedValue, null, "table.col", dictionary);
            Object result2 = strategy.anonymize(fixedValue, null, "table.col", dictionary);
            Object result3 = strategy.anonymize(fixedValue, null, "table.col", dictionary);

            assertThat(result1).isEqualTo(result2).isEqualTo(result3);
        }
    }

    // -------------------------------------------------------------------------
    // NullifyStrategy
    // -------------------------------------------------------------------------

    @Nested
    class NullifyStrategyTest {

        private NullifyStrategy strategy;

        @BeforeEach
        void setUp() {
            strategy = new NullifyStrategy();
        }

        @Test
        void anonymize_anyValue_returnsNull() {
            assertThat(strategy.anonymize("non-null", null, "table.col", dictionary)).isNull();
        }

        @Test
        void anonymize_null_returnsNull() {
            assertThat(strategy.anonymize(null, null, "table.col", dictionary)).isNull();
        }
    }

    // -------------------------------------------------------------------------
    // KeepStrategy
    // -------------------------------------------------------------------------

    @Nested
    class KeepStrategyTest {

        private KeepStrategy strategy;

        @BeforeEach
        void setUp() {
            strategy = new KeepStrategy();
        }

        @Test
        void anonymize_returnsOriginalValue() {
            Object original = "unchanged-value";
            Object result = strategy.anonymize(original, null, "table.col", dictionary);
            assertThat(result).isSameAs(original);
        }

        @Test
        void anonymize_null_returnsNull() {
            assertThat(strategy.anonymize(null, null, "table.col", dictionary)).isNull();
        }
    }
}


