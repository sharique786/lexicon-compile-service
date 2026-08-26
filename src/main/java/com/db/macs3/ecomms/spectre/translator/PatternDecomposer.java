package com.db.macs3.ecomms.spectre.translator;

import com.db.macs3.ecomms.spectre.model.ScriptType;
import com.db.macs3.ecomms.spectre.util.ScriptDetector;

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
 * <p><b>What "leaf" means here</b>
 * <p>Given a NEAR/FOLLOWEDBY tree, a leaf is a maximal subtree that is NOT
 * itself a NEAR/FOLLOWEDBY node — i.e. an {@link Ast.Or}, {@link Ast.And},
 * {@link Ast.Word}, {@link Ast.Phrase}, or {@link Ast.QuotedPhrase} found by
 * walking down through every NEAR/FOLLOWEDBY node's children. For
 * {@code (A FOLLOWEDBY{4} B) FOLLOWEDBY{4} C}, the three leaves are exactly
 * A, B, and C — the two FOLLOWEDBY nodes themselves contribute no leaf of
 * their own; only what they connect.
 *
 * <p><b>The gap is preserved on the leaf that follows it — confirmed bug fix</b>
 * <p>An earlier revision of this class ({@code collectLeaves}) discarded
 * every NEAR/FOLLOWEDBY node's gap entirely — {@code distance} was thrown
 * away along with the node itself, and each leaf was code-generated in
 * total isolation. That is a REAL regression from what the single,
 * non-decomposed pattern for the same term would have expressed: given
 * {@code (A FOLLOWEDBY{4} B) FOLLOWEDBY{4} C}, the non-decomposed pattern is
 * exactly {@code A<gap4>B<gap4>C} — one literal concatenated string. Simply
 * dropping both {@code <gap4>} fragments and emitting {@code A}, {@code B},
 * {@code C} as three totally independent patterns is a bigger precision loss
 * than necessary: it allows, for instance, {@code B} to match as the very
 * FIRST token of the whole message, something the original term's structure
 * never allowed.
 *
 * <p>{@link #decompose} fixes this: every non-first leaf that resulted from
 * splitting a NEAR/FOLLOWEDBY node is prefixed with that node's own gap
 * fragment (the exact {@code (?:\s+\S+){0,n}\s+} / {@code [\s\S]{0,N}}
 * fragment {@link MultiLanguagePatternBuilder} would have used at that same
 * position in the non-decomposed pattern — word-based or character-based,
 * chosen the same way, from the same script detection over the same
 * operand pair). For {@code (A FOLLOWEDBY{4} B) FOLLOWEDBY{4} C}, decompose
 * now returns exactly: {@code [A, <gap4(A,B)>B, <gap4(AB,C)>C]}.
 *
 * <p><b>This is still NOT the original proximity constraint — read carefully</b>
 * <p>The prefix is a LITERAL gap fragment baked into one leaf's own pattern
 * text, not a cross-expression constraint — Hyperscan has no mechanism to
 * make one independently-scanned expression's match position depend on
 * another's. Each leaf still reports its own match independently, and the
 * decomposed leaves are still combined with pure boolean AND ("all of these
 * appear somewhere in the message") — see {@link TermSyntaxTranslator} class
 * Javadoc and {@code TermCompilationResult} class Javadoc. Concretely: the
 * gap-prefixed leaf for B only requires SOME up-to-{@code n} words/chars of
 * ANY content to precede B WHEREVER B itself occurs in the message — it does
 * NOT require that content to be A's own match. The true guarantee this
 * restores is narrower but real: a leaf that originally sat on the right of
 * a NEAR/FOLLOWEDBY can no longer match with literally nothing before it
 * (e.g. as the message's first token), which the fully-discarded-gap version
 * incorrectly allowed. {@code warnings} still always carries an explicit
 * entry whenever decomposition applies, precisely because the true
 * order/distance relationship BETWEEN leaves remains lost.
 *
 * <p><b>NEAR is bidirectional; the prefix approximates one direction anyway</b>
 * <p>{@link Ast.Near} allows either operand to appear first; a single
 * decomposed leaf cannot faithfully carry "preceded by up to n OR followed
 * by up to n". {@link #decompose} applies the same left-to-right,
 * gap-on-the-right-operand treatment it uses for {@link Ast.FollowedBy} —
 * strictly weaker than NEAR's true bidirectional guarantee (never rejects a
 * message NEAR would have matched because of this approximation; it can
 * only, in the same narrow sense as above, additionally require the
 * right-hand leaf not be the message's literal first token), so this is a
 * safe strengthening in the same spirit as the FOLLOWEDBY case, not a
 * silent semantic change in the unsafe direction.
 */
final class PatternDecomposer {

    private PatternDecomposer() {
    }

    /**
     * @param ast a NEAR/FOLLOWEDBY tree (or any AST — a non-proximity root
     *            simply returns a single-element list containing {@code ast}'s
     *            generated pattern)
     * @param ctx shared parse context — mutated with UTF8/UCP flag needs
     *            exactly as {@link PatternCodeGenerator#generate} would for
     *            the equivalent non-decomposed pattern, both for each leaf's
     *            own content and for each gap's script detection
     * @return the leaf patterns, in left-to-right order as they appear in the
     * original term text, each one already a complete, independently
     * Hyperscan-compilable PCRE fragment — every leaf after the first
     * carries its enclosing NEAR/FOLLOWEDBY node's own gap fragment as a
     * literal prefix (see class Javadoc)
     */
    static List<String> decompose(Ast ast, ParseContext ctx) {
        return switch (ast) {
            case Ast.Near near -> decomposeProximity(near.left(), near.right(), near.distance(), ctx);
            case Ast.FollowedBy fb -> decomposeProximity(fb.left(), fb.right(), fb.distance(), ctx);

            // A single-operand Or is not something the parser itself ever produces
            // (ExpressionParser.parseOr unwraps a lone alternative directly) — it only
            // arises from TermSyntaxTranslator wrapping an AND NOT term's excluded
            // operand list in an Or even when there is exactly one operand, specifically
            // to keep the generated exclusionPattern string's "(?:...)" wrapping identical
            // to before decomposition existed. Seeing through it here means that single
            // excluded operand's OWN proximity structure (if any) is still visible to
            // decomposition, rather than the Or wrapper being treated as one opaque leaf.
            case Ast.Or or when or.operands().size() == 1 -> decompose(or.operands().getFirst(), ctx);

            default -> List.of(PatternCodeGenerator.generate(ast, ctx));
        };
    }

    /**
     * Decomposes both sides of one NEAR/FOLLOWEDBY node and stitches them
     * together: {@code left}'s leaves unchanged, followed by {@code right}'s
     * leaves with this node's own gap fragment prefixed onto only the FIRST
     * of them — that first right-hand leaf is exactly the leaf that sat
     * immediately after this gap in the non-decomposed pattern text; any
     * further leaves from a right subtree that was itself a further nested
     * NEAR/FOLLOWEDBY already carry their OWN gap prefix from their own
     * recursive {@link #decompose} call and must not be touched again here.
     */
    private static List<String> decomposeProximity(Ast left, Ast right, int distance, ParseContext ctx) {
        List<String> leftLeaves = decompose(left, ctx);
        List<String> rightLeaves = new ArrayList<>(decompose(right, ctx));

        String firstRightLeaf = rightLeaves.get(0);
        String gap = gapBetween(left, right, distance, ctx, firstRightLeaf);
        rightLeaves.set(0, gap + firstRightLeaf);

        List<String> combined = new ArrayList<>(leftLeaves.size() + rightLeaves.size());
        combined.addAll(leftLeaves);
        combined.addAll(rightLeaves);
        return combined;
    }

    /**
     * Computes exactly the gap fragment {@link PatternCodeGenerator}'s
     * non-decomposed {@code generateNear}/{@code generateFollowedBy} would
     * have used for this same {@code left}/{@code right} pair and
     * {@code distance} — same script detection, over the same fully
     * generated (non-decomposed) text of both operands, so a term's gap
     * choice (word-based vs. character-based — see {@link ScriptDetector}
     * class Javadoc) is identical whether or not that term ends up
     * decomposed.
     *
     * <p>{@code left}/{@code right} are re-generated here (in full, ignoring
     * any decomposition within them) purely as script-detection input — this
     * mirrors exactly what the non-decomposed code path itself passes to
     * {@link ScriptDetector#detectCombined}, and is cheap (string building
     * only, no Hyperscan calls).
     *
     * <p>This also supplies {@link MultiLanguagePatternBuilder#buildGap} a
     * trial-pattern builder for the REAL decomposed-leaf shape —
     * {@code gap + rightLeafText}, the exact fragment that ends up as this
     * leaf's own independently-compiled Hyperscan expression — so a gap
     * width that is safe in the generic calibration but not for this
     * specific leaf (e.g. a leaf itself containing a wide OR group) gets
     * adaptively narrowed the same way the non-decomposed NEAR/FOLLOWEDBY
     * path already does. The gap-fragment format the trial builder emits
     * must match {@code script}'s own choice ({@code [\s\S]{0,n}} for a
     * character-based script, {@code (?:\s+\S+){0,n}\s+} for a word-based
     * one) — see {@link MultiLanguagePatternBuilder#charBasedGap(ScriptType, int, java.util.function.IntFunction)}
     * and {@link MultiLanguagePatternBuilder#wordBasedGap(int, java.util.function.IntFunction)}.
     *
     * @param rightLeafText the already-generated pattern of the first right-hand
     *                      leaf this gap will be prefixed onto (see {@link #decomposeProximity})
     */
    private static String gapBetween(Ast left, Ast right, int distance, ParseContext ctx, String rightLeafText) {
        String leftText = PatternCodeGenerator.generate(left, ctx);
        String rightText = PatternCodeGenerator.generate(right, ctx);
        ScriptType script = ScriptDetector.detectCombined(leftText, rightText);
        if ((script.recommendedHsFlags() & ParseContext.HS_FLAG_UTF8) != 0) {
            ctx.setNeedsUtf8();
        }
        java.util.function.IntFunction<String> trial = script.isCharBased()
                ? n -> "[\\s\\S]{0,%d}".formatted(n) + rightLeafText
                : n -> "(?:\\s+\\S+){0,%d}\\s+".formatted(n) + rightLeafText;
        MultiLanguagePatternBuilder.GapResult gr = MultiLanguagePatternBuilder.buildGap(script, distance, trial);
        ctx.addWarning(gr.warning());
        return gr.pattern();
    }
}
