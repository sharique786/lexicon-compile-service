package com.db.macs3.ecomms.spectre.translator;

import com.db.macs3.ecomms.spectre.model.ScriptType;
import com.db.macs3.ecomms.spectre.util.ScriptDetector;

/**
 * Estimates whether an {@link Ast} is likely to compile to a pattern Hyperscan rejects with
 * "Pattern is too large". {@code TermSyntaxTranslator#resolveSide} uses it as a cheap
 * pre-check before it generates and trial-compiles a side as one gap-embedded pattern: a
 * side predicted over budget goes straight to {@link PatternDecomposer}'s leaves, saving a
 * compile that would almost certainly fail. It only measures ({@link #estimate},
 * {@link #isOverBudget}); it never rejects a term. Real Hyperscan validation still has the
 * final say for anything the heuristic lets through.
 *
 * <p><b>What drives Hyperscan's limit.</b> "Pattern is too large" follows compiled automaton
 * STATE COUNT, not string length (a 190-character pattern can fail while a much longer,
 * simpler one compiles). A bounded gap such as {@code (?:\s+\S+){0,4}} makes the automaton
 * track how many gap words were consumed — cheap alone. When a proximity operand is itself a
 * proximity node, two gap counters are tracked at once, a PRODUCT of state spaces. Nesting
 * depth therefore compounds multiplicatively, while OR-branch width at one level does not.
 *
 * <p><b>The score</b> ({@link #estimate}): OR adds its operands, AND and proximity multiply
 * them. NEAR is doubled (both directions are generated). A wildcard word weighs
 * {@link #WILDCARD_WEIGHT}, a plain one {@link #PLAIN_WEIGHT}. A proximity node whose
 * operand is itself proximity is multiplied by that operand's {@code distance + 1}. Under a
 * character-based script the node is also multiplied by its effective character gap
 * ({@link MultiLanguagePatternBuilder#effectiveGapWidth}), because a bounded
 * {@code [\s\S]{0,N}} repeat under UTF8+UCP is far costlier than a word gap; word-based
 * scripts get factor 1.
 *
 * <p><b>Calibration.</b> {@link #COMPLEXITY_BUDGET} is set between two observed real Hyperscan
 * outcomes: a single NEAR over an 18- and an 8-alternative OR group compiles (score 480), and
 * two nested FOLLOWEDBY over 4-alternative groups is rejected (score 1470 with the nesting
 * penalty). Two points do not prove the formula generalises, so the budget stays a single
 * named constant that can be re-tuned.
 */
final class PatternComplexityAnalyzer {

    /**
     * Score above which an AST is considered over budget; see the class Javadoc for how it was
     * chosen (comfortably above the known-good 480, comfortably below the known-bad 1470).
     */
    static final int COMPLEXITY_BUDGET = 700;

    /**
     * Multiplier for NEAR: it generates both A-then-B and B-then-A, doubling the automaton
     * relative to a single-direction FOLLOWEDBY over the same operands.
     */
    private static final int NEAR_DIRECTIONALITY_FACTOR = 2;

    /**
     * Weight of a wildcard-containing word/phrase: the unbounded {@code \S*} compounds with any
     * bounded repetition or alternation around it.
     */
    private static final int WILDCARD_WEIGHT = 2;
    private static final int PLAIN_WEIGHT = 1;

    private PatternComplexityAnalyzer() {
    }

    /**
     * @return true when {@code ast}'s estimated complexity exceeds {@link #COMPLEXITY_BUDGET}
     *         (an arithmetic overflow while scoring also counts as over budget)
     */
    static boolean isOverBudget(Ast ast) {
        return estimateSafely(ast) > COMPLEXITY_BUDGET;
    }

    /**
     * @return the estimated complexity score, package-visible so callers can report the number
     */
    static int estimate(Ast ast) {
        return estimateSafely(ast);
    }

    /**
     * Runs {@link #estimateRaw}, treating integer overflow as unambiguously over budget: a term
     * complex enough to overflow a 32-bit score is over budget whatever the exact number.
     */
    private static int estimateSafely(Ast ast) {
        try {
            return estimateRaw(ast);
        } catch (ArithmeticException overflow) {
            return Integer.MAX_VALUE;
        }
    }

    // ── Estimation ────────────────────────────────────────────────────────────

