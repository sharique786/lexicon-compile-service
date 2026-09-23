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
 * Validates PCRE patterns with the real native Hyperscan library ({@code com.gliwka.hyperscan}
 * 5.4.0-2.0.0), converts a term's flag bitmask into Hyperscan {@link ExpressionFlag} sets, and
 * compiles the combined multi-pattern database served by {@code /compile/bundle}.
 *
 * <p><b>Native library.</b> Bundled in the jar for linux-x86_64, linux-aarch64, osx-aarch64 and windows-x86_64, and
 * extracted to {@code java.io.tmpdir} on first use; nothing to deploy. There is no fallback engine:
 * {@link #selfTest()} compiles a probe pattern at startup, and if the native library is unavailable
 * the Spring context does not start (so Cloud Run health checks fail fast).
 *
 * <p><b>Validation.</b> {@link #validate} compiles one pattern alone into a throwaway
 * {@link Database}, closes it, and returns pass or Hyperscan's own error message. It is stateless and
 * {@link Database#compile} is thread-safe, so concurrent virtual-thread requests are fine.
 *
 * <p><b>Flag sets.</b> Which set an expression gets depends on its kind, never on a caller option:
 * <table border="1">
 *   <caption>Expression flag sets</caption>
 *   <tr><th>Expression</th><th>Method</th><th>Flags</th></tr>
 *   <tr><td>Plain single-pattern, non-AND-NOT term; also what {@link #validate} uses</td>
 *       <td>{@link #toExpressionFlags}</td><td>CASELESS, DOTALL, SOM_LEFTMOST (+ UTF8, UCP)</td></tr>
 *   <tr><td>Required/excluded pattern of an AND NOT term</td>
 *       <td>{@link #toAndNotExpressionFlags}</td><td>CASELESS (+ UTF8, UCP)</td></tr>
 *   <tr><td>Decomposed leaf feeding a native COMBINATION</td>
 *       <td>{@link #toSubExpressionFlags}</td><td>CASELESS, QUIET (+ UTF8, UCP)</td></tr>
 *   <tr><td>The COMBINATION formula itself</td>
 *       <td>{@link #toCombinationExpressionFlags}</td><td>COMBINATION</td></tr>
 * </table>
 * UTF8 and UCP are added only when the term's bitmask says it has non-ASCII content. The
 * constraints behind this scheme:
 * <ul>
 *   <li>{@code SOM_LEFTMOST} is incompatible with {@code QUIET} (a real compile error), so a QUIET
 *       leaf never gets it; only plain, reportable expressions do.</li>
 *   <li>A {@code COMBINATION} expression ignores every flag except {@code SINGLEMATCH}/{@code QUIET}.</li>
 *   <li>UCP is conditional because Hyperscan rejects {@code \b} in UCP mode (generated word
 *       boundaries and Regex-type terms use it) and UCP compiles roughly 15x slower even for ASCII
 *       patterns. It cannot simply be omitted either: non-ASCII text and emoji ({@code \x{XXXX}}) need
 *       UTF8 to compile, and a pattern that validated fine could otherwise fail the combined build
 *       ("Hexadecimal value is greater than \xFF").</li>
 *   <li>An AND NOT pattern gets neither DOTALL nor SOM_LEFTMOST — only its presence matters, not its offsets.</li>
 * </ul>
 *
 * <p><b>Native COMBINATION</b> ({@code HS_FLAG_COMBINATION}, Hyperscan 5.0+) lets an expression be a
 * boolean formula such as {@code "(101&102)"} over other expressions' ids; Hyperscan reports the
 * formula's own id when the condition holds. Here it is used only for a decomposed term WITHOUT AND
 * NOT — a positive-only formula has no negation, so Hyperscan's eager, progressive evaluation is safe.
 * AND NOT is never built this way; see {@link HyperscanCombinationHandler}. A combination
 * sub-expression must not end in an assertion such as a trailing {@code \b}, which is why fallback
 * leaves carry only a leading word boundary.
 */
@Component
public class HyperscanCompiler {

    private static final Logger log = LoggerFactory.getLogger(HyperscanCompiler.class);

    /**
     * Hyperscan flag bitmasks (mirrors hs_compile.h).
     */
    public static final int HS_FLAG_CASELESS = 1;
    public static final int HS_FLAG_DOTALL = 2;
    public static final int HS_FLAG_UTF8 = 32;
    public static final int HS_FLAG_UCP = 64;

    /**
     * Library version reported in responses.
     */
    private static final String HYPERSCAN_VERSION = "5.4.0-2.0.0";

    // ── Startup self-test ─────────────────────────────────────────────────────

    /**
     * Startup check that the native library works: compiles a trivial pattern. Failure prevents
     * the application context from starting.
     */
    @PostConstruct
    public void selfTest() {
        log.info("Initialising Hyperscan (com.gliwka.hyperscan {})...", HYPERSCAN_VERSION);
        try {
            Expression probe = new Expression("selftest_probe",
                    EnumSet.of(ExpressionFlag.CASELESS, ExpressionFlag.SOM_LEFTMOST));
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
     * Validates one PCRE pattern by compiling it under {@link #toExpressionFlags(int)}. A fresh
     * {@link Database} is created and closed per call, so each call is stateless.
     *
     * <p>Every candidate is validated under this one flag set, whatever it will eventually be compiled
     * as, so the translator's "too large" checks are consistent.
     *
     * @param pattern Hyperscan PCRE string
     * @param hsFlags flag bitmask (CASELESS=1, DOTALL=2, UTF8=32, UCP=64); only the UTF8/UCP bits matter
     * @return {@link ValidationResult#pass}, or {@link ValidationResult#failed} with Hyperscan's message
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

    /**
     * Returns the engine identifier string for REST responses.
     */
    public String getEngineMode() {
        return "HYPERSCAN_NATIVE";
    }

    /**
     * Returns the bundled Hyperscan library version.
     */
    public String getHyperscanVersion() {
        return HYPERSCAN_VERSION;
    }

    // ── Flag conversion ───────────────────────────────────────────────────────

    /**
     * Flags for a term that compiles as one plain, independently reportable expression: a
     * single-pattern, non-AND-NOT PASS term. Also the set {@link #validate} uses for every pattern.
     *
     * <p>{@code CASELESS}, {@code DOTALL} and {@code SOM_LEFTMOST} are always included;
     * {@code UTF8}/{@code UCP} only when {@code bitmask} has them (see the class Javadoc for why UCP must
     * stay conditional). {@code SOM_LEFTMOST} is safe here because a plain expression is never
     * {@code QUIET}.
     *
     * @param bitmask flag bitmask; only the UTF8/UCP bits matter
     */
    public EnumSet<ExpressionFlag> toExpressionFlags(int bitmask) {
        EnumSet<ExpressionFlag> flags = EnumSet.of(
                ExpressionFlag.CASELESS, ExpressionFlag.DOTALL, ExpressionFlag.SOM_LEFTMOST);
        if ((bitmask & HS_FLAG_UTF8) != 0) {
            flags.add(ExpressionFlag.UTF8);
        }
        if ((bitmask & HS_FLAG_UCP) != 0) {
            flags.add(ExpressionFlag.UCP);
        }
        return flags;
    }

    /**
     * Flags for a required/excluded pattern of an AND NOT term, which compiles as its own plain,
     * individually reportable expression (see {@link HyperscanCombinationHandler}).
     *
     * <p>{@code CASELESS} always; {@code UTF8}/{@code UCP} when {@code bitmask} has them, because a side
     * containing an emoji or any code point above 0xFF is encoded as {@code \x{XXXX}}, which Hyperscan
     * accepts only in UTF8 mode. No {@code DOTALL} and no {@code SOM_LEFTMOST}.
     *
     * @param bitmask the term's flag bitmask ({@code TermCompilationResult.hyperscanFlags()})
     */
    public EnumSet<ExpressionFlag> toAndNotExpressionFlags(int bitmask) {
        EnumSet<ExpressionFlag> flags = EnumSet.of(ExpressionFlag.CASELESS);
        if ((bitmask & HS_FLAG_UTF8) != 0) {
            flags.add(ExpressionFlag.UTF8);
        }
        if ((bitmask & HS_FLAG_UCP) != 0) {
            flags.add(ExpressionFlag.UCP);
        }
        return flags;
    }

    /**
     * Flags for a decomposed leaf that feeds a native COMBINATION (a fallback term without AND NOT).
     *
     * <p>{@code QUIET} suppresses the leaf's own match report, so only the combination's id is reported
     * for the term. {@code CASELESS} and {@code QUIET} always; {@code UTF8}/{@code UCP} when
     * {@code bitmask} has them (same reason as {@link #toAndNotExpressionFlags}). Never
     * {@code SOM_LEFTMOST}: Hyperscan rejects it together with {@code QUIET}.
     *
     * @param bitmask the term's flag bitmask ({@code TermCompilationResult.hyperscanFlags()})
     */
    public EnumSet<ExpressionFlag> toSubExpressionFlags(int bitmask) {
        EnumSet<ExpressionFlag> flags = EnumSet.of(ExpressionFlag.CASELESS, ExpressionFlag.QUIET);
        if ((bitmask & HS_FLAG_UTF8) != 0) {
            flags.add(ExpressionFlag.UTF8);
        }
        if ((bitmask & HS_FLAG_UCP) != 0) {
            flags.add(ExpressionFlag.UCP);
        }
        return flags;
    }

    /**
     * Flags for the logical COMBINATION expression itself: just {@code COMBINATION}. Hyperscan
     * ignores every other flag on a combination except {@code SINGLEMATCH}/{@code QUIET}, neither of
     * which is used here. CASELESS/UTF8 apply to the combined sub-expressions, not to the formula.
     */
    public EnumSet<ExpressionFlag> toCombinationExpressionFlags() {
        return EnumSet.of(ExpressionFlag.COMBINATION);
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
            String pattern,
            int hsFlags,
            String errorMessage
    ) {
        /**
         * Factory: successful compilation.
         */
        public static ValidationResult pass(String pattern, int flags) {
            return new ValidationResult(true, pattern, flags, null);
        }

        /**
         * Factory: failed compilation (no pattern available).
         */
        public static ValidationResult failed(String error) {
            return new ValidationResult(false, null, 0, error);
        }

        /**
         * Factory: failed compilation (pattern attempted but invalid).
         */
        public static ValidationResult failed(String error, String pattern, int flags) {
            return new ValidationResult(false, pattern, flags, error);
        }

        public boolean isPass() {
            return pass;
        }

        public boolean isFailed() {
            return !pass;
        }
    }

    // ── Combined multi-pattern database (for the /compile/bundle endpoint) ─────

    /**
     * Compiles {@code expressions} into ONE combined database and serialises it with
     * {@link Database#save}. This produces the persistent {@code .hdb} that the Lexicon Scan Engine loads
     * with {@link Database#load} and scans against many messages with a single native call each
     * (one multi-pattern database is far faster than N single-pattern ones). {@link #validate}, by
     * contrast, only checks one pattern and discards the database.
     *
     * <p><b>Ids.</b> Every expression must carry a unique, non-null id; {@code LexiconCompileBundleService}
     * assigns each term's number (parsed from its {@code termId}) or an allocated auxiliary id, so a match
     * id can always be mapped back to its term.
     *
     * <p><b>What is saved.</b> {@code save()} writes each expression's metadata (id, pattern, flags) and the
     * serialised native database together, so the {@code .hdb} is self-contained. It is not portable
     * across CPU architectures with different instruction-set features; load it on a compatible platform.
     *
     * @param expressions the expressions to combine; non-empty, each with a unique non-null id
     * @return {@link CombinedCompileResult#success} with the bytes, or {@link CombinedCompileResult#failure}
     *         with the error and, when identifiable, the id of the failing expression
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
     * @param success            true when the database compiled and serialised
     * @param databaseBytes      the {@code .hdb} content; null on failure
     * @param databaseSizeBytes  {@code databaseBytes.length}; 0 on failure
     * @param expressionCount    number of expressions included; 0 on failure
     * @param failedExpressionId the id of the expression that broke compilation when Hyperscan can
     *                           identify it; null otherwise and always null on success
     * @param errorMessage       human-readable error; null on success
     */
    public record CombinedCompileResult(
            boolean success,
            byte[] databaseBytes,
            long databaseSizeBytes,
            int expressionCount,
            Integer failedExpressionId,
            String errorMessage
    ) {
        /**
         * Factory: successful combined compile.
         */
        public static CombinedCompileResult success(byte[] bytes, int expressionCount) {
            return new CombinedCompileResult(true, bytes, bytes.length, expressionCount, null, null);
        }

        /**
         * Factory: failed combined compile.
         */
        public static CombinedCompileResult failure(String error, Integer failedExpressionId) {
            return new CombinedCompileResult(false, null, 0, 0, failedExpressionId, error);
        }
    }
}
