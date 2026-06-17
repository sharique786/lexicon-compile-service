package com.db.macs3.ecomms.spectre.hyperscan;

import com.gliwka.hyperscan.wrapper.CompileErrorException;
import com.gliwka.hyperscan.wrapper.Database;
import com.gliwka.hyperscan.wrapper.Expression;
import com.gliwka.hyperscan.wrapper.ExpressionFlag;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.EnumSet;
import java.util.List;

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
     * <p><b>Return type note:</b> this returns {@link EnumSet} specifically
     * (not the wider {@link java.util.Set} interface) because every
     * {@code Expression} constructor in {@code com.gliwka.hyperscan.wrapper}
     * requires an {@code EnumSet<ExpressionFlag>} argument — Java's static
     * type system does not implicitly narrow {@code Set} to {@code EnumSet}
     * at the call site, even when the runtime object actually is an
     * {@code EnumSet}. Declaring the narrower return type here lets
     * {@link #validate} and {@link #compileCombinedDatabase} pass the result
     * straight into {@code new Expression(...)} without a cast.
     *
     * <p>Bitmask values mirror {@code hs_compile.h}:
     * <pre>
     *  1  = HS_FLAG_CASELESS
     *  2  = HS_FLAG_DOTALL
     * 32  = HS_FLAG_UTF8
     * 64  = HS_FLAG_UCP
     * </pre>
     * If the bitmask is 0 (no flags set), CASELESS is added as a safe default.
     *
     * <p>Public so {@code LexiconCompileBundleService} can build multi-pattern
     * {@link Expression} lists for the combined-database endpoint using the
     * exact same flag-conversion logic as the single-pattern {@link #validate} path.
     */
    public EnumSet<ExpressionFlag> toExpressionFlags(int bitmask) {
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

    // ── Combined multi-pattern database (for the /compile/bundle endpoint) ─────

    /**
     * Compiles a list of {@link Expression}s into a single combined Hyperscan
     * database and serialises it to a byte array using {@link Database#save}.
     *
     * <h2>Why one combined database instead of one-per-term</h2>
     * <p>{@link #validate} (used by {@code /compile}) creates one ephemeral
     * single-pattern {@link Database} per term purely to check compile
     * validity, then discards it. This method is different: it produces the
     * one persistent multi-pattern database that the Lexicon Scan Engine
     * loads via {@link Database#load} and scans against millions of messages
     * with a single native call per message (Hyperscan's multi-pattern mode
     * is what makes that fast — scanning with N separate single-pattern
     * databases would be N times slower).
     *
     * <h2>Expression IDs</h2>
     * <p>Each {@link Expression} passed in must already carry a unique
     * {@code id} (set via the 3-arg {@code Expression(pattern, flags, id)}
     * constructor). {@code com.gliwka.hyperscan.wrapper.Database} requires
     * this — if any expression in the list has a null id while others don't,
     * or if two expressions share the same id, the underlying library throws.
     * The caller ({@code LexiconCompileBundleService}) assigns
     * {@code id = index of the term in the original request's terms array},
     * so a downstream consumer can always map a Hyperscan match id back to
     * the term that produced it.
     *
     * <h2>What {@code save()} actually writes</h2>
     * <p>{@link Database#save(java.io.OutputStream)} writes BOTH the
     * expression metadata (id, pattern, flags for every expression) AND the
     * platform-specific serialised native database into the same stream, in
     * that order. The returned byte array is therefore fully self-contained —
     * {@link Database#load(java.io.InputStream)} on the same bytes
     * reconstructs an equivalent database with no separate metadata file
     * needed. This is what becomes the single {@code .hdb} file in the zip.
     *
     * <h2>Platform portability caveat</h2>
     * <p>The serialised bytes are <b>not portable across CPU architectures</b>
     * with different instruction-set features (see Intel's Hyperscan
     * documentation on {@code hs_serialize_database}). The database must be
     * loaded on a platform compatible with the one it was compiled on.
     *
     * @param expressions PASS expressions to combine; must be non-empty and
     *                    each must have a unique non-null id
     * @return {@link CombinedCompileResult#success} with the serialised bytes,
     *         or {@link CombinedCompileResult#failure} with the Hyperscan
     *         error and (if identifiable) the id of the expression that
     *         caused the failure
     */
    public CombinedCompileResult compileCombinedDatabase(List<Expression> expressions) {
        if (expressions == null || expressions.isEmpty()) {
            return CombinedCompileResult.failure(
                    "No PASS expressions were supplied — nothing to compile into a combined database",
                    null);
        }

        Database db = null;
        try {
            db = Database.compile(expressions);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            db.save(baos);
            byte[] bytes = baos.toByteArray();

            log.info("Combined Hyperscan database compiled: {} expressions, {} bytes",
                    expressions.size(), bytes.length);
            return CombinedCompileResult.success(bytes, expressions.size());

        } catch (CompileErrorException e) {
            Integer failedId = e.getFailedExpression() != null
                    ? e.getFailedExpression().getId()
                    : null;
            log.error("Combined database compile FAILED at expression id={}: {}",
                    failedId, e.getMessage());
            return CombinedCompileResult.failure(
                    "Combined Hyperscan compile error: " + e.getMessage(), failedId);

        } catch (IOException e) {
            log.error("Failed to serialise combined database: {}", e.getMessage(), e);
            return CombinedCompileResult.failure(
                    "Database serialisation error: " + e.getMessage(), null);

        } finally {
            if (db != null) {
                db.close();
            }
        }
    }

    /**
     * Result of one {@link #compileCombinedDatabase} call.
     *
     * @param success            true when the combined database compiled and serialised cleanly
     * @param databaseBytes      the {@code .hdb} file content (null on failure)
     * @param databaseSizeBytes  {@code databaseBytes.length} (0 on failure)
     * @param expressionCount    number of expressions included (0 on failure)
     * @param failedExpressionId the id of the expression that broke compilation,
     *                           when Hyperscan was able to identify it; null otherwise
     *                           (always null when {@code success} is true)
     * @param errorMessage       human-readable error; null when {@code success} is true
     */
    public record CombinedCompileResult(
            boolean success,
            byte[]  databaseBytes,
            long    databaseSizeBytes,
            int     expressionCount,
            Integer failedExpressionId,
            String  errorMessage
    ) {
        /** Factory: successful combined compile. */
        public static CombinedCompileResult success(byte[] bytes, int expressionCount) {
            return new CombinedCompileResult(true, bytes, bytes.length, expressionCount, null, null);
        }

        /** Factory: failed combined compile. */
        public static CombinedCompileResult failure(String error, Integer failedExpressionId) {
            return new CombinedCompileResult(false, null, 0, 0, failedExpressionId, error);
        }
    }
}
