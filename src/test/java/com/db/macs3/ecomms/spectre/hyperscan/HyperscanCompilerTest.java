package com.db.macs3.ecomms.spectre.hyperscan;

import org.junit.jupiter.api.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link HyperscanCompiler}.
 * Requires the Hyperscan native library (bundled in the JAR for linux-x86_64 and osx-aarch64).
 */
@DisplayName("HyperscanCompiler Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HyperscanCompilerTest {

    private HyperscanCompiler compiler;

    @BeforeEach
    void setUp() {
        compiler = new HyperscanCompiler();
        compiler.selfTest();
    }

    @Test @Order(1)
    @DisplayName("engineMode is always HYPERSCAN_NATIVE")
    void engineMode() {
        assertThat(compiler.getEngineMode()).isEqualTo("HYPERSCAN_NATIVE");
    }

    @Test @Order(2)
    @DisplayName("hyperscanVersion returns library version string")
    void hyperscanVersion() {
        assertThat(compiler.getHyperscanVersion()).isEqualTo("5.4.0-2.0.0");
    }

    // ── Valid patterns ────────────────────────────────────────────────────────

    @Test @Order(10)
    @DisplayName("Simple word pattern → PASS")
    void simpleWordPass() {
        var r = compiler.validate("insider", HyperscanCompiler.HS_FLAG_CASELESS);
        assertThat(r.isPass()).isTrue();
        assertThat(r.isFailed()).isFalse();
        assertThat(r.errorMessage()).isNull();
    }

    @Test @Order(11)
    @DisplayName("OR group pattern → PASS")
    void orGroupPass() {
        var r = compiler.validate("(?:price|spread|stock)", HyperscanCompiler.HS_FLAG_CASELESS);
        assertThat(r.isPass()).isTrue();
    }

    @Test @Order(12)
    @DisplayName("NEAR{5} bidirectional pattern → PASS")
    void nearPatternPass() {
        String pattern = "(?:manipulate\\S*(?:\\s+\\S+){0,5}\\s+(?:price|spread|stock)"
                + "|(?:price|spread|stock)(?:\\s+\\S+){0,5}\\s+manipulate\\S*)";
        var r = compiler.validate(pattern, HyperscanCompiler.HS_FLAG_CASELESS);
        assertThat(r.isPass()).isTrue();
    }

    @Test @Order(13)
    @DisplayName("FOLLOWEDBY directional pattern → PASS")
    void followedByPatternPass() {
        var r = compiler.validate(
                "don't(?:\\s+\\S+){0,3}\\s+compliance",
                HyperscanCompiler.HS_FLAG_CASELESS);
        assertThat(r.isPass()).isTrue();
    }

    @Test @Order(14)
    @DisplayName("Lookahead (?=...) is NOT supported by Hyperscan — correctly rejected")
    void andLookaheadUnsupported() {
        int flags = HyperscanCompiler.HS_FLAG_CASELESS | HyperscanCompiler.HS_FLAG_DOTALL;
        // Hyperscan does not support variable-length lookaheads — the translator
        // now generates OR patterns instead; this documents the Hyperscan limitation
        var r = compiler.validate("(?=.*insider)(?=.*announcement).*", flags);
        assertThat(r.isFailed()).isTrue();
        assertThat(r.errorMessage()).isNotBlank();
    }

    @Test @Order(15)
    @DisplayName("Negative lookahead (?!...) is NOT supported by Hyperscan — correctly rejected")
    void andNotUnsupported() {
        int flags = HyperscanCompiler.HS_FLAG_CASELESS | HyperscanCompiler.HS_FLAG_DOTALL;
        // Hyperscan does not support lookahead/lookbehind — the translator
        // now returns the positive pattern only; this documents the Hyperscan limitation
        var r = compiler.validate("(?=.*insider)(?!.*disclaimer).*", flags);
        assertThat(r.isFailed()).isTrue();
        assertThat(r.errorMessage()).isNotBlank();
    }

    @Test @Order(16)
    @DisplayName("Emoji codepoint \\x{1F4B0} with UTF8+UCP → PASS")
    void emojiCodepointPass() {
        int flags = HyperscanCompiler.HS_FLAG_CASELESS
                | HyperscanCompiler.HS_FLAG_UTF8
                | HyperscanCompiler.HS_FLAG_UCP;
        var r = compiler.validate("(?:\\x{1F4B0}|\\x{1F92B}|\\x{1F910})", flags);
        assertThat(r.isPass()).isTrue();
    }

    @Test @Order(17)
    @DisplayName("Korean literal with UTF8+UCP → PASS")
    void koreanLiteralPass() {
        int flags = HyperscanCompiler.HS_FLAG_CASELESS
                | HyperscanCompiler.HS_FLAG_UTF8
                | HyperscanCompiler.HS_FLAG_UCP;
        var r = compiler.validate("(?:비밀|내부자 거래)", flags);
        assertThat(r.isPass()).isTrue();
    }

    @Test @Order(18)
    @DisplayName("Arabic literal with UTF8+UCP → PASS")
    void arabicLiteralPass() {
        int flags = HyperscanCompiler.HS_FLAG_CASELESS
                | HyperscanCompiler.HS_FLAG_UTF8
                | HyperscanCompiler.HS_FLAG_UCP;
        var r = compiler.validate("(?:مخالفة|استثمار داخلي)", flags);
        assertThat(r.isPass()).isTrue();
    }

    @Test @Order(19)
    @DisplayName("Wildcard \\S* pattern → PASS")
    void wildcardPass() {
        var r = compiler.validate("manipulate\\S*", HyperscanCompiler.HS_FLAG_CASELESS);
        assertThat(r.isPass()).isTrue();
    }

    // ── Invalid patterns ──────────────────────────────────────────────────────

    @Test @Order(30)
    @DisplayName("Null pattern → FAILED with descriptive error")
    void nullPatternFailed() {
        var r = compiler.validate(null, HyperscanCompiler.HS_FLAG_CASELESS);
        assertThat(r.isFailed()).isTrue();
        assertThat(r.errorMessage()).isNotBlank();
    }

    @Test @Order(31)
    @DisplayName("Blank pattern → FAILED")
    void blankPatternFailed() {
        var r = compiler.validate("  ", HyperscanCompiler.HS_FLAG_CASELESS);
        assertThat(r.isFailed()).isTrue();
    }

    @Test @Order(32)
    @DisplayName("Unclosed group (unclosed → FAILED with Hyperscan error in message")
    void invalidPatternFailed() {
        var r = compiler.validate("(unclosed", HyperscanCompiler.HS_FLAG_CASELESS);
        assertThat(r.isFailed()).isTrue();
        assertThat(r.errorMessage()).containsIgnoringCase("Hyperscan compile error");
    }

    @Test @Order(33)
    @DisplayName("Unclosed character class [unclosed → FAILED (truly invalid Hyperscan pattern)")
    void invalidQuantifierFailed() {
        // {broken} is treated as a literal by Hyperscan (not a quantifier without an atom)
        // [unclosed is an unclosed character class — guaranteed to fail Hyperscan compilation
        var r = compiler.validate("[unclosed", HyperscanCompiler.HS_FLAG_CASELESS);
        assertThat(r.isFailed()).isTrue();
        assertThat(r.errorMessage()).containsIgnoringCase("Hyperscan compile error");
    }

    // ── Flag computation ──────────────────────────────────────────────────────

    @Test @Order(40)
    @DisplayName("ValidationResult.pass stores pattern and flags")
    void validationResultPass() {
        var r = HyperscanCompiler.ValidationResult.pass("test", 1);
        assertThat(r.isPass()).isTrue();
        assertThat(r.pattern()).isEqualTo("test");
        assertThat(r.hsFlags()).isEqualTo(1);
        assertThat(r.errorMessage()).isNull();
    }

    @Test @Order(41)
    @DisplayName("ValidationResult.failed stores error message")
    void validationResultFailed() {
        var r = HyperscanCompiler.ValidationResult.failed("some error");
        assertThat(r.isFailed()).isTrue();
        assertThat(r.errorMessage()).isEqualTo("some error");
    }

    // ── Thread safety ─────────────────────────────────────────────────────────

    @Test @Order(50)
    @DisplayName("Thread safety: 50 concurrent compiles via JDK 21 virtual threads")
    void threadSafety() throws InterruptedException {
        int count   = 50;
        var latch   = new CountDownLatch(count);
        var failures = new AtomicInteger();

        for (int i = 0; i < count; i++) {
            final int idx = i;
            Thread.ofVirtual().start(() -> {
                try {
                    var r = compiler.validate("thread_test_" + idx,
                            HyperscanCompiler.HS_FLAG_CASELESS);
                    if (!r.isPass()) {
                        failures.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(failures.get()).isZero();
    }
}
