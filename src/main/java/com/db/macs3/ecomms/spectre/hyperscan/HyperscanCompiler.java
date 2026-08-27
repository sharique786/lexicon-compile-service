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
 * <p><b>Library overview</b>
 * <p>{@code com.gliwka.hyperscan} bundles the native Hyperscan .so inside the JAR:
 * <ul>
 *   <li>linux-x86_64  → Cloud Run (standard)</li>
 *   <li>linux-aarch64 → Cloud Run (ARM), AWS Graviton</li>
 *   <li>osx-aarch64   → Apple Silicon dev machines</li>
 * </ul>
 * The native library is extracted to {@code java.io.tmpdir} on first use.
 * No manual deployment is required.
 *
 * <p><b>Validation flow</b>
 * <p>For each term:
 * <ol>
 *   <li>Wrap the PCRE pattern in an {@link Expression} with the appropriate flags</li>
 *   <li>Call {@link Database#compile(Expression)} — throws {@link CompileErrorException}
 *       on invalid patterns</li>
 *   <li>Close the {@link Database} immediately (we only need compile validation)</li>
 *   <li>Return {@link ValidationResult#pass} or {@link ValidationResult#failed}</li>
 * </ol>
 *
 * <p><b>Native Hyperscan logical combinations — pure decomposition ONLY, never AND NOT</b>
 * <p>Hyperscan 5.0+ supports logical combinations of patterns natively —
 * {@code HS_FLAG_COMBINATION} lets a compiled expression be the STRING
 * {@code "(101&102)"} (operators {@code &}/{@code |}/{@code !} over other
 * expressions' numeric ids), and Hyperscan reports a match for THAT id only
 * when the boolean condition over the referenced sub-expressions is true —
 * evaluated natively during the scan, no application-level combination
 * needed after the fact. This is used in the {@code /compile/bundle}
 * endpoint's combined {@code .hdb} file ONLY for a term decomposed by
 * {@code PatternDecomposer} with NO {@code AND NOT} involved — a positive-only
 * {@code R1&R2&...&Rn} formula has no negation, so Hyperscan's eager,
 * progressive combination evaluation is safe for it. AND NOT is explicitly
 * NOT built this way any more — see {@code HyperscanCombinationHandler} class
 * Javadoc for why a combination mixing a positive requirement with a
 * negation (confirmed broken via Hyperscan's own documented evaluation
 * model) was replaced with every required/excluded pattern reporting as its
 * own plain expression, evaluated by the caller after the whole scan
 * completes. See {@link #toSubExpressionFlags(int)} (decomposition leaves),
 * {@link #toAndNotExpressionFlags(int)} (AND NOT sides), and
 * {@link #toCombinationExpressionFlags} (the combination formula itself).
 *
 * <p><b>Dependency note:</b> {@code ExpressionFlag.COMBINATION} and
 * {@code ExpressionFlag.QUIET} were added to {@code com.gliwka.hyperscan-java}
 * in its v1.0.0 release; this project pins the wrapper's v2.0.0 line
 * (version string {@code 5.4.0-2.0.0}), which post-dates that release. If a
 * future dependency bump ever removed these constants, every call site below
 * would fail to compile with an unambiguous "cannot find symbol" naming the
 * exact missing flag — not a silent runtime behaviour change.
 *
 * <p><b>Thread safety</b>
 * <p>{@link Database#compile} is thread-safe. Spring Boot 4 Tomcat uses JDK 21
 * virtual threads — many concurrent compilations are handled without OS-thread blocking.
 *
 * <p><b>No fallback</b>
 * <p>RE2J and any other fallback have been removed. If Hyperscan is unavailable
 * (unsupported platform, ABI mismatch), the {@link PostConstruct} self-test fails
 * and the Spring context does not start — Cloud Run health checks fail fast.
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
     * Validates a single PCRE pattern by compiling it via Hyperscan.
     *
     * <p>One {@link Database} is created per call and immediately closed after
     * validation. Each call is stateless and safe for concurrent virtual threads.
     *
     * @param pattern Hyperscan PCRE string from {@code TermSyntaxTranslator}
     * @param hsFlags HS_FLAG_* bitmask (CASELESS=1, DOTALL=2, UTF8=32, UCP=64)
     * @return {@link ValidationResult#pass} on success,
     * {@link ValidationResult#failed} with Hyperscan error message on failure
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
     * Flags for a term that compiles as one plain, top-level, independently
     * reportable Hyperscan expression — a simple PASS term: not decomposed
     * (estimated complexity under {@code PatternComplexityAnalyzer.COMPLEXITY_BUDGET},
     * i.e. 700), not AND NOT, and compiled without error.
     *
     * <p><b>{@code CASELESS}, {@code DOTALL}, and {@code SOM_LEFTMOST} are
     * always included, unconditionally.</b> {@code UTF8}/{@code UCP} remain
     * CONDITIONAL on {@code bitmask} — deliberately, not an oversight: always
     * forcing them on was tried and confirmed to cause two real regressions —
     * (1) Hyperscan rejects {@code \b} (word boundary) when UCP is active
     * ("{@code \b} unsupported in UCP mode"), breaking any caller-supplied
     * Regex-type term that uses it; (2) UCP mode measurably slows down
     * Hyperscan compilation even for plain-ASCII patterns (~15x in this
     * project's own performance test). UTF8/UCP are added only when
     * {@code bitmask} indicates non-ASCII content is actually present — see
     * {@link #toAndNotExpressionFlags(int)} for the AND NOT case and
     * {@link #toSubExpressionFlags(int)} for the pure-decomposition-leaf
     * case — both intentionally narrower than this one (no {@code DOTALL}/
     * {@code SOM_LEFTMOST}), but UTF8/UCP are conditional there too, for the
     * same reason (see their own Javadoc for the confirmed regression that
     * fix addressed).
     *
     * <p><b>Also the general validation flag set</b>
     * <p>{@link #validate} always uses this method (regardless of what a
     * candidate pattern will eventually be compiled as downstream) — this is
     * historically the flag set {@code PatternComplexityAnalyzer}'s
     * {@code COMPLEXITY_BUDGET} was calibrated against, so validating every
     * candidate under it keeps the "too large" pre-check accurate.
     *
     * <p><b>{@link ExpressionFlag#SOM_LEFTMOST} is always included here</b>
     * <p>This method builds flags for a PLAIN, standalone, reportable
     * expression — never a {@code QUIET} sub-expression and never a
     * {@code COMBINATION} formula (those go through {@link #toSubExpressionFlags}
     * and {@link #toCombinationExpressionFlags} respectively, which never
     * include SOM_LEFTMOST — see their Javadoc for why). SOM_LEFTMOST is
     * therefore always safe to include here: Hyperscan's own documentation
     * and a real compile-time error this project hit directly both confirm
     * SOM_LEFTMOST is incompatible with QUIET (and separately, with
     * SINGLEMATCH/PREFILTER) — but a plain expression carries none of those.
     *
     * <p>Public so {@code LexiconCombinationHandler} can build multi-pattern
     * {@link Expression} lists for the combined-database endpoint using the
     * exact same flag-conversion logic as the single-pattern {@link #validate} path.
     *
     * @param bitmask HS_FLAG_* bitmask (only the UTF8/UCP bits matter here —
     *                CASELESS/DOTALL/SOM_LEFTMOST are added regardless of this value)
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
     * Flags for a required/excluded pattern belonging to an AND NOT term —
     * see {@code HyperscanCombinationHandler} class Javadoc for why AND NOT
     * no longer uses native COMBINATION and why every required/excluded
     * pattern compiles as its own plain, individually-reportable expression.
     *
     * <p><b>{@code CASELESS} always; {@code UTF8}/{@code UCP} conditional on
     * {@code bitmask} — confirmed-fixed regression</b>: this used to be a
     * fixed, unconditional {@code CASELESS}-only set. That broke any AND NOT
     * term whose required/excluded side contains an emoji or other codepoint
     * above {@code 0xFF} — {@code PatternCodeGenerator} encodes those as a
     * literal {@code \x{XXXX}} escape, which Hyperscan/PCRE only accepts in
     * UTF8 mode; without it, {@code compileCombinedDatabase} fails with
     * "Hexadecimal value is greater than \xFF at index 0" — even though
     * {@code TermSyntaxTranslator.translate} had already validated the exact
     * same pattern text successfully, because validation went through
     * {@link #toExpressionFlags} (UTF8-conditional) while the real
     * {@code /compile/bundle} database build went through this method's old
     * fixed set. Still deliberately narrower than {@link #toExpressionFlags}
     * in every other respect: no {@code DOTALL}, and no {@code SOM_LEFTMOST} —
     * an AND NOT term's required/excluded patterns are still plain
     * (non-QUIET) expressions, so SOM_LEFTMOST would be structurally SAFE to
     * add here (unlike the QUIET-sub-expression case), but that flag stays
     * out regardless.
     *
     * @param bitmask HS_FLAG_* bitmask for the whole term (only the UTF8/UCP
     *                bits matter here) — pass the term's
     *                {@code TermCompilationResult.hyperscanFlags()}
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
     * Flags for a decomposed leaf pattern that feeds a native logical
     * combination — pure decomposition, no AND NOT (see
     * {@code HyperscanCombinationHandler} class Javadoc) — rather than being
     * reported on its own.
     *
     * <p>{@link ExpressionFlag#QUIET} suppresses this leaf's own match
     * reporting — without it, a decomposed term's leaves would each
     * independently raise their own match event, instead of only the
     * combination expression's id (built with
     * {@link #toCombinationExpressionFlags}) being reported for the term.
     *
     * <p><b>{@code CASELESS} and {@code QUIET} always; {@code UTF8}/
     * {@code UCP} conditional on {@code bitmask}</b> — same confirmed-fixed
     * regression described on {@link #toAndNotExpressionFlags}: a leaf
     * containing an emoji or other codepoint above {@code 0xFF} needs UTF8
     * mode to compile at all, and a decomposed term's leaves are
     * {@code \x{XXXX}}-encoded the same way an AND NOT side is. {@code DOTALL}
     * stays out (unaffected by this fix). Never {@code SOM_LEFTMOST} —
     * confirmed incompatible with {@code QUIET} both by a real Hyperscan
     * compile-time error this project hit directly ("HS_FLAG_QUIET is not
     * supported in combination with HS_FLAG_SOM_LEFTMOST") and by Hyperscan's
     * own documentation, which lists flags incompatible with SOM_LEFTMOST —
     * UTF8/UCP carry no such incompatibility with QUIET, so adding them
     * conditionally here is safe.
     *
     * @param bitmask HS_FLAG_* bitmask for the whole term (only the UTF8/UCP
     *                bits matter here) — pass the term's
     *                {@code TermCompilationResult.hyperscanFlags()}
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
     * Flags for a logical combination expression itself (see class Javadoc).
     * Hyperscan's own documentation states a COMBINATION-flagged expression
     * "ignores all other flags except HS_FLAG_SINGLEMATCH and HS_FLAG_QUIET" —
     * confirmed across multiple official sources (Hyperscan API reference,
     * the Compiling Patterns guide, and Intel's own published logical-combinations
     * article). Neither SINGLEMATCH nor QUIET is added here by default: a
     * combination expression is quiet only when it itself feeds an OUTER
     * combination (never produced by this codebase — see class Javadoc "Why
     * exactly one combination expression per term"), and SINGLEMATCH is an
     * optional match-deduplication choice this codebase does not currently
     * opt into. CASELESS/UTF8/etc. apply only to the sub-expressions being
     * combined (via {@link #toSubExpressionFlags}), not to the boolean
     * formula referencing their ids — Hyperscan ignores them here regardless,
     * so they are never added.
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
     * Compiles a list of {@link Expression}s into a single combined Hyperscan
     * database and serialises it to a byte array using {@link Database#save}.
     *
     * <p><b>Why one combined database instead of one-per-term</b>
     * <p>{@link #validate} (used by {@code /compile}) creates one ephemeral
     * single-pattern {@link Database} per term purely to check compile
     * validity, then discards it. This method is different: it produces the
     * one persistent multi-pattern database that the Lexicon Scan Engine
     * loads via {@link Database#load} and scans against millions of messages
     * with a single native call per message (Hyperscan's multi-pattern mode
     * is what makes that fast — scanning with N separate single-pattern
     * databases would be N times slower).
     *
     * <p><b>Expression IDs</b>
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
     * <p><b>What {@code save()} actually writes</b>
     * <p>{@link Database#save(java.io.OutputStream)} writes BOTH the
     * expression metadata (id, pattern, flags for every expression) AND the
     * platform-specific serialised native database into the same stream, in
     * that order. The returned byte array is therefore fully self-contained —
     * {@link Database#load(java.io.InputStream)} on the same bytes
     * reconstructs an equivalent database with no separate metadata file
     * needed. This is what becomes the single {@code .hdb} file in the zip.
     *
     * <p><b>Platform portability caveat</b>
     * <p>The serialised bytes are <b>not portable across CPU architectures</b>
     * with different instruction-set features (see Intel's Hyperscan
     * documentation on {@code hs_serialize_database}). The database must be
     * loaded on a platform compatible with the one it was compiled on.
     *
     * @param expressions PASS expressions to combine; must be non-empty and
     *                    each must have a unique non-null id
     * @return {@link CombinedCompileResult#success} with the serialised bytes,
     * or {@link CombinedCompileResult#failure} with the Hyperscan
     * error and (if identifiable) the id of the expression that
     * caused the failure
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
