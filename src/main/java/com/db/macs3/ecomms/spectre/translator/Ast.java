package com.db.macs3.ecomms.spectre.translator;

import java.util.List;

/**
 * Abstract syntax tree of a parsed lexicon term, produced by {@link ExpressionParser} and consumed
 * by {@link PatternCodeGenerator}, {@link PatternDecomposer} and {@link PatternComplexityAnalyzer}.
 *
 * <p>A node's children are exactly the resolved content of the parentheses that enclosed them, so
 * nesting depth in the tree mirrors nesting depth in the source term at any depth.
 */
sealed interface Ast {

    /**
     * {@code A OR B OR C} — alternation.
     */
    record Or(List<Ast> operands) implements Ast {
    }

    /**
     * {@code A AND B AND C} — every operand present ANYWHERE in the message, in ANY order, at ANY
     * distance. {@link PatternCodeGenerator} compiles it to one Hyperscan pattern: every ordering of
     * the operands joined by an unbounded {@code [\s\S]*} gap (the unbounded form of the gap
     * {@link Near} uses). Hyperscan has no lookahead, so this alternation of orderings is what makes
     * AND expressible without a post-filter.
     */
    record And(List<Ast> operands) implements Ast {
    }

    /**
     * {@code A AND NOT (B)} (and chained {@code A AND NOT (B) AND NOT (C)}, or NOT-groups folded in by
     * the parser). The term matches only when {@code required} is present AND NONE of
     * {@code excluded} is present, anywhere in the message.
     *
     * <p>Unlike {@link And} this cannot be a single Hyperscan pattern: "absent from the whole message"
     * needs negative lookaround, which Hyperscan lacks. {@link PatternCodeGenerator} therefore emits
     * two independent patterns — the required side and the excluded side (excluded operands OR'd) —
     * and the caller evaluates the condition after the scan. Only valid at the root of a term.
     */
    record AndNot(Ast required, List<Ast> excluded) implements Ast {
    }

    /**
     * {@code A NEAR{n} B} — bidirectional proximity, bounded gap of {@code n} words/chars.
     */
    record Near(Ast left, Ast right, int distance) implements Ast {
    }

    /**
     * {@code A FOLLOWEDBY{n} B} — directional proximity (A before B), bounded gap of {@code n} words/chars.
     */
    record FollowedBy(Ast left, Ast right, int distance) implements Ast {
    }

    /**
     * {@code NOT (X)} — an internal, transient node. It is produced by {@link ExpressionParser#parseAtom}
     * and folded, with the other operands of its enclosing {@code AND}, into an {@link AndNot} by
     * {@link ExpressionParser#parseAnd}. One that finds no enclosing {@code AND} (standalone, as an
     * {@link Or} alternative, or as a {@link Near}/{@link FollowedBy} operand) is a parse error, so a
     * {@code Not} never reaches code generation.
     */
    record Not(Ast operand) implements Ast {
    }

    /**
     * A single unquoted word — may contain a {@code *} wildcard (zero or more
     * characters) and/or a {@code ?} wildcard (exactly one character).
     */
    record Word(String text) implements Ast {
    }

    /**
     * Multiple unquoted words that appeared together inside one set of
     * parentheses with no operator between them, e.g. {@code (bomb this place)}
     * — a literal multi-word phrase. Each word is still wildcard-aware
     * (e.g. {@code (chimp* attack)} is valid).
     */
    record Phrase(List<String> words) implements Ast {
    }

    /**
     * A double-quoted phrase — always literal; {@code *} and {@code ?} inside are NOT special.
     */
    record QuotedPhrase(String text) implements Ast {
    }
}
