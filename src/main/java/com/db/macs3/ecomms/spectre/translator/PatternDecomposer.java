package com.db.macs3.ecomms.spectre.translator;

import java.util.ArrayList;
import java.util.List;

/**
 * Breaks an over-budget (see {@link PatternComplexityAnalyzer}) NEAR/FOLLOWEDBY
 * tree into independent leaf sub-patterns, for compilation as separate
 * Hyperscan QUIET expressions combined by a native COMBINATION expression
 * (for {@code /compile/bundle}) or as a caller-combined pattern list (for
 * {@code /compile} and {@code /compile/csv}) — see {@link TermSyntaxTranslator}
 * class Javadoc for the full flow and {@code LexiconCompileBundleService} for
 * how the combination expression itself gets built once real Hyperscan
 * expression ids are assigned.
 *
 * <h2>What "leaf" means here</h2>
 * <p>Given a NEAR/FOLLOWEDBY tree, a leaf is a maximal subtree that is NOT
 * itself a NEAR/FOLLOWEDBY node — i.e. an {@link Ast.Or}, {@link Ast.And},
 * {@link Ast.Word}, {@link Ast.Phrase}, or {@link Ast.QuotedPhrase} found by
 * walking down through every NEAR/FOLLOWEDBY node's children. For
 * {@code (A FOLLOWEDBY{4} B) FOLLOWEDBY{4} C}, the three leaves are exactly
 * A, B, and C — the two FOLLOWEDBY nodes themselves contribute no leaf of
 * their own; only what they connect.
 *
 * <h2>This discards the tree's proximity structure entirely</h2>
 * <p>The leaves say nothing about order or distance — that information lived
 * entirely in the discarded NEAR/FOLLOWEDBY nodes and their {@code distance}
 * values. A term originally requiring "A, then within 4 words B, then within
 * 4 more words C" becomes, once decomposed, "A and B and C all appear
 * somewhere in the message" — see {@link TranslationResult} class Javadoc
 * for why this trade-off exists and how it is surfaced to callers.
 */
final class PatternDecomposer {

    private PatternDecomposer() {}

    /**
     * @param ast a NEAR/FOLLOWEDBY tree (or any AST — a non-proximity root
     *            simply returns a single-element list containing {@code ast} itself)
     * @return the leaf subtrees, in left-to-right order as they appear in the
     *         original term text
     */
    static List<Ast> collectLeaves(Ast ast) {
        List<Ast> leaves = new ArrayList<>();
        collectLeavesInto(ast, leaves);
        return leaves;
    }

    private static void collectLeavesInto(Ast ast, List<Ast> leavesOut) {
        switch (ast) {
            case Ast.Near near -> {
                collectLeavesInto(near.left(), leavesOut);
                collectLeavesInto(near.right(), leavesOut);
            }
            case Ast.FollowedBy fb -> {
                collectLeavesInto(fb.left(), leavesOut);
                collectLeavesInto(fb.right(), leavesOut);
            }
            // A single-operand Or is not something the parser itself ever produces
            // (ExpressionParser.parseOr unwraps a lone alternative directly) — it only
            // arises from TermSyntaxTranslator wrapping an AND NOT term's excluded
            // operand list in an Or even when there is exactly one operand, specifically
            // to keep the generated exclusionPattern string's "(?:...)" wrapping identical
            // to before decomposition existed. Seeing through it here means that single
            // excluded operand's OWN proximity structure (if any) is still visible to
            // decomposition, rather than the Or wrapper being treated as one opaque leaf.
            case Ast.Or or when or.operands().size() == 1 -> collectLeavesInto(or.operands().get(0), leavesOut);

            default -> leavesOut.add(ast);
        }
    }
}
