package com.db.macs3.ecomms.spectre.translator;

import java.util.ArrayList;
import java.util.List;

/**
 * Mutable context passed through {@link PatternCodeGenerator} while walking
 * one term's {@link Ast}. Accumulates flags and the (at most one) AND-NOT
 * exclusion pattern discovered along the way.
 *
 * <p><b>Why there is no more {@code hasAndOp} / required-operands tracking</b>
 * <p>The previous design treated {@code AND} the same way it treated
 * {@code AND NOT}: emit an OR pre-scan pattern, and rely on separately
 * tracked "required operand" metadata for a downstream post-filter to
 * enforce actual AND semantics. That metadata was never actually consumed
 * by any caller, so in practice {@code "price AND rigging"} behaved like
 * {@code "price OR rigging"} — matching a message containing only "price".
 * {@code AND} is now compiled DIRECTLY into a single correct Hyperscan
 * pattern by {@link PatternCodeGenerator#generateAnd} (every ordering of the
 * operands joined by an unbounded gap — see {@link Ast.And} class Javadoc),
 * so no post-filter or operand bookkeeping is needed for it at all.
 *
 * <p><b>Why {@code exclusionRegex} still exists</b>
 * <p>{@code AND NOT} is different in kind, not just degree: "B does not
 * appear anywhere in this message" cannot be compiled into the same
 * Hyperscan expression as "A appears somewhere" without negative lookaround,
 * which Hyperscan does not support. {@link #exclusionRegex} carries B's
 * OWN independently Hyperscan-valid pattern, to be checked separately by the
 * caller — see {@link Ast.AndNot} class Javadoc for the full contract.
 *
 * <p><b>Why there is no more DOTALL flag-setting</b>
 * <p>DOTALL only changes what the {@code .} metacharacter matches. Every
 * gap this translator generates — NEAR/FOLLOWEDBY's bounded gap
 * ({@code (?:\s+\S+){0,n}\s+} or {@code [\s\S]{0,n}}) and AND's unbounded
 * gap ({@code [\s\S]*}) — is built from the {@code \s}/{@code \S}/
 * {@code [\s\S]} character classes, never from a bare {@code .}. DOTALL was
 * being set but had no effect on anything this translator actually produces;
 * removing it is a correctness cleanup discovered while fixing AND/AND-NOT,
 * not a behaviour change.
 */
class ParseContext {

    /**
     * HS_FLAG_CASELESS (1) — always applied.
     */
    static final int HS_FLAG_CASELESS = 1;
    /**
     * HS_FLAG_UTF8 (32) — treat pattern as UTF-8; needed for non-ASCII content.
     */
    static final int HS_FLAG_UTF8 = 32;
    /**
     * HS_FLAG_UCP (64) — use Unicode character properties; applied alongside UTF8.
     */
    static final int HS_FLAG_UCP = 64;

    /**
     * Maximum operands allowed in a single AND (or the required side of an
     * AND NOT) at one grammar level. {@link PatternCodeGenerator#generateAnd}
     * enumerates every ordering of the operands (N! permutations) to express
     * "all present, any order, unbounded distance" without lookahead — at 5
     * operands that is already 120 permutations of the combined sub-patterns.
     * Beyond this ceiling the resulting pattern reliably becomes too large
     * for Hyperscan to compile, the same class of failure the chained
     * NEAR/FOLLOWEDBY validation in {@link ExpressionParser} exists to catch
     * up front rather than let Hyperscan reject opaquely later.
     */
    static final int MAX_AND_OPERANDS = 5;

    private boolean needsUtf8 = false;
    private String exclusionRegex = null;
    private final List<String> warnings = new ArrayList<>();
    private final boolean wordBoundaries;
    private final boolean trailingBoundaries;

    /**
     * Whole-word matching enabled on both edges (the default) — see {@link #isWordBoundaries()}.
     */
    ParseContext() {
        this(true, true);
    }

    ParseContext(boolean wordBoundaries) {
        this(wordBoundaries, true);
    }

