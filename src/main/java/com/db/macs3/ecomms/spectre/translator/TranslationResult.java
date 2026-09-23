package com.db.macs3.ecomms.spectre.translator;

import java.util.List;

/**
 * Sealed result type for one {@link TermSyntaxTranslator#translate} call.
 *
 * <p>JDK 21 sealed interface with two permitted records. Use pattern matching switch:
 * <pre>
 * switch (result) {
 *   case TranslationResult.Success s -> use(s.hsPatterns(), s.hsFlags());
 *   case TranslationResult.Error   e -> log(e.message());
 * }
 * </pre>
 *
 * <p><b>{@code hsPatterns} is always a list — this is how NEAR/FOLLOWEDBY splitting is represented</b>
 * <p>A term whose required side has no NEAR/FOLLOWEDBY structure (or whose
 * NEAR/FOLLOWEDBY is nested inside an {@code OR} — see
 * {@code PatternDecomposer} class Javadoc "the one exception") has
 * {@code hsPatterns} with exactly one entry. A term with NEAR/FOLLOWEDBY
 * structure elsewhere always splits — see {@code PatternDecomposer} — into
 * two or more entries: independent, individually Hyperscan-validated leaf
 * patterns that a caller (or, for {@code /compile/bundle},
 * {@code HyperscanCombinationHandler}) combines with pure boolean AND.
 * There is no separate boolean flag for "was this split" — the caller
 * simply checks {@code hsPatterns.size()}. The same applies to
 * {@link #exclusionRegexs()} for an AND NOT term's excluded side.
 *
 * <p><b>This is a real precision trade-off, not a lossless rewrite, whenever
 * a list has more than one entry.</b> Splitting discards the DISTANCE and
 * ORDER constraint BETWEEN leaf patterns that a NEAR/FOLLOWEDBY structure
 * expressed — the leaves are combined with pure boolean AND ("all of these
 * appear somewhere in the message"), not with any positional relationship
 * to EACH OTHER. Unlike an earlier revision of this codebase, NO gap
 * fragment is baked into any leaf any more — the full relationship,
 * including the term author's raw distance, is instead conveyed separately
 * via {@link #resolvedPattern()}, as literal {@code NEAR{n}}/
 * {@code FOLLOWEDBY{n}}/{@code AND NOT} keyword text — see that field's
 * Javadoc and {@code TermCompilationResult.resolvedPatterns} for the full
 * contract. A term originally written as
 * {@code (A FOLLOWEDBY{4} B) FOLLOWEDBY{4} C} — "these in this order, this
 * close together" — becomes, once split, "A and B and C all appear
 * somewhere in this message, independently of each other" for
 * {@code hsPatterns} alone; a caller that needs the original proximity
 * relationship reads {@link #resolvedPattern()} instead. This trade-off
 * exists specifically so a term whose NEAR/FOLLOWEDBY gap would otherwise
 * risk "Pattern is too large" still produces a usable result — see
 * {@link #warnings()}, which always carries an explicit warning whenever
 * this trade-off applies to either side, so no caller can silently treat a
 * split match as a genuine proximity match without realizing precision was
 * moved to {@link #resolvedPattern()}.
 *
 * <p><b>AND NOT: a two-side contract, not a single regex</b>
 * <p>Hyperscan cannot express "absent from the whole message" — that is
 * exactly what negative lookaround is for, and Hyperscan supports none.
 * {@code A AND NOT B} therefore returns two independently Hyperscan-valid
 * pattern lists: {@link Success#hsPatterns()} (A) and
 * {@link Success#exclusionRegexs()} (B). The term is correctly matched
 * only when EVERY entry of {@code hsPatterns} is found (pure AND — trivially
 * true when there is exactly one entry) AND NO entry of
 * {@code exclusionRegexs} needing to ALL be found is fully satisfied —
 * see {@code HyperscanCombinationHandler} for the exact boolean formula this
 * becomes for {@code /compile/bundle}, including the De Morgan's-law
 * negation needed when {@code exclusionRegexs} itself has more than one
 * entry.
 */
