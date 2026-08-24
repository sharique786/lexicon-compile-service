package com.db.macs3.ecomms.spectre.translator;

import java.util.List;

/**
 * Abstract syntax tree for a parsed lexicon term, produced by
 * {@link ExpressionParser} and consumed by {@link PatternCodeGenerator}.
 *
 * <p>Working with an explicit tree — rather than generating pattern
 * fragments directly while parsing, as the previous implementation did —
 * is what makes "brackets resolve first, then NEAR/FOLLOWEDBY/AND/OR" a
 * structural property of the code rather than something that has to be
 * gotten right by careful ordering of string operations. A node's children
 * are, by construction, exactly the fully-resolved contents of whatever
 * parentheses enclosed them; nesting depth in the tree mirrors nesting
 * depth in the source term exactly, at any depth.
 */
sealed interface Ast {

    /** {@code A OR B OR C} — alternation. */
    record Or(List<Ast> operands) implements Ast {}

    /**
     * {@code A AND B AND C} — all operands must be present, ANYWHERE in the
     * message, in ANY order, with NO distance limit. Compiled DIRECTLY into
     * a single valid Hyperscan pattern by {@link PatternCodeGenerator} —
     * every ordering of the operands, joined by an unbounded
     * {@code [\s\S]*} gap (the same technique {@link Ast.Near} uses for a
     * BOUNDED gap, just without the bound). This needs no lookahead, no
     * post-filter, and no scan-time cooperation from the caller — see
     * {@link PatternCodeGenerator} class Javadoc for why the previous
     * lookahead-based design (documented in the old README) could never
     * have worked against Hyperscan, which does not support lookaround at all.
     */
    record And(List<Ast> operands) implements Ast {}

    /**
     * {@code A AND NOT B} (and its chained form {@code A AND NOT B AND NOT C},
     * where every entry in {@code excluded} is combined into one exclusion
     * check — the term matches only when {@code required} is present AND
     * NONE of {@code excluded} is present, anywhere in the message).
     *
     * <p>Unlike {@link And}, this CANNOT be compiled into a single Hyperscan
     * pattern — "B does not appear anywhere in this message" is not
     * expressible without negative lookaround, which Hyperscan does not
     * support (this is the fundamental fact the old README got wrong).
     * {@link PatternCodeGenerator} therefore emits TWO independent, plain
     * Hyperscan-valid patterns: {@code required}'s pattern (the term's
     * {@code hsPattern}) and {@code excluded}'s combined pattern (the
     * term's {@code exclusionPattern}). Both are Hyperscan-validated at
     * compile time. A caller gets a correct result only by checking BOTH at
     * scan time: the term matches iff {@code hsPattern} matches AND
     * {@code exclusionPattern} does NOT match the same message — see the
     * README's "AND NOT: the two-pattern contract" section.
     */
    record AndNot(Ast required, List<Ast> excluded) implements Ast {}

    /** {@code A NEAR{n} B} — bidirectional proximity, bounded gap of {@code n} words/chars. */
    record Near(Ast left, Ast right, int distance) implements Ast {}

    /** {@code A FOLLOWEDBY{n} B} — directional proximity (A before B), bounded gap of {@code n} words/chars. */
    record FollowedBy(Ast left, Ast right, int distance) implements Ast {}

    /** A single unquoted word — may contain a {@code *} wildcard and/or a literal {@code ?}. */
    record Word(String text) implements Ast {}

    /**
     * Multiple unquoted words that appeared together inside one set of
     * parentheses with no operator between them, e.g. {@code (bomb this place)}
     * — a literal multi-word phrase. Each word is still wildcard-aware
     * (e.g. {@code (chimp* attack)} is valid).
     */
    record Phrase(List<String> words) implements Ast {}

    /** A double-quoted phrase — always literal; {@code *} and {@code ?} inside are NOT special. */
    record QuotedPhrase(String text) implements Ast {}
}
