package com.db.macs3.ecomms.spectre.translator;

/**
 * Mutable context passed through {@link PatternCodeGenerator} while walking
 * one term's {@link Ast}. Accumulates flags and the (at most one) AND-NOT
 * exclusion pattern discovered along the way.
 *
 * <h2>Why there is no more {@code hasAndOp} / required-operands tracking</h2>
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
 * <h2>Why {@code exclusionPattern} still exists</h2>
 * <p>{@code AND NOT} is different in kind, not just degree: "B does not
 * appear anywhere in this message" cannot be compiled into the same
 * Hyperscan expression as "A appears somewhere" without negative lookaround,
 * which Hyperscan does not support. {@link #exclusionPattern} carries B's
 * OWN independently Hyperscan-valid pattern, to be checked separately by the
 * caller — see {@link Ast.AndNot} class Javadoc for the full contract.
 *
 * <h2>Why there is no more DOTALL flag-setting</h2>
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
    private String exclusionPattern = null;

    /**
     * Mark that a non-ASCII character was encountered.
     */
    void setNeedsUtf8() {
        this.needsUtf8 = true;
    }

    /**
     * Records the Hyperscan-valid pattern that must NOT match the same
     * message for an {@code AND NOT} term to be considered matched.
     * A term has at most one AND-NOT "level", so this is set at most once —
     * chained {@code AND NOT X AND NOT Y} combines X and Y's patterns into
     * ONE exclusion pattern (via OR) before this is called; see
     * {@link PatternCodeGenerator#generateAndNot}.
     */
    void setExclusionPattern(String pattern) {
        this.exclusionPattern = pattern;
    }

    boolean isNeedsUtf8() {
        return needsUtf8;
    }

    /**
     * @return the exclusion pattern, or {@code null} when this term has no AND NOT.
     */
    String getExclusionPattern() {
        return exclusionPattern;
    }

    /**
     * @return true when {@link #getExclusionPattern()} is non-null and must be checked by the caller.
     */
    boolean requiresExclusionCheck() {
        return exclusionPattern != null;
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
