package com.db.macs3.ecomms.spectre.hyperscan;

import com.db.macs3.ecomms.spectre.model.TermCompilationResult;
import com.gliwka.hyperscan.wrapper.Expression;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds the Hyperscan {@link Expression}(s) one term needs in a
 * {@code /compile/bundle} combined database.
 *
 * <h2>AND NOT no longer uses native Hyperscan COMBINATION — confirmed broken</h2>
 * <p>An earlier version of this class compiled every AND NOT term as a
 * single native {@code HS_FLAG_COMBINATION} formula, e.g.
 * {@code (R&!E)} or, for a decomposed excluded side,
 * {@code (R&(!E1|!E2|!Em))}. This is confirmed BROKEN by Hyperscan's own
 * documented evaluation model, not merely observed as a bug in this
 * project's own testing:
 *
 * <ul>
 *   <li>Hyperscan's Compiling Patterns guide states a combination
 *       expression "will raise matches at every offset where one of its
 *       sub-expressions matches and the logical value of the whole
 *       expression is true" — combinations are evaluated EAGERLY and
 *       PROGRESSIVELY as the scan proceeds, not once, holistically, after
 *       the whole text has been seen.</li>
 *   <li>Hyperscan's own changelog documents special-case handling for
 *       <i>purely negative</i> combinations specifically because of this:
 *       "add support for purely negative combinations, which report match
 *       at EOD [end-of-data] in case of no sub-expressions matched." A
 *       combination that can be satisfied by "nothing has matched (yet)"
 *       is deliberately deferred to end-of-data, since Hyperscan cannot
 *       know until the scan finishes whether that will remain true.</li>
 *   <li>{@code R&!E} is NOT a purely negative combination — it also
 *       requires the positive condition {@code R} to be true — so it does
 *       NOT receive this end-of-data deferral. The moment {@code R}
 *       matches, if {@code E} has simply not been REACHED yet in the scan
 *       (not confirmed absent — merely not yet seen), {@code !E} reads as
 *       true at that instant and the combination fires immediately,
 *       before {@code E} has had any chance to match later in the same
 *       text. This is exactly the false-positive this class previously
 *       produced.</li>
 * </ul>
 *
 * <p><b>Pure decomposition (no AND NOT) is unaffected and remains on
 * native COMBINATION</b> — {@code R1&R2&...&Rn} involves no negation at
 * all, so it has no such ambiguity: a positive sub-expression's truth
 * value is only ever true after it genuinely matches, never before.
 * Hyperscan's own worked example in the same documentation shows exactly
 * this kind of formula firing correctly and progressively as each
 * referenced sub-expression matches. Whether a term uses native
 * COMBINATION is therefore decided strictly by
 * {@link TermCompilationResult#requiresExclusionCheck()}, not by whether
 * either side was decomposed.
 *
 * <h2>The fix: no combination for AND NOT — every pattern reports individually,
 * evaluated by the caller after the whole scan completes</h2>
 * <p>For an AND NOT term, every pattern in both
 * {@link TermCompilationResult#translatedPattern()} (required) and
 * {@link TermCompilationResult#exclusionPattern()} (excluded) compiles as
 * its own plain, independently reportable expression — never
 * {@code QUIET}, never {@code COMBINATION}. A single {@code Scanner.scan()}
 * call is still synchronous and returns the complete list of every match
 * that occurred anywhere in the text by the time it returns, so a caller
 * that waits for the whole scan to finish before evaluating "were ALL
 * required ids present AND NONE of the excluded ids present" sees an
 * accurate, complete picture — this is exactly the "industry-standard"
 * end-of-scan post-processing approach Hyperscan's own eager-combination
 * behaviour requires for any formula mixing a positive and a negative
 * condition. See {@link #addExpressions} return value and
 * {@code LexiconCompileBundleService} for where this evaluation happens.
 *
 * <h2>The id scheme, revised</h2>
 * <p>For a term that does NOT require an exclusion check (simple or purely
 * decomposed), the reportable expression id is still ALWAYS the term's own
 * term number, exactly as before — see {@link ExpressionAssignment#hyperscanExpressionId()}.
 * For an AND NOT term, there is no longer a single reportable id at all —
 * every required and excluded pattern gets its own id from the same
 * allocated auxiliary range decomposition leaves already used, and the
 * caller is told exactly which ids belong to which side via
 * {@link ExpressionAssignment#requiredExpressionIds()} /
 * {@link ExpressionAssignment#excludedExpressionIds()}. This does mean an
 * AND NOT term's {@code .hdb} contribution is no longer self-resolving the
 * way a simple or purely-decomposed term's is — the caller must read the
 * JSON response to know which ids to combine, and how. That trade-off is
 * unavoidable: Hyperscan itself cannot correctly resolve this boolean
 * condition natively for a mixed positive/negative formula, so the
 * responsibility must move to the caller regardless of id-naming choices.
 *
 * <h2>Flag constraint: COMBINATION only pairs with QUIET/SINGLEMATCH</h2>
 * <p>Still relevant for the (now narrower) case where COMBINATION is used
 * at all — a Hyperscan expression flagged {@code COMBINATION} may only
 * additionally carry {@code QUIET} and/or {@code SINGLEMATCH}, never
 * {@code CASELESS}/{@code UTF8}/{@code UCP}/{@code DOTALL}/{@code SOM_LEFTMOST}.
 * {@link HyperscanCompiler#toCombinationExpressionFlags} already returns
 * exactly {@code {COMBINATION}} and nothing else.
 */
@Component
public class HyperscanCombinationHandler {

    private final HyperscanCompiler compiler;

    public HyperscanCombinationHandler(HyperscanCompiler compiler) {
        this.compiler = compiler;
    }

    /**
     * The auxiliary (non-term-number) id range must never overlap a real
     * term number, however sparse or large those term numbers are.
     * {@code offset = (largest term number) + 1} is the first id available
     * for every auxiliary expression this request needs (decomposition
     * leaves, and now every required/excluded pattern of an AND NOT term);
     * every subsequent auxiliary id is handed out sequentially from there
     * by {@link HyperscanIdAllocator}.
     */
    public int computeIdOffset(Collection<Integer> termNumbers) {
        return termNumbers.stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
    }

    /**
     * Hands out sequentially increasing auxiliary ids within one bundle
     * request. Starting from {@link #computeIdOffset}, every id this
     * allocator returns is guaranteed distinct from every real term number
     * and from every other id it has already handed out.
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
     * The result of assigning expression id(s) to one term — exactly one
     * of {@code hyperscanExpressionId} / {@code requiredExpressionIds}+
     * {@code excludedExpressionIds} is populated, never both:
     *
     * @param hyperscanExpressionId populated for a term that does NOT require
     *                              an exclusion check (simple or purely
     *                              decomposed) — always the term's own term
     *                              number. Null for an AND NOT term.
     * @param requiredExpressionIds populated ONLY for an AND NOT term — the id(s)
     *                              of the required side's plain expression(s), one
     *                              per entry of {@code translatedPattern}. Null otherwise.
     * @param excludedExpressionIds populated ONLY for an AND NOT term — the id(s)
     *                              of the excluded side's plain expression(s), one
     *                              per entry of {@code exclusionPattern}. Null otherwise.
     * @param patternMapping        the logical formula over this term's expression id(s)
     *                              — see {@code TermCompilationResult} class Javadoc
     *                              "patternMapping". Null for a simple, single-pattern,
     *                              non-AND-NOT term (nothing to map — its one id IS the
     *                              whole answer). Non-null for pure decomposition (mirrors
     *                              the native COMBINATION formula also written into the
     *                              {@code .hdb}) and for AND NOT (the ONLY place this
     *                              formula is recorded, since AND NOT never gets a native
     *                              COMBINATION in the {@code .hdb} itself).
     */
    public record ExpressionAssignment(
            Integer hyperscanExpressionId,
            List<Integer> requiredExpressionIds,
            List<Integer> excludedExpressionIds,
            String patternMapping
    ) {
    }

    /**
     * Adds this PASS term's Hyperscan {@link Expression}(s) to
     * {@code expressionsOut} and returns how its id(s) were assigned — see
     * class Javadoc for why AND NOT terms and non-AND-NOT terms are handled
     * completely differently now.
     *
     * <p>Which {@code ExpressionFlag} set an expression gets is decided
     * strictly by which of these three cases it falls into — never by the
     * term's own script content any more:
     * <ul>
     *   <li>AND NOT (any term, regardless of decomposition on either side) —
     *       {@link HyperscanCompiler#toAndNotExpressionFlags} ({@code CASELESS} only)</li>
     *   <li>Simple, single-pattern, non-AND-NOT PASS term —
     *       {@link HyperscanCompiler#toExpressionFlags} ({@code CASELESS},
     *       {@code DOTALL}, {@code SOM_LEFTMOST} always, plus {@code UTF8}/
     *       {@code UCP} when the term's content needs them)</li>
     *   <li>Pure decomposition leaf, no AND NOT —
     *       {@link HyperscanCompiler#toSubExpressionFlags} ({@code CASELESS}, {@code QUIET})</li>
     * </ul>
     *
     * @param termResult     a PASS result
     * @param termNumber     this term's own term number, parsed from its {@code termId}
     * @param idAllocator    shared across the whole bundle request — see {@link #computeIdOffset}
     * @param expressionsOut every expression this term needs is appended here
     * @return this term's id assignment — see {@link ExpressionAssignment}
     */
    public ExpressionAssignment addExpressions(TermCompilationResult termResult, int termNumber,
                                               HyperscanIdAllocator idAllocator, List<Expression> expressionsOut) {
        List<String> requiredPatterns = termResult.translatedPattern();

        if (termResult.requiresExclusionCheck()) {
            // AND NOT — no combination, regardless of decomposition on either side.
            // Every pattern (both sides) is its own plain, individually-reportable expression.
            List<Integer> requiredIds = addPlainSide(requiredPatterns, idAllocator, expressionsOut);
            List<Integer> excludedIds = addPlainSide(termResult.exclusionPattern(), idAllocator, expressionsOut);
            String patternMapping = buildAndNotFormula(requiredIds, excludedIds);
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

        // Pure decomposition, no AND NOT — native COMBINATION remains safe here (no negation
        // involved) — see class Javadoc for why this path is unaffected by the AND NOT fix.
        List<Integer> leafIds = addQuietSide(requiredPatterns, idAllocator, expressionsOut);
        String combinationFormula = "(" + joinWithAnd(leafIds) + ")";
        expressionsOut.add(new Expression(combinationFormula, compiler.toCombinationExpressionFlags(), termNumber));
        return new ExpressionAssignment(termNumber, null, null, combinationFormula);
    }

    /**
     * Adds every pattern in {@code patterns} as its own PLAIN (non-QUIET,
     * non-COMBINATION, individually reportable) expression — used only for
     * AND NOT terms now, where every required/excluded pattern must report
     * on its own so the caller can evaluate the boolean condition after the
     * whole scan completes. Flagged via {@link HyperscanCompiler#toAndNotExpressionFlags}
     * ({@code CASELESS} only — no SOM_LEFTMOST, even though these are plain,
     * non-QUIET expressions for which SOM_LEFTMOST would be structurally safe).
     */
    private List<Integer> addPlainSide(List<String> patterns, HyperscanIdAllocator idAllocator,
                                       List<Expression> expressionsOut) {
        List<Integer> ids = new ArrayList<>(patterns.size());
        for (String pattern : patterns) {
            int id = idAllocator.allocate();
            expressionsOut.add(new Expression(pattern, compiler.toAndNotExpressionFlags(), id));
            ids.add(id);
        }
        return ids;
    }

    /**
     * Adds every pattern in {@code patterns} as its own QUIET expression,
     * returning their allocated ids. Used only for the pure-decomposition
     * (no AND NOT) COMBINATION path, which remains safe — see class Javadoc.
     * Flagged via {@link HyperscanCompiler#toSubExpressionFlags}
     * ({@code CASELESS} + {@code QUIET} — never SOM_LEFTMOST, confirmed
     * incompatible with QUIET).
     */
    private List<Integer> addQuietSide(List<String> patterns, HyperscanIdAllocator idAllocator,
                                       List<Expression> expressionsOut) {
        List<Integer> ids = new ArrayList<>(patterns.size());
        for (String pattern : patterns) {
            int id = idAllocator.allocate();
            expressionsOut.add(new Expression(pattern, compiler.toSubExpressionFlags(), id));
            ids.add(id);
        }
        return ids;
    }

    /**
     * Builds the AND NOT logical formula for {@code TermCompilationResult.patternMapping}
     * — {@code "(<required>&!<excluded>)"}, where each side is {@link #joinFormula}'d
     * independently (bare id if that side has exactly one; parenthesised
     * {@code (id1&id2&...)} AND-join if it was decomposed into several — same
     * "AND convention" documented on {@code requiredExpressionIds}/{@code excludedExpressionIds}).
     * This is the ONLY place this formula is recorded — never written into the
     * {@code .hdb} itself as a native {@code COMBINATION}, since that combination
     * shape is confirmed unsafe for AND NOT (see class Javadoc).
     */
    private static String buildAndNotFormula(List<Integer> requiredIds, List<Integer> excludedIds) {
        return "(" + joinFormula(requiredIds) + "&!" + joinFormula(excludedIds) + ")";
    }

    /**
     * One side's AND-join sub-formula: a bare id when {@code ids} has exactly
     * one entry, or a parenthesised {@code (id1&id2&...)} when it was
     * decomposed into several.
     */
    private static String joinFormula(List<Integer> ids) {
        return ids.size() == 1 ? String.valueOf(ids.getFirst()) : "(" + joinWithAnd(ids) + ")";
    }

    private static String joinWithAnd(List<Integer> ids) {
        return ids.stream().map(String::valueOf).collect(Collectors.joining("&"));
    }
}