public sealed interface TranslationResult
        permits TranslationResult.Success, TranslationResult.Error {

    /**
     * Returns true when translation succeeded.
     */
    boolean isSuccess();

    /**
     * Successful translation.
     *
     * @param hsPatterns             the required side's Hyperscan PCRE pattern(s) — see class
     *                               Javadoc. Never null or empty for a {@code Success}.
     * @param hsFlags                bitmask: 1=CASELESS, 32=UTF8, 64=UCP
     * @param requiresExclusionCheck true when this term used AND NOT — the
     *                               caller MUST also check the excluded side
     * @param exclusionRegexs      the excluded side's Hyperscan PCRE pattern(s). Null
     *                               (not just empty) when {@code requiresExclusionCheck} is
     *                               false; never null or empty when it is true.
     * @param warnings               non-fatal issues worth surfacing to the caller — never null,
     *                               may be empty. Always includes an explicit entry whenever
     *                               NEAR/FOLLOWEDBY splitting applied to either side, and whenever the term
     *                               relied on chained NEAR/FOLLOWEDBY without explicit parentheses
     *                               (see {@code ExpressionParser}).
     * @param resolvedPattern        this term rendered with literal
     *                               {@code NEAR{n}}/{@code FOLLOWEDBY{n}}/{@code AND NOT} keyword text
     *                               standing in for whatever {@code hsPatterns}/{@code exclusionRegexs}
     *                               no longer encode as a gap regex — always exactly one string, never
     *                               null for a {@code Success}. Every leaf substring within it is
     *                               byte-identical to the corresponding {@code hsPatterns}/
     *                               {@code exclusionRegexs} entry — see {@code PatternDecomposer} class
     *                               Javadoc for exactly how this is built.
     * @param patternFormulaTemplate {@code hsPatterns}' own boolean-AND grouping structure, using
     *                               {@code {i}} leaf-index placeholders — see
     *                               {@code PatternDecomposer.Result#formulaTemplate()}. Not part of the
     *                               public JSON contract; consumed only by {@code HyperscanCombinationHandler}
     *                               at {@code /compile/bundle} time to build a {@code patternMapping}/native
     *                               combination formula that reflects the term's actual authored nesting.
     * @param exclusionFormulaTemplate the same, for {@code exclusionRegexs}' own grouping — null (not just
     *                               empty) when {@code requiresExclusionCheck} is false.
     */
    record Success(
            List<String> hsPatterns,
            int hsFlags,
            boolean requiresExclusionCheck,
            List<String> exclusionRegexs,
            List<String> warnings,
            String resolvedPattern,
            String patternFormulaTemplate,
            String exclusionFormulaTemplate
    ) implements TranslationResult {

        public Success {
            if (hsPatterns == null || hsPatterns.isEmpty()) {
                throw new IllegalArgumentException("hsPatterns must not be null or empty");
            }
            if (requiresExclusionCheck && (exclusionRegexs == null || exclusionRegexs.isEmpty())) {
                throw new IllegalArgumentException(
                        "exclusionRegexs must not be null or empty when requiresExclusionCheck is true");
            }
            if (!requiresExclusionCheck && exclusionRegexs != null) {
                throw new IllegalArgumentException(
                        "exclusionRegexs must be null when requiresExclusionCheck is false");
            }
            if (resolvedPattern == null || resolvedPattern.isBlank()) {
                throw new IllegalArgumentException("resolvedPattern must not be null or blank");
            }
            if (!requiresExclusionCheck && exclusionFormulaTemplate != null) {
                throw new IllegalArgumentException(
                        "exclusionFormulaTemplate must be null when requiresExclusionCheck is false");
            }
        }

        /**
         * @return true always
         */
        public boolean isSuccess() {
            return true;
        }
    }

    /**
     * Failed translation.
     *
     * @param message human-readable error
     */
    record Error(String message) implements TranslationResult {

        /**
         * @return false always
         */
        public boolean isSuccess() {
            return false;
        }
    }

    // ── Factories ────────────────────────────────────────────────────────────

    /**
     * Factory: successful translation with no AND-NOT exclusion, no warnings.
     */
    static TranslationResult success(List<String> patterns, int flags, String resolvedPattern) {
        return new Success(patterns, flags, false, null, List.of(), resolvedPattern, null, null);
    }

    /**
     * Factory: successful translation with no AND-NOT exclusion, carrying warnings.
     */
    static TranslationResult successWithWarnings(
            List<String> patterns, int flags, List<String> warnings, String resolvedPattern) {
        return new Success(patterns, flags, false, null, List.copyOf(warnings), resolvedPattern, null, null);
    }

    /**
     * Factory: successful translation WITH an AND-NOT exclusion, no warnings.
     */
    static TranslationResult successWithExclusion(
            List<String> patterns, int flags, List<String> exclusionRegexs, String resolvedPattern) {
        return new Success(patterns, flags, true, exclusionRegexs, List.of(), resolvedPattern, null, null);
    }

    /**
     * Factory: successful translation WITH an AND-NOT exclusion, carrying warnings.
     */
    static TranslationResult successWithExclusionAndWarnings(
            List<String> patterns, int flags, List<String> exclusionRegexs, List<String> warnings,
            String resolvedPattern) {
        return new Success(patterns, flags, true, exclusionRegexs, List.copyOf(warnings), resolvedPattern, null, null);
    }

    /**
     * Factory: failed translation.
     */
    static TranslationResult error(String message) {
        return new Error(message);
    }
}
