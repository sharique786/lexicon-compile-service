package com.db.macs3.ecomms.spectre.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * Top-level response of every compile endpoint. Absent fields are omitted from the JSON.
 *
 * @param requestId        {@code request_id}, echoed (or generated for CSV); present on every endpoint
 * @param lexiconRuleName  the rule name
 * @param requestType      {@code "Natural Language"} or {@code "Regex"}; present only for {@code /compile/bundle}
 * @param totalTerms       number of terms
 * @param passCount        terms that reached PASS
 * @param failedCount      terms that reached FAILED
 * @param hasFailures      true when {@code failedCount > 0}
 * @param engineMode       always {@code "HYPERSCAN_NATIVE"} (no fallback engine)
 * @param hyperscanVersion the bundled library version; present for {@code /compile} and {@code /compile/csv},
 *                         absent for {@code /compile/bundle}
 * @param compiledAt       when the response was produced (ISO-8601)
 * @param processingTimeMs wall-clock compilation time for the whole request; omitted when 0. The only timing
 *                         signal — terms carry no per-term timestamp
 * @param results          one {@link TermCompilationResult} per term, in request order
 * @param databaseError    {@code /compile/bundle} only: set when every term resolved normally but the combined
 *                         database build then failed. Null otherwise. When non-null the caller must not trust
 *                         per-term PASS statuses as meaning a usable {@code .hdb} exists
 */
public record CompileResponse(

        @JsonProperty("request_id")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String requestId,

        @JsonProperty("lexiconRuleName")
        String lexiconRuleName,

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

        @JsonProperty("engineMode")
        String engineMode,

        @JsonProperty("hyperscanVersion")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String hyperscanVersion,

        @JsonProperty("compiledAt")
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Instant compiledAt,

        @JsonProperty("processingTimeMs")
        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        long processingTimeMs,

        @JsonProperty("results")
        List<TermCompilationResult> results,

        @JsonProperty("databaseError")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String databaseError

) {

    // ── Factory: /compile and /compile/csv ────────────────────────────────────

    /**
     * Builds the response for {@code /compile} and {@code /compile/csv}: {@code requestType} omitted,
     * {@code hyperscanVersion} included.
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
     * Builds the response for {@code /compile/bundle}: {@code requestType} included, {@code hyperscanVersion}
     * omitted.
     *
     * @param requestId       the caller's {@code request_id}
     * @param requestType     the root {@code requestType}
     * @param ruleName        the lexicon rule name
     * @param results         per-term outcomes
     * @param processingTimeMs total wall-clock compilation time, in milliseconds
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
     * A copy of this response with {@code requestId} replaced (records are immutable).
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
     * A copy of this response with {@code databaseError} set, used when every term resolved normally but the
     * combined database build failed, so the JSON itself says the bundle is not usable.
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
