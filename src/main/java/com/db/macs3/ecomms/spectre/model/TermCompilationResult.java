package com.db.macs3.ecomms.spectre.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Compilation outcome of one lexicon term — the per-term object in every endpoint's JSON. Null fields
 * are omitted from the JSON.
 *
 * <p><b>Reading a PASS term.</b> {@code regexPattern} holds the required side's pattern(s):
 * <ul>
 *   <li><b>one entry</b> — a single self-contained Hyperscan pattern, with any NEAR/FOLLOWEDBY gap embedded
 *       (Hyperscan enforces distance and order itself);</li>
 *   <li><b>several entries</b> — the fallback for a side too large to compile as one pattern: independent,
 *       gap-less leaves that match when ALL are found anywhere in the message. The distance and order between
 *       them lives only in {@code resolvedPatterns}, which a consumer needing exact proximity must apply itself.
 *       A caller checks {@code regexPattern.size()}; there is no separate "was split" flag.</li>
 * </ul>
 * {@code exclusionRegex} is the same for an AND NOT term's excluded side. Translation warnings (fallback,
 * clamped gaps, skipped whole-word matching) are logged server-side and are NOT part of this object.
 *
 * <p><b>Evaluating a term, by endpoint</b>
 * <ul>
 *   <li>{@code /compile}, {@code /compile/csv} — the caller compiles the pattern lists itself. Matched iff EVERY
 *       {@code regexPattern} entry matches AND the excluded condition (EVERY {@code exclusionRegex} entry found)
 *       is NOT fully satisfied.</li>
 *   <li>{@code /compile/bundle}, no AND NOT — every pattern is in the {@code .hdb}; several leaves are QUIET
 *       sub-expressions of a native COMBINATION reported at {@code hyperscanExpressionId}, so Hyperscan
 *       evaluates "all present" itself.</li>
 *   <li>{@code /compile/bundle}, AND NOT — no native combination (Hyperscan's eager evaluation makes a mixed
 *       positive/negative formula unsafe; see {@code HyperscanCombinationHandler}). Each required and excluded
 *       pattern is its own expression ({@code requiredExpressionIds}/{@code excludedExpressionIds}) and the
 *       caller evaluates the condition after the whole scan, against the complete set of matched ids.</li>
 * </ul>
 *
 * <p><b>{@code resolvedPatterns}</b> is this term (for AND NOT, both sides joined by the literal keyword)
 * rendered with {@code NEAR{n}} / {@code FOLLOWEDBY{n}} / {@code AND NOT} keyword text standing in for gaps,
 * e.g. {@code "\bbash\b FOLLOWEDBY{30} (?:\bfuck\b|\bfck\b)"}. Despite the plural name it is always ONE string.
 * It is populated for every PASS Natural-Language term on all three endpoints. When a side fell back to
 * leaves, every leaf appears in it byte-for-byte and in order, so a consumer can correlate a leaf's match with its
 * position; when it compiled as one pattern there is no such correspondence (Hyperscan already enforced it).
 * Regex-type terms have none.
 *
 * <p><b>{@code hyperscanExpressionId}</b> ({@code /compile/bundle} only, terms without AND NOT) is the
 * term's own number from its {@code termId}'s {@code ::<n>} suffix, whether it needed one expression or a
 * combination, so a consumer that knows the number can predict the id to watch for. AND NOT terms report
 * through {@code requiredExpressionIds}/{@code excludedExpressionIds} instead. Auxiliary ids never collide
 * with any term number.
 *
 * <p><b>{@code patternMapping}</b> ({@code /compile/bundle} only, terms needing more than one id) is a boolean
 * formula over the term's ids in Hyperscan's own {@code &}/{@code !} syntax, e.g. {@code "(5&6&7)"} or
 * {@code "(8&!(9&10&11))"}. Each side is a bare id, or a parenthesised AND-join that follows the term's authored
 * nesting. For several leaves without AND NOT it mirrors the COMBINATION inside the {@code .hdb}; for AND NOT
 * it is the ONLY place the formula exists, so a consumer must read it from this JSON and apply it after the
 * scan.
 *
 * <p>Failure fields: at most one of {@code errorLog} / {@code translationError} is set, and both are null for PASS.
 *
 * @param termId                 the request's term id, echoed
 * @param termDescription        the request's description (after newline/tab normalisation), echoed
 * @param compilationStatus      {@code PASS} or {@code FAILED}
 * @param regexPattern           the required side's pattern(s); non-empty for PASS; null for a translation
 *                               failure; may hold the attempted pattern for a Hyperscan-stage failure
 * @param errorLog               Hyperscan's message when it rejected a pattern — set only for a Regex-type term
 *                               on {@code /compile/bundle} (the translator reports its own Hyperscan rejections
 *                               as {@code translationError})
 * @param translationError       why the operator-language term could not be translated (syntax error, or a leaf
 *                               Hyperscan rejected)
 * @param hyperscanFlags         the flag bitmask (1=CASELESS, 32=UTF8, 64=UCP); NOT serialised — the bundle build
 *                               needs it, and it is logged
 * @param requiresExclusionCheck true for an AND NOT term; false for AND-only terms, Regex-type and failed terms
 * @param exclusionRegex         the excluded side's pattern(s); non-null exactly when {@code requiresExclusionCheck}
 * @param resolvedPatterns       see above; null for a FAILED or Regex-type term
 * @param hyperscanExpressionId  see above
 * @param requiredExpressionIds  AND NOT terms on {@code /compile/bundle}: one id per {@code regexPattern} entry
 * @param excludedExpressionIds  AND NOT terms on {@code /compile/bundle}: one id per {@code exclusionRegex} entry;
 *                               the exclusion holds only when EVERY one was found (same AND convention as the
 *                               required side), so a term matches only if at least one excluded id is absent
 * @param patternMapping         see above
 * @param patternFormulaTemplate {@code regexPattern}'s AND grouping with {@code {i}} placeholders; NOT serialised,
 *                               used by {@code HyperscanCombinationHandler} to build {@code patternMapping} and the
 *                               combination in the term's authored shape (e.g. {@code "(54&(55&56))"})
 * @param exclusionFormulaTemplate the same for {@code exclusionRegex}; null unless {@code requiresExclusionCheck}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TermCompilationResult(

        @JsonProperty("termId")
        String termId,

        @JsonProperty("termDescription")
        String termDescription,

        @JsonProperty("compilationStatus")
        CompilationStatus compilationStatus,

        @JsonProperty("regexPattern")
        List<String> regexPattern,

        @JsonProperty("errorLog")
        String errorLog,

        @JsonProperty("translationError")
        String translationError,

        @JsonIgnore
        int hyperscanFlags,

        @JsonProperty("requiresExclusionCheck")
        boolean requiresExclusionCheck,

        @JsonProperty("exclusionRegex")
        List<String> exclusionRegex,

        @JsonProperty("resolvedPatterns")
        String resolvedPatterns,

        @JsonProperty("hyperscanExpressionId")
        Integer hyperscanExpressionId,

        @JsonProperty("requiredExpressionIds")
        List<Integer> requiredExpressionIds,

        @JsonProperty("excludedExpressionIds")
        List<Integer> excludedExpressionIds,

        @JsonProperty("patternMapping")
        String patternMapping,

        @JsonIgnore
        String patternFormulaTemplate,

        @JsonIgnore
        String exclusionFormulaTemplate

) {
    // ── Factory methods ───────────────────────────────────────────────────────

    /**
     * Creates a PASS result. {@code regexPattern} and (when
     * {@code requiresExclusionCheck}) {@code exclusionRegex} may each have
     * one entry (no NEAR/FOLLOWEDBY structure) or several (split) — see class Javadoc.
     * {@code resolvedPatterns} should be null only for a Regex-type term
     * (which never goes through the translator this field is built from).
     * Any translation warnings are logged by the caller (see
     * {@code LexiconCompileService#compileTerm}) — this result carries none.
     */
    public static TermCompilationResult pass(TypedCompileRequest.TermInput input,
                                             List<String> regexPattern,
                                             int hyperscanFlags,
                                             boolean requiresExclusionCheck,
                                             List<String> exclusionRegex,
                                             String resolvedPatterns,
                                             String patternFormulaTemplate,
                                             String exclusionFormulaTemplate) {
        return new TermCompilationResult(
                input.termId(), input.termDescription(),
                CompilationStatus.PASS,
                regexPattern, null, null,
                hyperscanFlags, requiresExclusionCheck, exclusionRegex,
                resolvedPatterns,
                null, null, null, null,
                patternFormulaTemplate, exclusionFormulaTemplate);
    }

    /**
     * Convenience overload for a PASS result with no AND-NOT exclusion and
     * no {@code resolvedPatterns} — used for Regex-type terms, which never
     * go through the translator/AST this field is built from.
     */
    public static TermCompilationResult pass(TypedCompileRequest.TermInput input,
                                             List<String> regexPattern,
                                             int hyperscanFlags) {
        return pass(input, regexPattern, hyperscanFlags, false, null, null, null, null);
    }

    /**
     * Creates a FAILED result where Hyperscan rejected a pattern — the main
     * pattern, the exclusion pattern, or a leaf of either side
     * (the message says which).
     *
     * @param regexPattern the pattern(s) attempted, for visibility — may be null
     *                          if the failure was in the exclusion side specifically
     */
    public static TermCompilationResult failedHyperscan(TypedCompileRequest.TermInput input,
                                                        List<String> regexPattern,
                                                        String errorLog,
                                                        int hyperscanFlags) {
        return new TermCompilationResult(
                input.termId(), input.termDescription(),
                CompilationStatus.FAILED,
                regexPattern, errorLog, null,
                hyperscanFlags, false, null,
                null,
                null, null, null, null,
                null, null);
    }

    /**
     * Creates a FAILED result where the term's syntax could not be translated
     * into a PCRE pattern at all — including a leaf with no further
     * NEAR/FOLLOWEDBY structure that was itself rejected by Hyperscan — see
     * {@code TermSyntaxTranslator}. Hyperscan was never invoked.
     */
    public static TermCompilationResult failedTranslation(TypedCompileRequest.TermInput input,
                                                          String translationError) {
        return new TermCompilationResult(
                input.termId(), input.termDescription(),
                CompilationStatus.FAILED,
                null, null, translationError,
                0, false, null,
                null,
                null, null, null, null,
                null, null);
    }

    /**
     * Returns a copy of this (PASS, non-AND-NOT) result with
     * {@link #hyperscanExpressionId} set — used only by
     * {@code LexiconCompileBundleService}, which always sets it to the
     * term's own term number (see class Javadoc). Never used for an AND NOT
     * term — see {@link #withExpressionIds}.
     *
     * @param patternMapping the logical formula over this term's leaf ids —
     *                       see class Javadoc "patternMapping" — null for a
     *                       simple, non-split term (nothing to map)
     */
    public TermCompilationResult withHyperscanExpressionId(int id, String patternMapping) {
        return new TermCompilationResult(
                termId, termDescription, compilationStatus,
                regexPattern, errorLog, translationError,
                hyperscanFlags, requiresExclusionCheck, exclusionRegex,
                resolvedPatterns,
                id, null, null, patternMapping,
                patternFormulaTemplate, exclusionFormulaTemplate);
    }

    /**
     * Returns a copy of this (PASS, AND NOT) result with
     * {@link #requiredExpressionIds} and {@link #excludedExpressionIds} set —
     * used only by {@code LexiconCompileBundleService} for a term where
     * {@link #requiresExclusionCheck} is true. {@link #hyperscanExpressionId}
     * remains null — see class Javadoc "AND NOT has two different correct
     * implementations".
     *
     * @param patternMapping the AND-NOT formula over the required/excluded
     *                       ids — see class Javadoc "patternMapping" — this
     *                       is the ONLY place an AND NOT term's combination
     *                       formula is recorded, since the {@code .hdb}
     *                       itself never encodes it
     */
    public TermCompilationResult withExpressionIds(List<Integer> requiredExpressionIds,
                                                   List<Integer> excludedExpressionIds,
                                                   String patternMapping) {
        return new TermCompilationResult(
                termId, termDescription, compilationStatus,
                regexPattern, errorLog, translationError,
                hyperscanFlags, requiresExclusionCheck, exclusionRegex,
                resolvedPatterns,
                null, requiredExpressionIds, excludedExpressionIds, patternMapping,
                patternFormulaTemplate, exclusionFormulaTemplate);
    }

    /**
     * Not part of the response JSON ({@code @JsonIgnore}) — record accessor
     * naming (Jackson would otherwise also serialise this as a bean-style
     * {@code "pass"} property alongside {@code compilationStatus}, which
     * already conveys the same information).
     */
    @JsonIgnore
    public boolean isPass() {
        return CompilationStatus.PASS == compilationStatus;
    }

    /**
     * Not part of the response JSON — see {@link #isPass()}.
     */
    @JsonIgnore
    public boolean isFailed() {
        return CompilationStatus.FAILED == compilationStatus;
    }
}
