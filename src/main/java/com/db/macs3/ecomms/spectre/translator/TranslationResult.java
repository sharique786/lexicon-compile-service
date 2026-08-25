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
 * <p><b>{@code hsPatterns} is always a list — this is how decomposition is represented</b>
 * <p>A term whose required side is simple enough for Hyperscan to compile as
 * one pattern has {@code hsPatterns} with exactly one entry. A term too
 * structurally complex for that — see {@code PatternComplexityAnalyzer} and
 * {@code PatternDecomposer} — has {@code hsPatterns} with two or more
 * entries: independent, individually Hyperscan-validated leaf patterns that
 * a caller (or, for {@code /compile/bundle}, {@code HyperscanCombinationHandler})
 * combines with pure boolean AND. There is no separate boolean flag for
 * "was this decomposed" — the caller simply checks {@code hsPatterns.size()}.
 * The same applies to {@link #exclusionPatterns()} for an AND NOT term's
 * excluded side.
 *
 * <p><b>This is a real precision trade-off, not a lossless rewrite, whenever
 * a list has more than one entry.</b> Decomposition discards the DISTANCE and
 * ORDER constraint BETWEEN leaf patterns that a nested NEAR/FOLLOWEDBY
 * structure expressed — the leaves are combined with pure boolean AND ("all
 * of these appear somewhere in the message"), not with any positional
 * relationship to EACH OTHER. It does NOT, however, discard the gap width
 * itself: {@code PatternDecomposer} bakes each originating NEAR/FOLLOWEDBY
 * node's own gap fragment into the start of the leaf that followed it in the
 * original term text, so that leaf can never match with nothing preceding
 * it — only its anchor (specifically the PRECEDING leaf, rather than
 * whichever text is actually there) is lost, not the bound itself. A term
 * originally written as {@code (A FOLLOWEDBY{4} B) FOLLOWEDBY{4} C} — "these
 * in this order, this close together" — becomes, once decomposed, "A and
 * (something FOLLOWEDBY{4} B) and (something FOLLOWEDBY{4} C) all appear
 * somewhere in this message, independently of each other". This trade-off
 * exists specifically so a term that would otherwise be REJECTED outright
 * still produces a usable (if less precise) result — see {@link #warnings()},
 * which always carries an explicit warning whenever this trade-off applies to
 * either side, so no caller can silently treat a decomposed match as a
 * genuine proximity match without realizing precision was reduced.
 *
 * <p><b>AND NOT: a two-side contract, not a single regex</b>
 * <p>Hyperscan cannot express "absent from the whole message" — that is
 * exactly what negative lookaround is for, and Hyperscan supports none.
 * {@code A AND NOT B} therefore returns two independently Hyperscan-valid
 * pattern lists: {@link Success#hsPatterns()} (A) and
 * {@link Success#exclusionPatterns()} (B). The term is correctly matched
 * only when EVERY entry of {@code hsPatterns} is found (pure AND — trivially
 * true when there is exactly one entry) AND NO entry of
 * {@code exclusionPatterns} needing to ALL be found is fully satisfied —
 * see {@code HyperscanCombinationHandler} for the exact boolean formula this
 * becomes for {@code /compile/bundle}, including the De Morgan's-law
 * negation needed when {@code exclusionPatterns} itself has more than one
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
     * @param exclusionPatterns      the excluded side's Hyperscan PCRE pattern(s). Null
     *                               (not just empty) when {@code requiresExclusionCheck} is
     *                               false; never null or empty when it is true.
     * @param warnings               non-fatal issues worth surfacing to the caller — never null,
     *                               may be empty. Always includes an explicit entry whenever
     *                               decomposition applied to either side, and whenever the term
     *                               relied on chained NEAR/FOLLOWEDBY without explicit parentheses
     *                               (see {@code ExpressionParser}).
     */
    record Success(
            List<String> hsPatterns,
            int hsFlags,
            boolean requiresExclusionCheck,
            List<String> exclusionPatterns,
            List<String> warnings
    ) implements TranslationResult {

        public Success {
            if (hsPatterns == null || hsPatterns.isEmpty()) {
                throw new IllegalArgumentException("hsPatterns must not be null or empty");
            }
            if (requiresExclusionCheck && (exclusionPatterns == null || exclusionPatterns.isEmpty())) {
                throw new IllegalArgumentException(
                        "exclusionPatterns must not be null or empty when requiresExclusionCheck is true");
            }
            if (!requiresExclusionCheck && exclusionPatterns != null) {
                throw new IllegalArgumentException(
                        "exclusionPatterns must be null when requiresExclusionCheck is false");
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
    static TranslationResult success(List<String> patterns, int flags) {
        return new Success(patterns, flags, false, null, List.of());
    }

    /**
     * Factory: successful translation with no AND-NOT exclusion, carrying warnings.
     */
    static TranslationResult successWithWarnings(List<String> patterns, int flags, List<String> warnings) {
        return new Success(patterns, flags, false, null, List.copyOf(warnings));
    }

    /**
     * Factory: successful translation WITH an AND-NOT exclusion, no warnings.
     */
    static TranslationResult successWithExclusion(List<String> patterns, int flags, List<String> exclusionPatterns) {
        return new Success(patterns, flags, true, exclusionPatterns, List.of());
    }

    /**
     * Factory: successful translation WITH an AND-NOT exclusion, carrying warnings.
     */
    static TranslationResult successWithExclusionAndWarnings(
            List<String> patterns, int flags, List<String> exclusionPatterns, List<String> warnings) {
        return new Success(patterns, flags, true, exclusionPatterns, List.copyOf(warnings));
    }

    /**
     * Factory: failed translation.
     */
    static TranslationResult error(String message) {
        return new Error(message);
    }
}
