package com.db.macs3.ecomms.spectre.hyperscan;

import com.gliwka.hyperscan.wrapper.CompileErrorException;
import com.gliwka.hyperscan.wrapper.Database;
import com.gliwka.hyperscan.wrapper.Expression;
import com.gliwka.hyperscan.wrapper.ExpressionFlag;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

/**
 * Validates Hyperscan PCRE patterns using the {@code com.gliwka.hyperscan} Java binding
 * (version 5.4.0-2.0.0).
 *
 * <h2>Library overview</h2>
 * <p>{@code com.gliwka.hyperscan} bundles the native Hyperscan .so inside the JAR:
 * <ul>
 *   <li>linux-x86_64  → Cloud Run (standard)</li>
 *   <li>linux-aarch64 → Cloud Run (ARM), AWS Graviton</li>
 *   <li>osx-aarch64   → Apple Silicon dev machines</li>
 * </ul>
 * The native library is extracted to {@code java.io.tmpdir} on first use.
 * No manual deployment is required.
 *
 * <h2>Validation flow</h2>
 * <p>For each term:
 * <ol>
 *   <li>Wrap the PCRE pattern in an {@link Expression} with the appropriate flags</li>
 *   <li>Call {@link Database#compile(Expression)} — throws {@link CompileErrorException}
 *       on invalid patterns</li>
 *   <li>Close the {@link Database} immediately (we only need compile validation)</li>
 *   <li>Return {@link ValidationResult#pass} or {@link ValidationResult#failed}</li>
 * </ol>
 *
 * <h2>Thread safety</h2>
 * <p>{@link Database#compile} is thread-safe. Spring Boot 4 Tomcat uses JDK 21
 * virtual threads — many concurrent compilations are handled without OS-thread blocking.
 *
 * <h2>No fallback</h2>
 * <p>RE2J and any other fallback have been removed. If Hyperscan is unavailable
 * (unsupported platform, ABI mismatch), the {@link PostConstruct} self-test fails
 * and the Spring context does not start — Cloud Run health checks fail fast.
 */
@Component
public class HyperscanCompiler {

    private static final Logger log = LoggerFactory.getLogger(HyperscanCompiler.class);

    /** Hyperscan flag bitmasks (mirrors hs_compile.h). */
    public static final int HS_FLAG_CASELESS = 1;
    public static final int HS_FLAG_DOTALL   = 2;
    public static final int HS_FLAG_UTF8     = 32;
    public static final int HS_FLAG_UCP      = 64;

    /** Library version reported in responses. */
    private static final String HYPERSCAN_VERSION = "5.4.0-2.0.0";

    // ── Startup self-test ─────────────────────────────────────────────────────

