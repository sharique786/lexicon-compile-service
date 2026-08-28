package com.db.macs3.ecomms.spectre.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * Top-level response returned by every compile endpoint.
 *
 * <p><b>Field visibility rules</b>
 * <p>Several fields are conditionally serialised to keep the JSON clean:
 * <ul>
 *   <li>{@code request_id} — present only when explicitly set (bundle / CSV endpoints)</li>
 *   <li>{@code requestType} — present only when set (bundle endpoint only)</li>
 *   <li>{@code hyperscanVersion} — present only when non-null (absent from bundle response)</li>
 *   <li>{@code processingTimeMs} — present only when non-zero; populated for all three endpoints,
 *       including {@code /compile/bundle}</li>
 * </ul>
 *
 * <p><b>Factory methods</b>
 * <ul>
 *   <li>{@link #of} — original {@code /compile} and {@code /compile/csv} responses</li>
 *   <li>{@link #ofBundle} — {@code /compile/bundle} response (includes requestId + requestType)</li>
 *   <li>{@link #withRequestId} — copies this response with a requestId added
 *       (used by the CSV endpoint to attach the generated UUID)</li>
 * </ul>
 */
public record CompileResponse(

        /*
         * Caller-supplied or generated request identifier, echoed back for
         * end-to-end tracing. {@code null} (and absent from JSON) for the
         * {@code /compile} endpoint which predates this field.
         */
        @JsonProperty("request_id")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String requestId,

        @JsonProperty("lexiconRuleName")
        String lexiconRuleName,

        /*
         * Term compilation strategy for this request.
         * Present only for the {@code /compile/bundle} endpoint where
         * {@code requestType} is declared at the request root level.
         * {@code null} (and absent from JSON) for {@code /compile} and
         * {@code /compile/csv}.
         */
        @JsonProperty("requestType")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String requestType,

        @JsonProperty("totalTerms")
        int totalTerms,

        @JsonProperty("passCount")
        int passCount,

        @JsonProperty("failedCount")
        int failedCount,

        @JsonProperty("hasFailures")
        boolean hasFailures,

        /* Always {@code "HYPERSCAN_NATIVE"} — no fallback engine. */
        @JsonProperty("engineMode")
        String engineMode,

        /*
         * Bundled Hyperscan library version (e.g. {@code "5.4.0-2.0.0"}).
         * Absent from the {@code /compile/bundle} response where version
         * pinning is handled at the Scan Engine side.
         */
        @JsonProperty("hyperscanVersion")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String hyperscanVersion,

        @JsonProperty("compiledAt")
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Instant compiledAt,

        /*
         * Total compilation time for the whole request, in milliseconds —
         * wall-clock time from the first term to the last, across all three
         * endpoints (including {@code /compile/bundle}, which now populates
         * this too — see {@code LexiconCompileBundleService#buildBundle}).
         * This is the only compilation-timing signal in the response now
         * that {@code TermCompilationResult} no longer carries a per-term
         * {@code compiledAt} timestamp; see this class's root-level
         * {@code compiledAt} for when the response was produced.
         */
        @JsonProperty("processingTimeMs")
        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        long processingTimeMs,

        @JsonProperty("results")
        List<TermCompilationResult> results,

        /*
         * Set ONLY for {@code /compile/bundle}, and ONLY when every term
         * resolved to PASS/FAILED normally but the combined multi-pattern
         * Hyperscan database build/serialisation itself then failed (see
         * {@code LexiconCompileBundleService#buildDatabasePortion}) — e.g. a
         * flag-compatibility or state-count problem that only surfaces once
         * every PASS expression is compiled together, which individual
         * per-term validation cannot catch. {@code null} (and absent from
         * JSON) whenever the database built successfully, or was never
         * expected to (e.g. every term FAILED translation) — a per-term
         * {@code compilationStatus} of FAILED already explains that case,
         * without needing this field. When non-null, the JSON no longer
         * represents an unqualified success even though every {@code results}
         * entry may show {@code compilationStatus: PASS} — the caller MUST
         * check this field, not just per-term status, before trusting that a
         * usable {@code .hdb} was produced.
         */
        @JsonProperty("databaseError")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String databaseError

) {

    // ── Factory: /compile and /compile/csv ────────────────────────────────────

    /**
     * Builds a response for the original {@code /compile} and
     * {@code /compile/csv} endpoints. {@code requestType} is omitted (null →
     * not serialised) — {@code requestId} is now always present, since
     * {@code TypedCompileRequest} (the single request type for every
     * endpoint) always carries one.
     */
    public static CompileResponse of(String requestId,
                                     String ruleName,
                                     List<TermCompilationResult> results,
                                     long processingTimeMs,
                                     String hyperscanVersion) {
        int passCount = (int) results.stream().filter(TermCompilationResult::isPass).count();
        int failedCount = (int) results.stream().filter(TermCompilationResult::isFailed).count();
        return new CompileResponse(
                requestId,
                ruleName,
                null,              // requestType — absent from /compile response
                results.size(),
                passCount, failedCount,
                failedCount > 0,
                "HYPERSCAN_NATIVE",
                hyperscanVersion,
                Instant.now(),
                processingTimeMs,
                results,
                null);              // databaseError — /compile never builds a database
    }

    // ── Factory: /compile/bundle ──────────────────────────────────────────────

    /**
     * Builds a response for the {@code /compile/bundle} endpoint.
     * {@code hyperscanVersion} is omitted ({@code null} → suppressed by
     * {@code NON_NULL}); {@code processingTimeMs} is populated the same way
     * {@link #of} populates it for {@code /compile}/{@code /compile/csv}.
     *
     * @param requestId       the caller-supplied {@code request_id}, echoed back
     * @param requestType     the root-level {@code requestType} from the request
     * @param ruleName        the lexicon rule name from the request
     * @param results         per-term compilation outcomes
     * @param processingTimeMs total wall-clock compilation time for the whole request, in milliseconds
     */
    public static CompileResponse ofBundle(String requestId,
                                           TermType requestType,
                                           String ruleName,
                                           List<TermCompilationResult> results,
                                           long processingTimeMs) {
        int passCount = (int) results.stream().filter(TermCompilationResult::isPass).count();
        int failedCount = (int) results.stream().filter(TermCompilationResult::isFailed).count();
        return new CompileResponse(
                requestId,
                ruleName,
                requestType.jsonValue(),
                results.size(),
                passCount, failedCount,
                failedCount > 0,
                "HYPERSCAN_NATIVE",
                null,              // hyperscanVersion — absent from bundle response
                Instant.now(),
                processingTimeMs,
                results,
                null);              // databaseError — set later via withDatabaseError if the build fails
    }

    // ── Copy helper ───────────────────────────────────────────────────────────

    /**
     * Returns a copy of this response with {@code requestId} set to the given
     * value. Used by the CSV endpoint controller to attach the auto-generated
     * UUID after the service has built the base response.
     *
     * <p>Records are immutable — this creates a new instance with all other
     * fields copied unchanged.
     *
     * @param requestId the UUID string to attach
     */
    public CompileResponse withRequestId(String requestId) {
        return new CompileResponse(
                requestId,
                this.lexiconRuleName,
                this.requestType,
                this.totalTerms,
                this.passCount,
                this.failedCount,
                this.hasFailures,
                this.engineMode,
                this.hyperscanVersion,
                this.compiledAt,
                this.processingTimeMs,
                this.results,
                this.databaseError);
    }

    /**
     * Returns a copy of this response with {@code databaseError} set — used
     * by {@code LexiconCompileBundleService} when every term resolved
     * PASS/FAILED normally but the combined Hyperscan database build itself
     * then failed, so the {@code /compile/bundle} JSON explicitly reflects
     * that the overall bundle is NOT usable, rather than only showing
     * per-term PASS statuses that would otherwise read as an unqualified
     * success.
     */
    public CompileResponse withDatabaseError(String databaseError) {
        return new CompileResponse(
                this.requestId,
                this.lexiconRuleName,
                this.requestType,
                this.totalTerms,
                this.passCount,
                this.failedCount,
                this.hasFailures,
                this.engineMode,
                this.hyperscanVersion,
                this.compiledAt,
                this.processingTimeMs,
                this.results,
                databaseError);
    }
}