    /**
     * @param wordBoundaries     whether {@link PatternCodeGenerator} wraps literal
     *                           words/phrases in {@code \b...\b}. {@link TermSyntaxTranslator}
     *                           passes {@code false} for a term containing any non-ASCII
     *                           text, because that term's flags will include UCP, and
     *                           Hyperscan rejects {@code \b} in UCP mode.
     * @param trailingBoundaries whether the END edge of a literal gets its {@code \b}. Must be
     *                           {@code false} for leaves that will be sub-expressions of a native
     *                           {@code HS_FLAG_COMBINATION} (a decomposition-fallback term with no
     *                           AND NOT): Hyperscan refuses a combination whose sub-expression ends
     *                           in an assertion ("Have unordered match in sub-expressions" —
     *                           verified for {@code \b}, {@code $} and {@code (?:\W|$)} alike),
     *                           while a LEADING {@code \b} is accepted.
     */
    ParseContext(boolean wordBoundaries, boolean trailingBoundaries) {
        this.wordBoundaries = wordBoundaries;
        this.trailingBoundaries = trailingBoundaries;
    }

    /**
     * @return true when the end edge of a literal may carry {@code \b} — see the constructor.
     */
    boolean isTrailingBoundaries() {
        return trailingBoundaries;
    }

    /**
     * @return true when literal words/phrases must match as whole words. The flag
     * is decided once per term up front (not discovered during generation, like
     * {@link #isNeedsUtf8()}) because a boundary already emitted into one leaf
     * cannot be taken back once a LATER leaf turns out to need UCP.
     */
    boolean isWordBoundaries() {
        return wordBoundaries;
    }

    /**
     * Mark that a non-ASCII character was encountered.
     */
    void setNeedsUtf8() {
        this.needsUtf8 = true;
    }

    /**
     * Records a non-fatal precision/behavior warning discovered while
     * generating this term's pattern(s) — e.g. a mixed RTL+LTR FOLLOWEDBY
     * direction warning, or a char-based NEAR/FOLLOWEDBY gap clamped to
     * {@link MultiLanguagePatternBuilder#MAX_CHAR_GAP}. Null/blank is
     * ignored so callers can pass a possibly-absent warning unconditionally.
     */
    void addWarning(String warning) {
        if (warning != null && !warning.isBlank()) {
            warnings.add(warning);
        }
    }

    /**
     * @return every warning recorded so far via {@link #addWarning}, in
     * recording order. Read by {@link TermSyntaxTranslator#translate} after
     * generating (and re-generating, if decomposition falls back) each side,
     * so the term's final {@code TranslationResult.Success.warnings()} always
     * includes anything discovered while building the pattern(s) — not just
     * the decomposition/AND-NOT warnings assembled directly there.
     */
    List<String> getWarnings() {
        return List.copyOf(warnings);
    }

    /**
     * Records the Hyperscan-valid pattern that must NOT match the same
     * message for an {@code AND NOT} term to be considered matched.
     * A term has at most one AND-NOT "level", so this is set at most once —
     * chained {@code AND NOT X AND NOT Y} combines X and Y's patterns into
     * ONE exclusion pattern (via OR) before this is called; see
     * {@link PatternCodeGenerator#generateAndNot}.
     */
    void setexclusionRegex(String pattern) {
        this.exclusionRegex = pattern;
    }

    boolean isNeedsUtf8() {
        return needsUtf8;
    }

    /**
     * @return the exclusion pattern, or {@code null} when this term has no AND NOT.
     */
    String getexclusionRegex() {
        return exclusionRegex;
    }

    /**
     * @return true when {@link #getexclusionRegex()} is non-null and must be checked by the caller.
     */
    boolean requiresExclusionCheck() {
        return exclusionRegex != null;
    }

    /**
     * Computes the Hyperscan flag bitmask from accumulated context.
     *
     * <ul>
     *   <li>CASELESS (1)  — always</li>
     *   <li>UTF8    (32)  — if non-ASCII present</li>
     *   <li>UCP     (64)  — alongside UTF8 (makes \\s/\\S honour Unicode,
     *       critical for Arabic, Korean, CJK, Hebrew)</li>
     * </ul>
     */
    int computeFlags() {
        int flags = HS_FLAG_CASELESS;
        if (needsUtf8) {
            flags |= HS_FLAG_UTF8 | HS_FLAG_UCP;
        }
        return flags;
    }
}
