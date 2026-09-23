package com.db.macs3.ecomms.spectre.translator;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a {@code NEAR}/{@code FOLLOWEDBY} tree into independent leaf
 * sub-patterns, and — in the same pass — builds a literal-keyword
 * "resolved" text representation of the same tree (see
 * {@link TermCompilationResult#resolvedPatterns()}).
 *
 * <p><b>{@link #decompose} itself still runs unconditionally for every term
 * containing proximity structure — but its LEAVES are no longer always what
 * ends up in {@code regexPattern}.</b> This class's own job hasn't changed:
 * every NEAR/FOLLOWEDBY node (with one narrow, deliberate exception — see
 * below) always splits into independent, gap-less leaves here, in the same
 * unified pass that builds {@link Result#resolvedText()} — the gap is NEVER
 * compiled into a regex fragment by THIS class, not even as a leaf prefix
 * (an earlier revision baked each node's own gap fragment as a literal
 * prefix onto the following leaf, as a "safe strengthening" over an even
 * earlier revision that discarded the gap entirely — both are gone, by
 * design). What changed is the CALLER: {@code TermSyntaxTranslator#resolveSide}
 * now decides, per side, whether to actually USE these leaves for
 * {@code regexPattern}, or to re-generate the side as ONE self-contained
 * gap-embedded pattern instead (preferred whenever it's safe to compile —
 * see that method's Javadoc for the full two-layer decision). Either way,
 * {@link Result#resolvedText()} is unconditionally what
 * {@code resolvedPatterns} reports, using the term author's raw, un-clamped,
 * un-multiplied distance rendered as literal operator text — {@code
 * " NEAR{n} "} / {@code " FOLLOWEDBY{n} "}. A downstream Java-regex-based
 * consumer (Lexicon Scan Engine / Lexicon Scanner Service) reconstructs the
 * actual proximity/AND-NOT relationship from this text WHENEVER a side
 * actually fell back to decomposed leaves (when it didn't — the common,
 * simple case — Hyperscan itself already enforced the relationship natively,
 * and this text is there for a caller who wants to read it directly anyway)
 * — see {@code TermCompilationResult.resolvedPatterns} class Javadoc for the
 * full contract, and {@code src/test/java/.../TokenProximityMatcher.java}-family
 * classes in this repo's test tree for a reference implementation of that
 * downstream logic (this repo does not consume {@code resolvedPatterns}
 * itself — it only produces it).
 *
 * <p><b>Nesting one proximity operator inside another's RIGHT operand is
 * preserved with explicit parentheses — confirmed-fixed regression</b>
 * <p>{@code "(manipulate OR front run) NEAR{5} ((price OR spread) NEAR{5} stock)"}
 * — a NEAR whose right operand is itself a NEAR — previously rendered
 * {@code resolvedPatterns} as the fully flat
 * {@code "(?:manipulate|front run) NEAR{5} (?:price|spread) NEAR{5} stock"},
 * indistinguishable from the LEFT-nested chain a naive left-to-right reading
 * would reconstruct from that same flat text — silently losing which pair the
 * author actually grouped together, even though {@code regexPattern} itself
 * was already fully correct. Fixed: {@code resolvedPatterns} is now
 * {@code "(?:manipulate|front run) NEAR{5} ((?:price|spread) NEAR{5} stock)"}
 * and the corresponding {@code patternMapping} is {@code "(54&(55&56))"}
 * (not the flat {@code "(54&55&56)"}) — see {@link #decomposeProximity} for
 * exactly which side gets wrapped and why.
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
     * @param leaves          independent, individually Hyperscan-compilable PCRE
     *                        fragments, in left-to-right term order — never
     *                        containing any gap fragment
     * @param resolvedText    the same subtree rendered with literal
     *                        {@code NEAR{n}}/{@code FOLLOWEDBY{n}}/{@code AND}
     *                        keyword text standing in for what would otherwise be
     *                        a gap — see class Javadoc "right-side nesting is
     *                        wrapped in parentheses" for exactly when this string
     *                        gains extra grouping parens beyond a leaf's own text.
     *                        For a leaf reached via the OR-nested-proximity
     *                        exception, this is byte-identical to that leaf's own
     *                        entry in {@code leaves}, since both come from the
     *                        exact same {@link PatternCodeGenerator#generate} call.
     * @param formulaTemplate the same subtree's boolean-AND structure, using
     *                        {@code {i}} placeholders (0-based, indexing into
     *                        THIS Result's own {@code leaves}) in place of each
     *                        leaf and {@code &} in place of every keyword —
     *                        e.g. {@code "{0}&({1}&{2})"}. Mirrors
     *                        {@code resolvedText}'s own grouping exactly (same
     *                        right-side-wraps-in-parens rule, same never-wrap-the-
     *                        left rule), so {@code HyperscanCombinationHandler}
     *                        can later substitute each placeholder with that
     *                        leaf's allocated Hyperscan expression id to build a
     *                        {@code patternMapping}/native-combination formula
     *                        that reflects the term's actual authored structure
     *                        instead of a flat AND-join of every leaf.
     */
    record Result(List<String> leaves, String resolvedText, String formulaTemplate) {
    }

    /**
     * Matches one {@code {i}} leaf-index placeholder in a {@link Result#formulaTemplate()}.
     */
    private static final Pattern FORMULA_PLACEHOLDER = Pattern.compile("\\{(\\d+)}");

    /**
     * Re-indexes every {@code {i}} placeholder in {@code template} by adding
     * {@code offset} — used when splicing a sub-Result's own (locally
     * 0-based) formula template into a larger combined leaf list, exactly the
     * way {@code leaves} lists are concatenated.
     */
    private static String shiftFormulaPlaceholders(String template, int offset) {
        if (offset == 0) {
            return template;
        }
        Matcher matcher = FORMULA_PLACEHOLDER.matcher(template);
        StringBuilder shifted = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(shifted, "{" + (Integer.parseInt(matcher.group(1)) + offset) + "}");
        }
        matcher.appendTail(shifted);
        return shifted.toString();
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
                yield new Result(List.of(pattern), pattern, "{0}");
            }
        };
    }

    /**
     * Decomposes both sides of one NEAR/FOLLOWEDBY node and stitches them
     * together: {@code left}'s leaves unchanged, followed by {@code right}'s
     * leaves — no gap fragment is baked onto either side any more (see class
     * Javadoc). {@code resolvedText} joins the two sides' own resolved text
     * with the literal {@code " KEYWORD{distance} "} text instead.
     *
     * <p><b>Right-side nesting is wrapped in parentheses; left-side nesting
     * never is — this is what lets {@code resolvedPatterns}/{@code patternMapping}
     * retain an explicitly-authored tree shape.</b> A flat, left-to-right
     * rendering of a proximity chain is naturally read as LEFT-associative —
     * that is exactly the shape {@link ExpressionParser}'s own implicit
     * chaining loop already produces with no extra parentheses at all (see
     * its class Javadoc "Chained NEAR/FOLLOWEDBY"), so leaving {@code left}
     * unwrapped here costs nothing: a chain like
     * {@code (A FOLLOWEDBY{4} B) FOLLOWEDBY{4} C} still renders as the flat
     * {@code "A FOLLOWEDBY{4} B FOLLOWEDBY{4} C"}, unchanged from before. But
     * when {@code right} is ITSELF a NEAR/FOLLOWEDBY node — only reachable via
     * an author explicitly grouping it, e.g.
     * {@code "(A) NEAR{5} ((B) NEAR{5} (C))"} — a flat rendering would be
     * silently reinterpreted as the LEFT-nested chain on a naive left-to-right
     * reading, losing the author's actual grouping even though no leaf/gap
     * information was discarded. Wrapping {@code right} in one extra pair of
     * parentheses whenever it is itself proximity-structured preserves this
     * distinction losslessly, for both {@code resolvedText} (a downstream
     * {@code ResolvedPatternMatcher}-style consumer recurses into the
     * parenthesised group as its own nested chain) and {@code formulaTemplate}
     * (the corresponding sub-formula is parenthesised the same way).
     */
    private static Result decomposeProximity(Ast left, Ast right, int distance, String keyword, ParseContext ctx) {
        Result leftResult = decompose(left, ctx);
        Result rightResult = decompose(right, ctx);

        List<String> combined = new ArrayList<>(leftResult.leaves().size() + rightResult.leaves().size());
        combined.addAll(leftResult.leaves());
        combined.addAll(rightResult.leaves());

        boolean rightIsNestedProximity = right instanceof Ast.Near || right instanceof Ast.FollowedBy;

        String rightResolvedText = rightIsNestedProximity
                ? "(" + rightResult.resolvedText() + ")" : rightResult.resolvedText();
        String resolvedText = leftResult.resolvedText() + " " + keyword + "{" + distance + "} " + rightResolvedText;

        String shiftedRightFormula = shiftFormulaPlaceholders(rightResult.formulaTemplate(), leftResult.leaves().size());
        String rightFormula = rightIsNestedProximity ? "(" + shiftedRightFormula + ")" : shiftedRightFormula;
        String formulaTemplate = leftResult.formulaTemplate() + "&" + rightFormula;

        return new Result(combined, resolvedText, formulaTemplate);
    }

    /**
     * Flattens an {@code And} node known (via {@link #containsProximity}) to
     * contain nested proximity structure: every operand is independently
     * decomposed and their leaves concatenated (AND's own semantics is
     * already flat-presence — see class Javadoc), and {@code resolvedText}/
     * {@code formulaTemplate} join each operand's own rendering with the
     * literal {@code " AND "} keyword / {@code "&"} respectively — never
     * parenthesised, since AND is commutative/associative and a flat AND-join
     * loses nothing (unlike NEAR/FOLLOWEDBY's right-side wrapping — see
     * {@link #decomposeProximity}).
     */
    private static Result decomposeAnd(Ast.And and, ParseContext ctx) {
        List<String> combined = new ArrayList<>();
        StringBuilder resolvedText = new StringBuilder();
        StringBuilder formulaTemplate = new StringBuilder();
        boolean first = true;
        for (Ast operand : and.operands()) {
            Result operandResult = decompose(operand, ctx);
            formulaTemplate.append(first ? "" : "&")
                    .append(shiftFormulaPlaceholders(operandResult.formulaTemplate(), combined.size()));
            combined.addAll(operandResult.leaves());
            if (!first) {
                resolvedText.append(" AND ");
            }
            resolvedText.append(operandResult.resolvedText());
            first = false;
        }
        return new Result(combined, resolvedText.toString(), formulaTemplate.toString());
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
