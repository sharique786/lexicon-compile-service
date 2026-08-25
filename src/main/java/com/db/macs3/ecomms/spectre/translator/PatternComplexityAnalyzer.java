package com.db.macs3.ecomms.spectre.translator;

/**
 * Estimates whether an {@link Ast} is likely to produce a pattern Hyperscan
 * rejects with "Pattern is too large", and rejects it EARLY — before
 * {@link PatternCodeGenerator} even runs — with a specific, actionable error
 * instead of letting Hyperscan fail opaquely at compile time.
 *
 * <p><b>Why string length isn't the right signal</b>
 * <p>The reported failure case compiles to a 190-character pattern — nowhere
 * near any raw length limit. Hyperscan's "Pattern is too large" is a
 * documented consequence of compiled AUTOMATON STATE COUNT, not string
 * length: Intel's own issue tracker shows a 32,000-repeat bounded quantifier
 * on a simple pattern compiling fine, while a 73-repeat bounded quantifier
 * on a pattern with additional structure fails.
 *
 * <p><b>Revision history: nesting depth, not branch width, is the primary driver</b>
 * <p>The first version of this analyzer scored pure OR-branch width and
 * wildcard presence, multiplying operand scores together at each NEAR/FOLLOWEDBY.
 * Real Hyperscan testing falsified that model directly: a term with a single
 * NEAR over an 18-alternative and an 8-alternative OR group (no nesting)
 * scored 480 under that model and compiled successfully; a term with two
 * NESTED FOLLOWEDBY operators over much narrower 4-alternative OR groups
 * scored only 294 and was REJECTED by real Hyperscan with "Pattern is too
 * large" — the model had the ordering backwards.
 *
 * <p>The reason, reasoned from how bounded repetition compiles: a bounded
 * gap like {@code (?:\s+\S+){0,4}} requires the automaton to track "how many
 * gap-words have been consumed so far" as part of its state (a handful of
 * states — 0 through 4 — is cheap on its own, which is why the wide,
 * single-level NEAR case above compiled fine). But when one proximity
 * operator's operand is ITSELF a proximity operator, the automaton must
 * track TWO independent gap-counters SIMULTANEOUSLY for ambiguous partial
 * matches — the outer gap's count AND the inner gap's count — and that is a
 * PRODUCT of state spaces, not a sum. Nesting depth compounds multiplicatively;
 * OR-branch width at a single level does not compound the same way.
 *
 * <p>{@link #estimate} now applies an explicit nesting penalty: a NEAR/FOLLOWEDBY
 * whose operand is itself a NEAR/FOLLOWEDBY multiplies the whole expression's
 * score by {@code (nestedDistance + 1)} — the nested gap's own bound plus
 * one, standing in for the number of additional simultaneous counter-states
 * that nesting introduces. A single-level proximity operator (either operand
 * a plain OR/word/phrase, never another proximity node) gets no such penalty,
 * matching the empirical result that width alone did not cause failure.
 *
 * <p><b>This is still a heuristic, not a guarantee</b>
 * <p>It is now calibrated against two known real Hyperscan outcomes (one
 * PASS at raw complexity 480, one real FAILURE at raw complexity 294 that
 * becomes 1470 once the nesting penalty is applied — see
 * {@code PatternComplexityAnalyzerTest}), not just reasoned from first
 * principles. Two data points bound the budget but do not prove the formula
 * generalizes to arbitrary structures; {@link #COMPLEXITY_BUDGET} remains a
 * single named constant specifically so it can be re-tuned as more real
 * Hyperscan compilation results become available.
 *
 * <p><b>Over-budget no longer means rejection — it means decomposition</b>
 * <p>An earlier revision of this class threw {@link TranslationException}
 * directly when {@link #COMPLEXITY_BUDGET} was exceeded. This class no
 * longer decides what happens on an over-budget result — it only measures
 * complexity ({@link #estimate}) and answers whether a given AST is over
 * budget ({@link #isOverBudget}). {@link TermSyntaxTranslator} now responds
 * to an over-budget result by attempting to DECOMPOSE the offending side
 * into independent leaf patterns (see {@code PatternDecomposer}) rather than
 * rejecting the term outright — see {@link TermSyntaxTranslator} class
 * Javadoc for the full flow, and for the real precision trade-off
 * decomposition carries (it discards NEAR/FOLLOWEDBY's distance and order
 * constraints). Rejection is now reserved for the narrower case where even
 * decomposition cannot help — a single leaf that is itself over budget on
 * its own, with no proximity structure left to decompose.
 */
