package com.db.macs3.ecomms.spectre.translator;

/**
 * A lexical token produced by {@link Tokenizer}. {@link ExpressionParser} works only with this
 * validated token stream, never with raw string indexes, so quote and parenthesis handling
 * lives in exactly one place.
 *
 * <p><b>Case sensitivity.</b> The reserved keywords {@code OR}, {@code AND}, {@code NOT},
 * {@code NEAR} and {@code FOLLOWEDBY} are recognised only in exact upper case; {@code or},
 * {@code and}, {@code near}, {@code not}, {@code followedby} are ordinary {@link Word} text.
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
     * The keyword {@code AND} (exact case) not followed by {@code NOT}.
     */
    record And() implements Token {
    }

    /**
     * The two-word keyword {@code AND NOT} (exact case), tokenised as one unit. What follows
     * {@code NOT} is irrelevant to recognising it, so {@code AND NOT(} and {@code AND NOT (} are
     * the same.
     */
    record AndNot() implements Token {
    }

    /**
     * The keyword {@code NOT} (exact case) standing on its own. {@link ExpressionParser} treats
     * {@code NOT '('} as the unary exclusion group and any other position as literal text or an error.
     */
    record Not() implements Token {
    }

    /**
     * The literal keyword {@code NEAR} immediately followed by {@code {n}} with
     * no intervening whitespace, where {@code n} is a whole number from 1 to
     * {@link Tokenizer#MAX_PROXIMITY_DISTANCE}. Validated by {@link Tokenizer}
     * at lex time — malformed forms ({@code NEAR{0}}, {@code NEAR{-1}},
     * {@code NEAR{51}}, {@code NEAR {1}}, etc.) are rejected before the
     * parser ever sees them.
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
