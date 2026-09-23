package com.db.macs3.ecomms.spectre.hyperscan;

import com.db.macs3.ecomms.spectre.model.TermCompilationResult;
import com.gliwka.hyperscan.wrapper.Expression;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Builds the Hyperscan {@link Expression}(s) one PASS term contributes to the {@code /compile/bundle}
 * combined database, and decides how their ids are assigned. Which of three shapes a term gets is
 * decided only by {@link TermCompilationResult#requiresExclusionCheck()} and
 * {@code regexPattern.size()}:
 * <table border="1">
 *   <caption>Expression shapes</caption>
 *   <tr><th>Term</th><th>Expressions</th><th>Reportable id</th></tr>
 *   <tr><td>Non-AND-NOT, one pattern</td>
 *       <td>one plain expression ({@link HyperscanCompiler#toExpressionFlags})</td>
 *       <td>{@code hyperscanExpressionId} = the term number</td></tr>
 *   <tr><td>Non-AND-NOT, several leaves (fallback)</td>
 *       <td>one QUIET expression per leaf ({@link HyperscanCompiler#toSubExpressionFlags}) plus one
 *           native COMBINATION formula over them</td>
 *       <td>{@code hyperscanExpressionId} = the term number (the combination's id);
 *           {@code patternMapping} mirrors the formula</td></tr>
 *   <tr><td>AND NOT (any leaf count on either side)</td>
 *       <td>one plain expression per required and excluded pattern
 *           ({@link HyperscanCompiler#toAndNotExpressionFlags}); no combination</td>
 *       <td>none — {@code requiredExpressionIds} / {@code excludedExpressionIds} instead;
 *           {@code patternMapping} holds the formula</td></tr>
 * </table>
 *
 * <p><b>Why AND NOT never uses native COMBINATION.</b> Hyperscan evaluates a combination eagerly and
 * progressively ("raises matches at every offset where one of its sub-expressions matches and the
 * logical value of the whole expression is true"), not once after the scan. Only PURELY negative
 * combinations are deferred to end of data. {@code R&!E} also needs the positive {@code R}, so as soon
 * as {@code R} matches while {@code E} simply has not been reached yet, {@code !E} reads true and the
 * combination fires before {@code E} could appear later in the same text — a false positive. A
 * positive-only {@code R1&R2&...} formula has no such ambiguity and stays on COMBINATION.
 *
 * <p>Instead every AND NOT pattern reports individually, and the caller evaluates the condition after
 * the whole scan, from the complete set of matched ids: matched iff every required id was found and NOT
 * every excluded id was found (an excluded side with several entries is excluded only when all of
 * them are present). A consequence is that an AND NOT term is not resolvable from the {@code .hdb}
 * alone — the JSON's {@code requiredExpressionIds}/{@code excludedExpressionIds}/{@code patternMapping}
 * are required.
 *
 * <p><b>Ids.</b> A non-AND-NOT term's reportable id is always its own term number. Every other
 * expression (leaves, AND NOT patterns) takes an id from an auxiliary range starting at
 * {@code max(term number) + 1} ({@link #computeIdOffset}), so it never collides with a term number.
 *
 * <p><b>Flag constraint.</b> A {@code COMBINATION} expression may carry only {@code QUIET}/{@code SINGLEMATCH};
 * {@link HyperscanCompiler#toCombinationExpressionFlags} returns just {@code COMBINATION}.
 */
@Component
public class HyperscanCombinationHandler {

    private final HyperscanCompiler compiler;

    public HyperscanCombinationHandler(HyperscanCompiler compiler) {
        this.compiler = compiler;
    }

    /**
     * The first auxiliary id: {@code max(termNumbers) + 1}, so auxiliary ids (decomposition leaves and every
     * AND NOT pattern) can never overlap a real term number however sparse or large those numbers are.
     */
    public int computeIdOffset(Collection<Integer> termNumbers) {
        return termNumbers.stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
    }

    /**
     * Hands out sequentially increasing auxiliary ids within one bundle request, starting at
     * {@link #computeIdOffset}; each is distinct from every term number and every id already issued.
     */
    public static final class HyperscanIdAllocator {
        private int nextId;

        public HyperscanIdAllocator(int startId) {
            this.nextId = startId;
        }

        public int allocate() {
            return nextId++;
        }
    }

    /**
     * How one term's expression id(s) were assigned. Exactly one of {@code hyperscanExpressionId} or
     * {@code requiredExpressionIds}+{@code excludedExpressionIds} is populated.
     *
     * @param hyperscanExpressionId for a term without AND NOT: its own term number; null for AND NOT
     * @param requiredExpressionIds AND NOT only: one id per {@code regexPattern} entry; null otherwise
     * @param excludedExpressionIds AND NOT only: one id per {@code exclusionRegex} entry; null otherwise
     * @param patternMapping        the boolean formula over this term's ids ({@code TermCompilationResult}
     *                              "patternMapping"): null for a single-pattern non-AND-NOT term; for several
     *                              leaves it mirrors the COMBINATION written into the {@code .hdb}; for AND NOT
     *                              it is the only place the formula exists
     */
    public record ExpressionAssignment(
            Integer hyperscanExpressionId,
            List<Integer> requiredExpressionIds,
            List<Integer> excludedExpressionIds,
            String patternMapping
    ) {
    }

    /**
     * Appends this PASS term's Hyperscan expressions to {@code expressionsOut} and returns how its ids
     * were assigned; see the class Javadoc for the three shapes. The flag set of each expression follows
     * from its kind (AND NOT pattern, plain single pattern, or QUIET leaf), never from the term's script.
     *
     * @param termResult     a PASS result
     * @param termNumber     the term's number, parsed from its {@code termId}
     * @param idAllocator    shared across the whole bundle request
     * @param expressionsOut receives every expression the term needs
     */
    public ExpressionAssignment addExpressions(TermCompilationResult termResult, int termNumber,
                                               HyperscanIdAllocator idAllocator, List<Expression> expressionsOut) {
        List<String> requiredPatterns = termResult.regexPattern();

        if (termResult.requiresExclusionCheck()) {
            // AND NOT — no combination, regardless of decomposition on either side.
            // Every pattern (both sides) is its own plain, individually-reportable expression.
            List<Integer> requiredIds = addPlainSide(requiredPatterns, termResult.hyperscanFlags(), idAllocator, expressionsOut);
            List<Integer> excludedIds = addPlainSide(termResult.exclusionRegex(), termResult.hyperscanFlags(), idAllocator, expressionsOut);
            String patternMapping = buildAndNotFormula(
                    requiredIds, termResult.patternFormulaTemplate(),
                    excludedIds, termResult.exclusionFormulaTemplate());
            return new ExpressionAssignment(null, requiredIds, excludedIds, patternMapping);
        }

        if (requiredPatterns.size() == 1) {
            // Simplest, most common case — one plain pattern, reportable directly.
            expressionsOut.add(new Expression(
                    requiredPatterns.getFirst(),
                    compiler.toExpressionFlags(termResult.hyperscanFlags()),
                    termNumber));
            return new ExpressionAssignment(termNumber, null, null, null);
        }

        // NEAR/FOLLOWEDBY structure, no AND NOT — native COMBINATION remains safe here (no
        // negation involved) — see class Javadoc for why this path is unaffected by the AND
        // NOT fix, and now fires unconditionally rather than only when over budget.
        List<Integer> leafIds = addQuietSide(requiredPatterns, termResult.hyperscanFlags(), idAllocator, expressionsOut);
        String combinationFormula = "(" + applyFormulaTemplate(termResult.patternFormulaTemplate(), leafIds) + ")";
        expressionsOut.add(new Expression(combinationFormula, compiler.toCombinationExpressionFlags(), termNumber));
        return new ExpressionAssignment(termNumber, null, null, combinationFormula);
    }

    /**
     * Adds each pattern as its own plain, individually reportable expression (used for the AND NOT
     * sides), flagged by {@link HyperscanCompiler#toAndNotExpressionFlags(int)}.
     *
     * @param hyperscanFlags the term's {@code TermCompilationResult.hyperscanFlags()} bitmask
     */
    private List<Integer> addPlainSide(List<String> patterns, int hyperscanFlags, HyperscanIdAllocator idAllocator,
                                       List<Expression> expressionsOut) {
        List<Integer> ids = new ArrayList<>(patterns.size());
        for (String pattern : patterns) {
            int id = idAllocator.allocate();
            expressionsOut.add(new Expression(pattern, compiler.toAndNotExpressionFlags(hyperscanFlags), id));
            ids.add(id);
        }
        return ids;
    }

    /**
     * Adds each pattern as its own QUIET expression and returns the allocated ids (used for the
     * fallback-leaves COMBINATION path), flagged by {@link HyperscanCompiler#toSubExpressionFlags(int)}.
     *
     * @param hyperscanFlags the term's {@code TermCompilationResult.hyperscanFlags()} bitmask
     */
    private List<Integer> addQuietSide(List<String> patterns, int hyperscanFlags, HyperscanIdAllocator idAllocator,
                                       List<Expression> expressionsOut) {
        List<Integer> ids = new ArrayList<>(patterns.size());
        for (String pattern : patterns) {
            int id = idAllocator.allocate();
            expressionsOut.add(new Expression(pattern, compiler.toSubExpressionFlags(hyperscanFlags), id));
            ids.add(id);
        }
        return ids;
    }

    /**
     * Builds the AND NOT {@code patternMapping}, {@code "(<required>&!<excluded>)"}, with each side rendered
     * by {@link #sideFormula}. Recorded only in the JSON; the {@code .hdb} never encodes it.
     */
    private static String buildAndNotFormula(List<Integer> requiredIds, String requiredFormulaTemplate,
                                             List<Integer> excludedIds, String excludedFormulaTemplate) {
        return "(" + sideFormula(requiredIds, requiredFormulaTemplate)
                + "&!" + sideFormula(excludedIds, excludedFormulaTemplate) + ")";
    }

    /**
     * One side's sub-formula: the bare id for a single pattern, otherwise a parenthesised AND-join that
     * follows {@code formulaTemplate}'s grouping (the term's authored nesting) or, with no template, a flat
     * {@code (id1&id2&...)}.
     */
    private static String sideFormula(List<Integer> ids, String formulaTemplate) {
        if (ids.size() == 1) {
            return String.valueOf(ids.getFirst());
        }
        return "(" + applyFormulaTemplate(formulaTemplate, ids) + ")";
    }

    /**
     * Substitutes each {@code {i}} placeholder in {@code formulaTemplate} with {@code ids.get(i)}
     * (see {@code PatternDecomposer.Result#formulaTemplate()}); with a null template, a flat
     * {@code id1&id2&...} AND-join in leaf order.
     */
    private static String applyFormulaTemplate(String formulaTemplate, List<Integer> ids) {
        if (formulaTemplate == null) {
            return joinWithAnd(ids);
        }
        Matcher matcher = FORMULA_PLACEHOLDER.matcher(formulaTemplate);
        StringBuilder substituted = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(substituted, String.valueOf(ids.get(Integer.parseInt(matcher.group(1)))));
        }
        matcher.appendTail(substituted);
        return substituted.toString();
    }

    private static final Pattern FORMULA_PLACEHOLDER = Pattern.compile("\\{(\\d+)}");

    private static String joinWithAnd(List<Integer> ids) {
        return ids.stream().map(String::valueOf).collect(Collectors.joining("&"));
    }
}