final class PatternComplexityAnalyzer {

    /**
     * Complexity budget — see class Javadoc. Comfortably above the known
     * real PASS case (480, single-level wide NEAR) and comfortably below the
     * known real FAILURE case (1470 with the nesting penalty applied,
     * two-level nested FOLLOWEDBY). An AST whose estimated score exceeds
     * this is over budget — see {@link #isOverBudget}.
     */
    static final int COMPLEXITY_BUDGET = 700;

    /**
     * Multiplier applied to a NEAR operand's score — NEAR generates both
     * A-then-B and B-then-A, doubling automaton complexity relative to the
     * same operands under a single-direction FOLLOWEDBY.
     */
    private static final int NEAR_DIRECTIONALITY_FACTOR = 2;

    /**
     * Extra weight for a wildcard-containing word/phrase — {@code \S*}'s own
     * unbounded internal branching compounds with any surrounding bounded
     * repetition or alternation it sits inside.
     */
    private static final int WILDCARD_WEIGHT = 2;
    private static final int PLAIN_WEIGHT = 1;

    private PatternComplexityAnalyzer() {
    }

    /**
     * @return true when {@code ast}'s estimated complexity exceeds
     * {@link #COMPLEXITY_BUDGET} — the caller should attempt
     * decomposition (see {@code PatternDecomposer}) rather than
     * code-generating {@code ast} as a single pattern.
     */
    static boolean isOverBudget(Ast ast) {
        return estimateSafely(ast) > COMPLEXITY_BUDGET;
    }

    /**
     * @return the estimated complexity score — exposed (package-visible,
     * not just via {@link #isOverBudget}) so callers building an
     * error or warning message can report the actual number.
     */
    static int estimate(Ast ast) {
        return estimateSafely(ast);
    }

    /**
     * Wraps the real {@link #estimateRaw} to treat integer overflow (an
     * extremely deep or wide nested structure) as unambiguously over-budget
     * rather than letting {@link ArithmeticException} propagate — a term
     * complex enough to overflow a 32-bit score is complex enough to treat
     * as over budget regardless of the exact number.
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
                yield Math.multiplyExact(directional, nestingPenalty(near.left(), near.right()));
            }

            case Ast.FollowedBy fb -> {
                int base = Math.multiplyExact(estimateRaw(fb.left()), estimateRaw(fb.right()));
                yield Math.multiplyExact(base, nestingPenalty(fb.left(), fb.right()));
            }

            case Ast.Word w -> containsWildcard(w.text()) ? WILDCARD_WEIGHT : PLAIN_WEIGHT;

            case Ast.Phrase p -> p.words().stream().anyMatch(PatternComplexityAnalyzer::containsWildcard)
                    ? WILDCARD_WEIGHT : PLAIN_WEIGHT;

            case Ast.QuotedPhrase q -> PLAIN_WEIGHT;
        };
    }

    /**
     * The extra multiplicative penalty for a proximity operator whose
     * operand(s) are THEMSELVES proximity operators — see class Javadoc.
     * {@code (nestingFactor(left) × nestingFactor(right))}: neutral (1) for
     * a non-proximity operand, {@code distance+1} for a nested one — so a
     * proximity node with NEITHER operand nested gets no penalty at all,
     * matching the empirical single-level-NEAR PASS case exactly.
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
}
