package com.db.macs3.ecomms.spectre.translator;

/**
 * A single lexical token produced by {@link Tokenizer}.
 *
 * <p>Using a real token stream (rather than repeated raw-index string
 * scanning) is the structural fix for a whole class of bugs the previous
 * ad-hoc implementation had: every place that needed to "find the next
 * operator respecting parens and quotes" re-implemented that scan from
 * scratch with its own depth-counter and its own off-by-one risk. Here it
 * is done exactly once, in {@link Tokenizer}, and everything downstream
 * — {@link ExpressionParser} — works with validated, bounds-safe tokens.
 *
 * <h2>Case sensitivity</h2>
 * <p>{@link #OR}, {@link #AND}, {@link #AND_NOT}, {@link #NOT},
 * {@link #NEAR}, and {@link #FOLLOWEDBY} are recognised ONLY in exact
 * upper-case form, per the reserved-keyword requirement. {@code or}, {@code and},
 * {@code near}, {@code not}, {@code followedby} in any other case are tokenised
 * as ordinary {@link #WORD} text, not as operators.
 */
public sealed interface Token {

    /**
     * {@code (}
     */
    record LParen() implements Token {
    }

    /**
     * {@code )}
     */
    record RParen() implements Token {
    }

    /**
     * The literal keyword {@code OR} (exact case).
     */
    record Or() implements Token {
    }

    /**
     * The literal keyword {@code AND} (exact case) — NOT immediately followed by {@code NOT}.
     */
    record And() implements Token {
    }

    /**
     * The two-word literal keyword {@code AND NOT} (exact case), tokenised as a single unit.
     */
    record AndNot() implements Token {
    }

    /**
     * The literal keyword {@code NOT} (exact case), or a leading {@code !}.
     */
    record Not() implements Token {
    }

    /**
     * The literal keyword {@code NEAR} immediately followed by {@code {n}} with
     * no intervening whitespace, where {@code n} is a single digit 1-9.
     * Validated by {@link Tokenizer} at lex time — malformed forms
     * ({@code NEAR{0}}, {@code NEAR{-1}}, {@code NEAR{10}}, {@code NEAR {1}}, etc.)
     * are rejected before the parser ever sees them.
     */
    record Near(int distance) implements Token {
    }

    /**
     * The literal keyword {@code FOLLOWEDBY} immediately followed by {@code {n}}; same validation as {@link Near}.
     */
    record FollowedBy(int distance) implements Token {
    }

    /**
     * A double-quoted phrase, e.g. {@code "please don't forward"}.
     * {@code text} is the content BETWEEN the quotes, not yet PCRE-escaped.
     */
    record QuotedPhrase(String text) implements Token {
    }

    /**
     * A single whitespace-free run of text: a word, a wildcarded word
     * ({@code chimp*}, {@code *word}), or a word containing a literal
     * {@code ?} (e.g. {@code he?d}). Not yet PCRE-escaped — that happens in
     * {@link PatternCodeGenerator}.
     */
    record Word(String text) implements Token {
    }
}