    /**
     * Verifies the Hyperscan native library is operational at startup.
     *
     * <p>Uses {@code jakarta.annotation.PostConstruct} (Jakarta EE 10 / Spring Boot 4).
     * Compiles a trivial pattern to confirm {@link Database#compile} works.
     * If this fails, the application context does not start.
     */
    @PostConstruct
    public void selfTest() {
        log.info("Initialising Hyperscan (com.gliwka.hyperscan {})...", HYPERSCAN_VERSION);
        try {
            Expression probe = new Expression("selftest_probe",
                    EnumSet.of(ExpressionFlag.CASELESS));
            try (Database db = Database.compile(probe)) {
                log.info("Hyperscan self-test PASSED — native library operational.");
            }
        } catch (CompileErrorException e) {
            throw new IllegalStateException(
                    "Hyperscan self-test FAILED (compile error): " + e.getMessage(), e);
        } catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
            throw new IllegalStateException(
                    "Hyperscan native library unavailable on this platform: " + e.getMessage(), e);
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Validates a single PCRE pattern by compiling it via Hyperscan.
     *
     * <p>One {@link Database} is created per call and immediately closed after
     * validation. Each call is stateless and safe for concurrent virtual threads.
     *
     * @param pattern  Hyperscan PCRE string from {@code TermSyntaxTranslator}
     * @param hsFlags  HS_FLAG_* bitmask (CASELESS=1, DOTALL=2, UTF8=32, UCP=64)
     * @return {@link ValidationResult#pass} on success,
     *         {@link ValidationResult#failed} with Hyperscan error message on failure
     */
    public ValidationResult validate(String pattern, int hsFlags) {
        if (pattern == null || pattern.isBlank()) {
            return ValidationResult.failed("Pattern is null or blank");
        }

        EnumSet<ExpressionFlag> flags = toExpressionFlags(hsFlags);
        log.debug("Compiling: pattern='{}' flags={}", pattern, flags);

        try {
            Expression expression = new Expression(pattern, flags);
            try (Database db = Database.compile(expression)) {
                log.debug("PASS: pattern='{}'", pattern);
                return ValidationResult.pass(pattern, hsFlags);
            }
        } catch (CompileErrorException e) {
            String errorMsg = buildErrorMessage(e, pattern);
            log.warn("FAILED: pattern='{}' hsError='{}'", pattern, errorMsg);
            return ValidationResult.failed(errorMsg, pattern, hsFlags);
        } catch (Exception e) {
            String errorMsg = "Unexpected Hyperscan error: " + e.getMessage();
            log.error("Unexpected compile error for pattern '{}': {}", pattern, e.getMessage(), e);
            return ValidationResult.failed(errorMsg, pattern, hsFlags);
        }
    }

    /** Returns the engine identifier string for REST responses. */
    public String getEngineMode() {
        return "HYPERSCAN_NATIVE";
    }

    /** Returns the bundled Hyperscan library version. */
    public String getHyperscanVersion() {
        return HYPERSCAN_VERSION;
    }

    // ── Flag conversion ───────────────────────────────────────────────────────

    /**
     * Converts an HS_FLAG_* bitmask to the {@link ExpressionFlag} EnumSet
     * required by {@link Expression}.
     *
     * <p>Bitmask values mirror {@code hs_compile.h}:
     * <pre>
     *  1  = HS_FLAG_CASELESS
     *  2  = HS_FLAG_DOTALL
     * 32  = HS_FLAG_UTF8
     * 64  = HS_FLAG_UCP
     * </pre>
     * If the bitmask is 0 (no flags set), CASELESS is added as a safe default.
     */
    private EnumSet<ExpressionFlag> toExpressionFlags(int bitmask) {
        EnumSet<ExpressionFlag> flags = EnumSet.noneOf(ExpressionFlag.class);
        if ((bitmask & HS_FLAG_CASELESS) != 0) {
            flags.add(ExpressionFlag.CASELESS);
        }
        if ((bitmask & HS_FLAG_DOTALL) != 0) {
            flags.add(ExpressionFlag.DOTALL);
        }
        if ((bitmask & HS_FLAG_UTF8) != 0) {
            flags.add(ExpressionFlag.UTF8);
        }
        if ((bitmask & HS_FLAG_UCP) != 0) {
            flags.add(ExpressionFlag.UCP);
        }
        // Default: CASELESS
        if (flags.isEmpty()) {
            flags.add(ExpressionFlag.CASELESS);
        }
        return flags;
    }

    /**
     * Builds a descriptive error message from {@link CompileErrorException}.
     *
     * <p>{@code CompileErrorException.getMessage()} returns the Hyperscan
     * {@code hs_compile_error->message} string, e.g. "Syntax error".
     * We prefix it with context for easier debugging in the BQ error_log column.
     */
    private String buildErrorMessage(CompileErrorException e, String pattern) {
        String hsMessage = e.getMessage() != null ? e.getMessage() : "unknown Hyperscan error";
        String patternSnippet = pattern.length() > 100
                ? pattern.substring(0, 100) + "..."
                : pattern;
        return "Hyperscan compile error: %s [pattern: %s]".formatted(hsMessage, patternSnippet);
    }

    // ── ValidationResult record ───────────────────────────────────────────────

    /**
     * Immutable result of one Hyperscan pattern compile attempt.
     */
    public record ValidationResult(
            boolean pass,
            String  pattern,
            int     hsFlags,
            String  errorMessage
    ) {
        /** Factory: successful compilation. */
        public static ValidationResult pass(String pattern, int flags) {
            return new ValidationResult(true, pattern, flags, null);
        }

        /** Factory: failed compilation (no pattern available). */
        public static ValidationResult failed(String error) {
            return new ValidationResult(false, null, 0, error);
        }

        /** Factory: failed compilation (pattern attempted but invalid). */
        public static ValidationResult failed(String error, String pattern, int flags) {
            return new ValidationResult(false, pattern, flags, error);
        }

        public boolean isPass()   { return pass; }
        public boolean isFailed() { return !pass; }
    }
}
