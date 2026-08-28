package com.db.macs3.ecomms.spectre.translator;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a {@code NEAR}/{@code FOLLOWEDBY} tree into independent leaf
 * sub-patterns, and — in the same pass — builds a literal-keyword
 * "resolved" text representation of the same tree (see
 * {@link TermCompilationResult#resolvedPatterns()}).
 *
 * <p><b>This is now the unconditional, always-on path for any term
 * containing proximity structure — not a complexity-overflow fallback.</b>
 * A previous revision of this codebase only decomposed a term when a
 * heuristic ({@code PatternComplexityAnalyzer}) or a real Hyperscan
 * rejection said the gap-embedded single pattern would be "too large" —
 * and, when it did decompose, baked each NEAR/FOLLOWEDBY node's own gap
 * fragment as a literal prefix onto the leaf that followed it, as a "safe
 * strengthening" over an even earlier revision that discarded the gap
 * entirely.
 *
 * <p><b>Both of those are gone now, by design, not by regression.</b> Every
 * NEAR/FOLLOWEDBY node (with one narrow, deliberate exception — see below)
 * always splits into independent leaves, and the gap is NEVER compiled into
 * a regex fragment anywhere, not even as a leaf prefix. The relationship
 * between leaves is instead conveyed as literal operator text — {@code
 * " NEAR{n} "} / {@code " FOLLOWEDBY{n} "} — using the term author's raw,
 * un-clamped, un-multiplied distance, via {@link Result#resolvedText()}. A
 * downstream Java-regex-based consumer (Lexicon Scan Engine / Lexicon
 * Scanner Service) reconstructs the actual proximity/AND-NOT relationship
 * from this text — see {@code TermCompilationResult.resolvedPatterns}
 * class Javadoc for the full contract, and
 * {@code src/test/java/.../TokenProximityMatcher.java}-family classes in
 * this repo's test tree for a reference implementation of that downstream
 * logic (this repo does not consume {@code resolvedPatterns} itself — it
 * only produces it).
 *
 * <p><b>The one exception: NEAR/FOLLOWEDBY nested inside {@code OR}</b>
 * <p>A NEAR/FOLLOWEDBY node that is one alternative of a multi-operand
 * {@code Ast.Or} — e.g. {@code "(plain phrase) OR ((EURIBOR FIXING) NEAR{2} TENOR)"}
 * — genuinely cannot be flattened into a flat, boolean-AND'd leaf list
 * without changing what the surrounding {@code OR} means. This case is
 * confirmed real, currently-used functionality (not a hypothetical edge
 * case), so it is deliberately left alone: a multi-operand {@code Or} is
 * still treated as ONE opaque leaf, generated via
 * {@link PatternCodeGenerator#generate}, exactly as before — which means
 * {@code PatternCodeGenerator.generateNear}/{@code generateFollowedBy} (and
 * therefore {@code MultiLanguagePatternBuilder}'s gap-building, including
 * its static clamp and adaptive real-Hyperscan retry) are still reachable,
 * but ONLY via this one residual path. Do not "finish the job" by also
 * flattening through {@code Or} without first designing how a
 * consumer-facing OR-of-AND-groups formula would work — that is a
 * materially larger change than this one (it would require
 * {@code regexPattern} to become a nested structure, not a flat list — see
 * {@code HyperscanCombinationHandler}, whose id-allocation scheme assumes
 * a flat AND-only leaf list).
 *
 * <p><b>{@code AND} is flattened, not left opaque, when it contains nested
 * proximity — and this is lossless</b>
 * <p>Unlike {@code OR}, {@code Ast.And}'s own semantics ("all operands
 * co-occur anywhere in the message, in any order, unbounded distance") is
 * ALREADY exactly equivalent to "these operands' leaves are all
 * independently present somewhere" — the same flat-AND convention every
 * other multi-leaf case in this codebase already uses. So when an
 * {@code And} operand is (or contains) a NEAR/FOLLOWEDBY node, decomposing
 * through the {@code And} loses nothing beyond what decomposing the nested
 * NEAR/FOLLOWEDBY itself already trades away — seee {@link #containsProximity}.
 * A plain {@code And} with NO nested proximity anywhere is left completely
 * untouched (one opaque leaf, via {@code PatternCodeGenerator.generateAnd}'s
 * existing permutation-based single pattern) — this is what keeps ordinary
 * {@code AND} terms fully unaffected by this whole feature.
 */
final class PatternDecomposer {

    private PatternDecomposer() {
    }

    /**
     * @param leaves       independent, individually Hyperscan-compilable PCRE
     *                     fragments, in left-to-right term order — never
     *                     containing any gap fragment
     * @param resolvedText the same subtree rendered with literal
     *                     {@code NEAR{n}}/{@code FOLLOWEDBY{n}}/{@code AND}
     *                     keyword text standing in for what would otherwise be
     *                     a gap — see class Javadoc. For a leaf reached via the
     *                     OR-nested-proximity exception, this is byte-identical
     *                     to that leaf's own entry in {@code leaves}, since both
     *                     come from the exact same {@link PatternCodeGenerator#generate}
     *                     call.
     */
    record Result(List<String> leaves, String resolvedText) {
    }

    /**
     * @param ast a NEAR/FOLLOWEDBY/AND tree (or any AST — a leaf root simply
     *            returns a single-element {@code leaves} list containing
     *            {@code ast}'s generated pattern, with {@code resolvedText}
     *            identical to it)
     * @param ctx shared parse context — mutated with UTF8/UCP flag needs and
     *            warnings exactly as {@link PatternCodeGenerator#generate}
     *            would for the equivalent non-decomposed pattern; each node
     *            is visited exactly once by this single unified pass, so
     *            there is no risk of duplicate flag/warning mutation between
     *            {@code leaves} and {@code resolvedText} — they are built
     *            together, not by two independent walks.
     */
    static Result decompose(Ast ast, ParseContext ctx) {
        return switch (ast) {
            case Ast.Near near -> decomposeProximity(near.left(), near.right(), near.distance(), "NEAR", ctx);
            case Ast.FollowedBy fb -> decomposeProximity(fb.left(), fb.right(), fb.distance(), "FOLLOWEDBY", ctx);

            // A single-operand Or is not something the parser itself ever produces
            // (ExpressionParser.parseOr unwraps a lone alternative directly) — it only
            // arises from TermSyntaxTranslator wrapping an AND NOT term's excluded
            // operand list in an Or even when there is exactly one operand, specifically
            // to keep the generated exclusionRegex string's "(?:...)" wrapping identical
            // to before decomposition existed. Seeing through it here means that single
            // excluded operand's OWN proximity structure (if any) is still visible to
            // decomposition, rather than the Or wrapper being treated as one opaque leaf.
            case Ast.Or or when or.operands().size() == 1 -> decompose(or.operands().getFirst(), ctx);

            // AND is flattened only when it actually contains nested proximity — see
            // class Javadoc. A plain AND (no nested NEAR/FOLLOWEDBY anywhere) falls
            // through to the default arm below, unaffected.
            case Ast.And and when containsProximity(and) -> decomposeAnd(and, ctx);

            case Ast.Not ignored -> throw new IllegalStateException(
                    "unreachable — every Ast.Not is folded into Ast.AndNot (or rejected) by "
                    + "ExpressionParser.parseAnd() before an Ast is ever returned; see Ast.Not Javadoc");

            // Every other case — a multi-operand Or (including one that itself contains
            // nested proximity — see class Javadoc "the one exception"), a plain And with
            // no nested proximity, Word, Phrase, QuotedPhrase — is one opaque leaf,
            // generated exactly as PatternCodeGenerator already would for a non-decomposed
            // term. resolvedText is deliberately the SAME string, not a re-derivation, so
            // it can never drift from what regexPattern/exclusionRegex actually contain.
            default -> {
                String pattern = PatternCodeGenerator.generate(ast, ctx);
                yield new Result(List.of(pattern), pattern);
            }
        };
    }

    /**
     * Decomposes both sides of one NEAR/FOLLOWEDBY node and stitches them
     * together: {@code left}'s leaves unchanged, followed by {@code right}'s
     * leaves — no gap fragment is baked onto either side any more (see class
     * Javadoc). {@code resolvedText} joins the two sides' own resolved text
     * with the literal {@code " KEYWORD{distance} "} text instead.
     */
    private static Result decomposeProximity(Ast left, Ast right, int distance, String keyword, ParseContext ctx) {
        Result leftResult = decompose(left, ctx);
        Result rightResult = decompose(right, ctx);

        List<String> combined = new ArrayList<>(leftResult.leaves().size() + rightResult.leaves().size());
        combined.addAll(leftResult.leaves());
        combined.addAll(rightResult.leaves());

        String resolvedText = leftResult.resolvedText() + " " + keyword + "{" + distance + "} " + rightResult.resolvedText();
        return new Result(combined, resolvedText);
    }

    /**
     * Flattens an {@code And} node known (via {@link #containsProximity}) to
     * contain nested proximity structure: every operand is independently
     * decomposed and their leaves concatenated (AND's own semantics is
     * already flat-presence — see class Javadoc), and {@code resolvedText}
     * joins each operand's own resolved text with the literal {@code " AND "}
     * keyword.
     */
    private static Result decomposeAnd(Ast.And and, ParseContext ctx) {
        List<String> combined = new ArrayList<>();
        StringBuilder resolvedText = new StringBuilder();
        boolean first = true;
        for (Ast operand : and.operands()) {
            Result operandResult = decompose(operand, ctx);
            combined.addAll(operandResult.leaves());
            if (!first) {
                resolvedText.append(" AND ");
            }
            resolvedText.append(operandResult.resolvedText());
            first = false;
        }
        return new Result(combined, resolvedText.toString());
    }

    /**
     * True when {@code ast} contains a {@link Ast.Near}/{@link Ast.FollowedBy}
     * node reachable by recursing only through {@link Ast.And} — {@link Ast.Or}
     * is deliberately NOT recursed into (an {@code Or} is always its own
     * opaque boundary for this check, matching the OR-nested-proximity
     * exception in class Javadoc: whether or not one of an {@code Or}'s
     * alternatives contains proximity has no bearing on whether the
     * ENCLOSING {@code And} should flatten — that {@code Or} stays one
     * opaque leaf regardless).
     */
    private static boolean containsProximity(Ast ast) {
        return switch (ast) {
            case Ast.Near ignored -> true;
            case Ast.FollowedBy ignored -> true;
            case Ast.And and -> and.operands().stream().anyMatch(PatternDecomposer::containsProximity);
            default -> false;
        };
    }
}
