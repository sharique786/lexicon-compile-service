package com.db.macs3.ecomms.spectre.translator;

import java.util.List;

/**
 * Result of one {@link TermSyntaxTranslator#translate} call: a {@link Success} or an {@link Error}.
 * <pre>
 * switch (result) {
 *   case TranslationResult.Success s -> use(s.hsPatterns(), s.hsFlags());
 *   case TranslationResult.Error   e -> log(e.message());
 * }
 * </pre>
 *
 * <p><b>{@code hsPatterns} is always a list.</b> Exactly one entry when the required side is a
 * single self-contained pattern: no proximity, proximity merged into one gap-embedded pattern, or
 * NEAR/FOLLOWEDBY nested inside an {@code OR}. Two or more entries when the side fell back to
 * {@link PatternDecomposer}'s independent, individually Hyperscan-validated leaves, which a caller
 * combines with boolean AND (for {@code /compile/bundle}, {@code HyperscanCombinationHandler} does
 * so natively). The same holds for {@link Success#exclusionRegexs()}. Callers check the list size;
 * there is no separate "was split" flag.
 *
 * <p><b>A multi-entry list is a precision trade-off.</b> Leaves match when ALL are found anywhere in
 * the message; the NEAR/FOLLOWEDBY distance and order between leaves is not encoded in them. It is
 * carried, with the author's raw distance, as literal {@code NEAR{n}} / {@code FOLLOWEDBY{n}} /
 * {@code AND NOT} text in {@link Success#resolvedPattern()}, which a caller needing exact proximity
 * must read and apply. {@link Success#warnings()} always carries an entry when this applies to
 * either side.
 *
 * <p><b>AND NOT is two lists.</b> Hyperscan cannot express "absent from the whole message", so
 * {@code A AND NOT B} yields {@link Success#hsPatterns()} (A) and {@link Success#exclusionRegexs()}
 * (B). The term matches iff every entry of A is found and NOT every entry of B is found (an
 * excluded side with several entries is excluded only when all of them are present).
 * {@code HyperscanCombinationHandler} documents the resulting evaluation rule.
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
     * @param hsPatterns             the required side's Hyperscan PCRE pattern(s); never null or empty
     * @param hsFlags                flag bitmask: 1=CASELESS, 32=UTF8, 64=UCP
     * @param requiresExclusionCheck true for an AND NOT term — the caller must also check the excluded side
     * @param exclusionRegexs        the excluded side's pattern(s); null when {@code requiresExclusionCheck}
     *                               is false, never null or empty when it is true
     * @param warnings               non-fatal issues for the caller; never null, may be empty. Includes an
     *                               entry whenever a side fell back to leaves, a gap was clamped, chained
     *                               proximity was used without parentheses, whole-word matching was skipped
     *                               (non-ASCII term), and so on
     * @param resolvedPattern        the term rendered with literal {@code NEAR{n}} / {@code FOLLOWEDBY{n}} /
     *                               {@code AND NOT} keyword text; always exactly one non-blank string. When a
     *                               side fell back to leaves, every leaf appears in it byte-for-byte
     * @param patternFormulaTemplate {@code hsPatterns}' boolean-AND grouping with {@code {i}} leaf-index
     *                               placeholders (see {@code PatternDecomposer.Result#formulaTemplate()});
     *                               never part of the JSON response, used by {@code HyperscanCombinationHandler}
     * @param exclusionFormulaTemplate the same for {@code exclusionRegexs}; null when
     *                               {@code requiresExclusionCheck} is false
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