    private static int estimateRaw(Ast ast) {
        return switch (ast) {
            case Ast.Or or -> or.operands().stream().mapToInt(PatternComplexityAnalyzer::estimateRaw).sum();

            case Ast.And and -> and.operands().stream()
                    .mapToInt(PatternComplexityAnalyzer::estimateRaw)
                    .reduce(1, Math::multiplyExact); // permutation count itself is already capped elsewhere

            case Ast.AndNot andNot -> estimateRaw(andNot.required()); // excluded side checked separately by the caller

            case Ast.Near near -> {
                int base = Math.multiplyExact(estimateRaw(near.left()), estimateRaw(near.right()));
                int directional = Math.multiplyExact(base, NEAR_DIRECTIONALITY_FACTOR);
                int nested = Math.multiplyExact(directional, nestingPenalty(near.left(), near.right()));
                yield Math.multiplyExact(nested, charGapPenalty(near.left(), near.right(), near.distance()));
            }

            case Ast.FollowedBy fb -> {
                int base = Math.multiplyExact(estimateRaw(fb.left()), estimateRaw(fb.right()));
                int nested = Math.multiplyExact(base, nestingPenalty(fb.left(), fb.right()));
                yield Math.multiplyExact(nested, charGapPenalty(fb.left(), fb.right(), fb.distance()));
            }

            case Ast.Not ignored -> throw new IllegalStateException(
                    "unreachable — every Ast.Not is folded into Ast.AndNot (or rejected) by "
                    + "ExpressionParser.parseAnd() before an Ast is ever returned; see Ast.Not Javadoc");

            case Ast.Word w -> containsWildcard(w.text()) ? WILDCARD_WEIGHT : PLAIN_WEIGHT;

            case Ast.Phrase p -> p.words().stream().anyMatch(PatternComplexityAnalyzer::containsWildcard)
                    ? WILDCARD_WEIGHT : PLAIN_WEIGHT;

            case Ast.QuotedPhrase q -> PLAIN_WEIGHT;
        };
    }

    /**
     * Extra multiplier for a proximity node whose operand(s) are themselves proximity nodes:
     * {@code nestingFactor(left) × nestingFactor(right)}, where the factor is 1 for a
     * non-proximity operand and {@code distance + 1} for a nested one.
     */
    private static int nestingPenalty(Ast left, Ast right) {
        return Math.multiplyExact(nestingFactor(left), nestingFactor(right));
    }

    private static int nestingFactor(Ast operand) {
        return switch (operand) {
            case Ast.Near near -> near.distance() + 1;
            case Ast.FollowedBy fb -> fb.distance() + 1;
            default -> 1;
        };
    }

    private static boolean containsWildcard(String word) {
        return word.indexOf('*') >= 0;
    }

    /**
     * Extra multiplier for a proximity node's own gap cost under a character-based script
     * (CJK, Hangul, Thai, …): the clamped effective gap width, at least 1. Applied at every
     * proximity node, nested or not, and stacks with {@link #nestingPenalty}. Neutral (1) for
     * word-based scripts, for any operand text.
     */
    private static int charGapPenalty(Ast left, Ast right, int distance) {
        ScriptType script = ScriptDetector.detectCombined(collectText(left), collectText(right));
        if (!script.isCharBased()) {
            return 1;
        }
        return Math.max(1, MultiLanguagePatternBuilder.effectiveGapWidth(script, distance));
    }

    /**
     * Collects a subtree's raw lexicon text, enough for {@link ScriptDetector#detectCombined} to
     * classify its script without running code generation. {@link ScriptDetector} ignores ASCII
     * punctuation and wildcard characters, so raw text is safe input.
     */
    private static String collectText(Ast ast) {
        return switch (ast) {
            case Ast.Or or -> or.operands().stream().map(PatternComplexityAnalyzer::collectText)
                    .reduce((textSoFar, nextText) -> textSoFar + " " + nextText).orElse("");
            case Ast.And and -> and.operands().stream().map(PatternComplexityAnalyzer::collectText)
                    .reduce((textSoFar, nextText) -> textSoFar + " " + nextText).orElse("");
            case Ast.AndNot andNot -> collectText(andNot.required());
            case Ast.Near near -> collectText(near.left()) + " " + collectText(near.right());
            case Ast.FollowedBy fb -> collectText(fb.left()) + " " + collectText(fb.right());
            case Ast.Not ignored -> throw new IllegalStateException(
                    "unreachable — every Ast.Not is folded into Ast.AndNot (or rejected) by "
                    + "ExpressionParser.parseAnd() before an Ast is ever returned; see Ast.Not Javadoc");
            case Ast.Word w -> w.text();
            case Ast.Phrase p -> String.join(" ", p.words());
            case Ast.QuotedPhrase q -> q.text();
        };
    }
}
