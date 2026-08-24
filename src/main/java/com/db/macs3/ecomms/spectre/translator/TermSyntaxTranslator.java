package com.db.macs3.ecomms.spectre.translator;

import com.db.macs3.ecomms.spectre.hyperscan.HyperscanCompiler;
import com.ibm.icu.text.Normalizer2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Translates lexicon term descriptions (operator language) into
 * Hyperscan-compatible PCRE patterns.
 *
 * <h2>Pipeline</h2>
 * <pre>
 * raw text → preprocess → Tokenizer → List&lt;Token&gt; → ExpressionParser → Ast (+ warnings)
 *          → per side (required; excluded, if AND NOT):
 *              PatternComplexityAnalyzer over budget?
 *                → yes: PatternDecomposer → independent leaf patterns, EACH real-Hyperscan-validated (+ warning)
 *                → no:  PatternCodeGenerator → one pattern → real-Hyperscan-validated
 *                         → Hyperscan ALSO rejects it as "too large"? → fall back to decomposition anyway
 *                         → Hyperscan rejects it for any OTHER reason?  → translation fails (real error surfaced)
 * </pre>
 *
 * <p>Each stage is a separate, independently-testable class.
 *
 * <h2>Decomposition triggers on EITHER signal — heuristic OR real Hyperscan rejection</h2>
 * <p>{@link PatternComplexityAnalyzer} is a heuristic (see its class Javadoc)
 * calibrated on two known real outcomes — it can under-estimate a structure
 * it has not seen before. This class therefore does not treat "under budget"
 * as the final word: a side that passes the heuristic still gets its
 * generated pattern checked against the REAL Hyperscan compiler
 * ({@link HyperscanCompiler#validate}) before being accepted. If Hyperscan
 * itself rejects that pattern with a size-related error ("Pattern is too
 * large" / "too large" — see {@link #isPatternTooLargeError}), this class
 * falls back to decomposition exactly as it would have if the heuristic had
 * caught it up front — the two triggers converge on the same recovery path.
 * A Hyperscan rejection for any OTHER reason (a genuinely malformed pattern)
 * is NOT treated as a decomposition trigger — decomposition cannot fix a
 * broken pattern, only an oversized one, so that case fails translation with
 * Hyperscan's real error surfaced directly.
 *
 * <p>Because every pattern this class returns (single or decomposed) has
 * therefore ALREADY been validated against real Hyperscan, {@code
 * LexiconCompileService} and {@code LexiconCompileBundleService} no longer
 * need to independently re-validate a translator-produced pattern — they
 * trust {@link TranslationResult.Success} as already Hyperscan-clean. (The
 * separate {@code Regex} term type — raw, caller-supplied PCRE that never
 * passes through this translator at all — is unaffected and still validated
 * directly where it is compiled; decomposition fundamentally cannot apply to
 * it either, since it has no parsed AST/proximity structure to decompose.)
 *
 * <h2>Operator precedence (grammar — see {@link ExpressionParser})</h2>
 * <table>
 * <tr><td>Atom</td><td>word, "quoted phrase", emoji, non-english, wildcard, parens (highest)</td></tr>
 * <tr><td>NEAR{n} / FOLLOWEDBY{n}</td><td>proximity, bounded gap — chaining without explicit
 *     parentheses is accepted (warned, not rejected) — see {@link ExpressionParser}</td></tr>
 * <tr><td>AND</td><td>co-occurrence — all operands present, any order, NO distance limit</td></tr>
 * <tr><td>AND NOT</td><td>the required side must be present; the excluded side(s) must be
 *     absent from the whole message — see the two-pattern contract below.
 *     There is no independent NOT operator: {@code NOT} is only ever valid
 *     immediately after {@code AND}.</td></tr>
 * <tr><td>OR</td><td>alternation (lowest)</td></tr>
 * </table>
 *
 * <h2>AND: same technique as NEAR/FOLLOWEDBY, just unbounded</h2>
 * <p>{@code price AND rigging} compiles to a single, fully correct Hyperscan
 * pattern using the same bidirectional-alternation technique NEAR uses for a
 * bounded gap, with the gap made unbounded — see {@link PatternCodeGenerator}
 * class Javadoc. No lookahead, no post-filter, no scan-time cooperation
 * needed from the caller.
 *
 * <h2>AND NOT: a two-pattern contract, not a single regex</h2>
 * <p>Hyperscan cannot express "absent from the whole message" — that is
 * exactly what negative lookaround is for, and Hyperscan supports none.
 * {@code A AND NOT B} therefore returns TWO independently Hyperscan-valid
 * patterns: {@link TranslationResult.Success#hsPattern()} (A) and
 * {@link TranslationResult.Success#exclusionPattern()} (B). The term is
 * correctly matched only when {@code hsPattern} matches the message AND
 * {@code exclusionPattern} does not — see {@link TranslationResult} class
 * Javadoc for the exact contract.
 *
 * <h2>Pattern examples</h2>
 * <pre>
 * (crap OR bad) NEAR{3} (bonus OR comp)
 *   → (?:(?:crap|bad)(?:\s+\S+){0,3}\s+(?:bonus|comp)|(?:bonus|comp)(?:\s+\S+){0,3}\s+(?:crap|bad))
 *
 * (F) FOLLOWEDBY{1} (((me) OR (cking)))
 *   → F(?:\s+\S+){0,1}\s+(?:me|cking)
 *
 * price AND rigging
 *   → hsPattern: (?:price[\s\S]*rigging|rigging[\s\S]*price)
 *   Matches "...price change and market rigging is going on" (both present,
 *   any order, any distance apart). Does NOT match "There's price change"
 *   alone (rigging never appears).
 *
 * ((fix) OR (rig)) FOLLOWEDBY{2} (the rate) AND NOT (fed rate move)
 *   → hsPattern:               (?:fix|rig)(?:\s+\S+){0,2}\s+the rate
 *     requiresExclusionCheck:  true
 *     exclusionPattern:        (?:fed rate move)
 *   The caller must check BOTH: matched iff hsPattern matches AND
 *   exclusionPattern does not — see README "AND NOT: the two-pattern contract".
 *
 * ((he?d kill) OR (she?d kill))
 *   → (?:he\?d kill|she\?d kill)     — '?' is always literal
 *
 * (check her out) OR (chimp*)
 *   → (?:check her out|chimp\S*)
 *
 * 내부자 NEAR{3} 거래    (Korean insider NEAR trading)
 *   → (?:내부자[\s\S]{0,18}거래|거래[\s\S]{0,18}내부자)
 *
 * 💰 OR 🤫
 *   → (?:\x{1F4B0}|\x{1F92B})  (with UTF8+UCP flags)
 * </pre>
 */
@Component
public final class TermSyntaxTranslator {

    private static final Logger log = LoggerFactory.getLogger(TermSyntaxTranslator.class);

    private final HyperscanCompiler compiler;

    public TermSyntaxTranslator(HyperscanCompiler compiler) {
        this.compiler = compiler;
    }

    /**
     * Translates one lexicon term description into a Hyperscan PCRE pattern
     * (or, for a side that needed decomposition, a set of independent
     * Hyperscan-validated patterns — see class Javadoc). Every pattern in a
     * successful result has already been checked against the real Hyperscan
     * compiler.
     *
     * @param rawExpression the "Term Description" from the CSV/JSON input
     * @return {@link TranslationResult.Success}, or
     *         {@link TranslationResult.Error} with a specific, actionable error message
     */
    public TranslationResult translate(String rawExpression) {
        if (rawExpression == null || rawExpression.isBlank()) {
            return TranslationResult.error("Term description is null or blank");
        }
        try {
            String preprocessed = preprocess(rawExpression);
            log.debug("Translating: '{}'", preprocessed);

            List<Token> tokens = Tokenizer.tokenize(preprocessed);
            ExpressionParser.ParseResult parseResult = ExpressionParser.parse(tokens, preprocessed);
            Ast ast = parseResult.ast();
            List<String> warnings = new ArrayList<>(parseResult.warnings());

            ParseContext ctx = new ParseContext();

            if (ast instanceof Ast.AndNot andNot) {
                // AND NOT is only handled at the ROOT of the term's AST — see
                // rejectNestedAndNot() Javadoc for why a SECOND, nested AND NOT within
                // either side (e.g. "A AND NOT (B AND NOT C)" or "(D AND NOT E) AND NOT F")
                // is rejected here rather than silently mishandled the same way a
                // non-root AND NOT elsewhere in the tree would be.
                rejectNestedAndNot(andNot.required(), preprocessed);
                for (Ast excludedOperand : andNot.excluded()) {
                    rejectNestedAndNot(excludedOperand, preprocessed);
                }

                // Multiple "AND NOT X AND NOT Y" excluded operands are combined via OR into
                // one Ast, exactly as PatternCodeGenerator.generateAndNot() already does inline
                // for the non-decomposed case — see PatternDecomposer/generateAndNot comparison.
                Ast excludedCombined = new Ast.Or(andNot.excluded());

                // GENERATE both sides' candidate patterns before validating EITHER — see class
                // Javadoc "the flag-accuracy reason this is two phases, not one": a side validated
                // before the OTHER side has even been generated could be checked against a flags
                // value missing UTF8/UCP only the other side's content would have required.
                Candidate requiredCandidate = generateSide(andNot.required(), ctx);
                Candidate excludedCandidate = generateSide(excludedCombined, ctx);
                int flags = ctx.computeFlags();

                SideResult required
                        = validateSide(requiredCandidate, ctx, flags,
                        preprocessed, "required", warnings);
                SideResult excluded
                        = validateSide(excludedCandidate, ctx, flags,
                        preprocessed, "excluded (AND NOT)", warnings);

                log.debug("Translated (AND NOT): '{}' -> required={} excluded={} flags={} warnings={}",
                        rawExpression, required, excluded, flags, warnings.size());

                return new TranslationResult.Success(
                        required.patterns(), flags, true, excluded.patterns(),
                        List.copyOf(warnings));
            }

            // The root is NOT AndNot — per rejectNestedAndNot() Javadoc, an AndNot node
            // ANYWHERE in this tree would otherwise be silently mishandled: PatternCodeGenerator
            // still has a case for it (so no exception is thrown by code generation itself), but
            // its only effect is a side-channel ctx.setExclusionPattern() call whose result is
            // then discarded entirely, since this branch always sets requiresExclusionCheck=false.
            rejectNestedAndNot(ast, preprocessed);

            Candidate candidate = generateSide(ast, ctx);
            int flags = ctx.computeFlags();
            SideResult required = validateSide(candidate, ctx, flags, preprocessed, "term", warnings);

            log.debug("Translated: '{}' -> {} flags={} warnings={}",
                    rawExpression, required, flags, warnings.size());

            return new TranslationResult.Success(
                    required.patterns(), flags, false, null,
                    List.copyOf(warnings));

        } catch (TranslationException te) {
            log.warn("Translation failed for '{}': {}", rawExpression, te.getMessage());
            return TranslationResult.error(te.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error translating '{}': {}", rawExpression, e.getMessage(), e);
            return TranslationResult.error("Unexpected error: " + e.getMessage());
        }
    }

    /**
     * Confirmed bug this method fixes: AND NOT is grammatically legal
     * anywhere a parenthesised group is legal — {@code parseParenGroup()}
     * recurses all the way back to the top of the grammar
     * ({@code parseOr()}), so a term like
     * {@code "(insider AND NOT compliance) NEAR{5} trading"} parses
     * successfully into an {@code Ast.Near} whose LEFT operand is an
     * {@code Ast.AndNot} node — {@code AndNot} nested inside {@code Near}.
     *
     * <p>{@code translate()} only ever special-cases AND NOT when it is the
     * ROOT of the whole term's AST (see the {@code ast instanceof Ast.AndNot}
     * check above). For the term above, the root is {@code Ast.Near}, not
     * {@code Ast.AndNot}, so translation takes the ordinary single-pattern
     * path — which does NOT throw or fail. {@code PatternCodeGenerator} has
     * a real {@code case Ast.AndNot} arm ({@code generateAndNot()}), so
     * generation completes without error; but that method's only visible
     * effect is a side-channel {@code ctx.setExclusionPattern(...)} call
     * that this path's caller never reads, since it unconditionally
     * constructs the result with {@code requiresExclusionCheck=false}. The
     * net effect, verified directly: the term above compiled to a PASS
     * result equivalent to plain {@code "insider NEAR{5} trading"} — the
     * {@code "AND NOT compliance"} constraint silently vanished, with no
     * error, no warning, and a PASS status. A message containing
     * "insider trading" would incorrectly match even when "compliance" was
     * also present, exactly the case the term was written to exclude.
     *
     * <p>This is walked and rejected explicitly, rather than left to whatever
     * {@code PatternCodeGenerator} happens to do with an unexpected node
     * shape, for both directions this check runs in: (1) when the whole
     * term's root is NOT {@code Ast.AndNot}, no {@code Ast.AndNot} may
     * appear ANYWHERE in the tree; (2) when the root IS {@code Ast.AndNot},
     * neither its required side nor any of its excluded operands may
     * THEMSELVES contain a further nested {@code Ast.AndNot} — chained
     * exclusions like {@code "A AND NOT B AND NOT C"} are already handled
     * correctly as multiple excluded OPERANDS of one {@code Ast.AndNot} node
     * (see {@code ExpressionParser.parseAndNot()}), not as nesting, so they
     * are unaffected by this check; only a genuinely nested second AND NOT
     * (e.g. {@code "A AND NOT (B AND NOT C)"}) is rejected.
     *
     * <p>Rejecting cleanly was chosen over attempting to support this
     * automatically (e.g. by hoisting a nested exclusion up to the whole
     * term's top level) because hoisting changes what the term actually
     * means — a caller who wrote the exclusion scoped to one operand of a
     * NEAR would silently get a term-wide exclusion instead, which is a
     * different, unrequested semantic change, not a bug fix. A clear
     * rejection lets the term's author rewrite it with AND NOT at the
     * top level explicitly, matching what they intended.
     *
     * @throws TranslationException naming the specific unsupported nesting, if found
     */
    private void rejectNestedAndNot(Ast node, String originalTerm) {
        if (node instanceof Ast.AndNot) {
            throw new TranslationException(
                    "AND NOT may only appear at the top level of a term, combined with the whole "
                    + "term via OR/AND at most — it cannot be nested inside NEAR, FOLLOWEDBY, AND, "
                    + "OR, or another AND NOT (including inside parentheses) in term: '" + originalTerm
                    + "'. Rewrite this term with AND NOT at the outermost level instead — e.g. "
                    + "replace '(A AND NOT B) NEAR{n} C' with the equivalent top-level form "
                    + "'(A NEAR{n} C) AND NOT B' if the exclusion is meant to apply to the whole term.");
        }
        switch (node) {
            case Ast.Or or -> or.operands().forEach(child -> rejectNestedAndNot(child, originalTerm));
            case Ast.And and -> and.operands().forEach(child -> rejectNestedAndNot(child, originalTerm));
            case Ast.Near near -> {
                rejectNestedAndNot(near.left(), originalTerm);
                rejectNestedAndNot(near.right(), originalTerm);
            }
            case Ast.FollowedBy fb -> {
                rejectNestedAndNot(fb.left(), originalTerm);
                rejectNestedAndNot(fb.right(), originalTerm);
            }
            case Ast.AndNot ignored -> throw new IllegalStateException("unreachable — handled above");
            case Ast.Word ignored -> { /* leaf: no children to check */ }
            case Ast.Phrase ignored -> { /* leaf: no children to check */ }
            case Ast.QuotedPhrase ignored -> { /* leaf: no children to check */ }
        }
    }

    // ── Per-side translation: generate (Phase 1), then validate (Phase 2) ──────

    /**
     * One side's translation outcome — always a list: exactly one entry for
     * a side that compiled as a single pattern, two or more when it was
     * decomposed. See {@link TranslationResult} class Javadoc for why there
     * is no separate boolean "was this decomposed" flag any more — the
     * caller just checks {@code patterns.size()}.
     */
    private record SideResult(List<String> patterns) {
        boolean isDecomposed() {
            return patterns.size() > 1;
        }

        @Override public String toString() {
            return isDecomposed() ? "decomposed(" + patterns.size() + " leaves)" : "'" + patterns.getFirst() + "'";
        }
    }

    /**
     * Phase-1 (generation-only, no Hyperscan calls) outcome for one side.
     * Exactly one of {@code singlePattern} / {@code preDecomposedLeaves} is set:
     * the heuristic already flagged this side as over budget (pre-decomposed,
     * every leaf already generated), or it did not (single pattern generated,
     * still needing Phase-2 validation before being trusted).
     */
    private record Candidate(Ast sideAst, String singlePattern, List<String> preDecomposedLeaves) {
        boolean isPreDecomposed() {
            return preDecomposedLeaves != null;
        }
    }

    /**
     * Phase 1: generates this side's pattern(s) — WITHOUT calling Hyperscan —
     * so that {@code ctx}'s UTF8/UCP flag needs are fully populated from
     * EVERY side of the term before {@link ParseContext#computeFlags} is
     * called once, term-wide (see call site in {@link #translate}). This is
     * what keeps Phase 2's validation flags accurate even when, say, only
     * an AND NOT term's EXCLUDED side contains the non-ASCII content that
     * determines whether UTF8/UCP are needed — the REQUIRED side must still
     * be validated with those flags, not with whatever was known before the
     * excluded side was even looked at.
     */
    private Candidate generateSide(Ast sideAst, ParseContext ctx) {
        if (PatternComplexityAnalyzer.isOverBudget(sideAst)) {
            List<String> leafPatterns = PatternDecomposer.decompose(sideAst, ctx);
            return new Candidate(sideAst, null, leafPatterns);
        }
        String pattern = PatternCodeGenerator.generate(sideAst, ctx);
        return new Candidate(sideAst, pattern, null);
    }

    /**
     * Phase 2: validates a Phase-1 {@link Candidate} against the real
     * Hyperscan compiler, using the term's final, complete flags. See class
     * Javadoc for the two decomposition triggers this implements: the
     * heuristic firing in Phase 1 ({@code candidate.isPreDecomposed()}), or
     * real Hyperscan rejecting an under-budget single pattern as too large
     * anyway (handled here, falling back to decomposition on the same leaves
     * {@link PatternDecomposer} would have produced up front).
     *
     * @param candidate    this side's Phase-1 generation outcome
     * @param ctx          shared {@link ParseContext} — used here only to re-derive
     *                     leaves' patterns if a late decomposition is triggered;
     *                     flags are NOT recomputed from it (the caller's {@code flags}
     *                     parameter, computed once after ALL sides were generated, is final)
     * @param flags        the term's final Hyperscan flag bitmask (computed once,
     *                     after every side's Phase-1 generation — see {@link #translate})
     * @param originalTerm the original term text, for error/warning messages
     * @param sideLabel    "term", "required", or "excluded (AND NOT)" — for error/warning messages
     * @param warnings     mutable list this method appends to when decomposition is applied
     */
    private SideResult validateSide(Candidate candidate, ParseContext ctx, int flags, String originalTerm,
                                     String sideLabel, List<String> warnings) {
        if (candidate.isPreDecomposed()) {
            log.debug("'{}' side of '{}' was over budget by heuristic (score {}) — validating its "
                            + "{} pre-generated leaf pattern(s)",
                    sideLabel, originalTerm, PatternComplexityAnalyzer.estimate(candidate.sideAst()),
                    candidate.preDecomposedLeaves().size());
            return validateDecomposedLeaves(candidate.sideAst(), candidate.preDecomposedLeaves(), flags,
                    originalTerm, sideLabel, warnings);
        }

        String pattern = candidate.singlePattern();
        HyperscanCompiler.ValidationResult validation = compiler.validate(pattern, flags);
        if (validation.isPass()) {
            return new SideResult(List.of(pattern));
        }

        if (!isPatternTooLargeError(validation.errorMessage())) {
            // A genuinely malformed pattern -- decomposition cannot fix this, only
            // hide it behind a confusing partial result. Surface Hyperscan's real error.
            throw new TranslationException(
                    "This term's " + sideLabel + " expression translated to a pattern Hyperscan rejected"
                    + " (not a size issue — decomposition would not help): " + validation.errorMessage()
                    + " In term: '" + originalTerm + "'.");
        }

        log.info("'{}' side of '{}' passed the complexity heuristic (score {}, budget {}) but was REJECTED"
                        + " by real Hyperscan as too large ({}) — falling back to decomposition",
                sideLabel, originalTerm, PatternComplexityAnalyzer.estimate(candidate.sideAst()),
                PatternComplexityAnalyzer.COMPLEXITY_BUDGET, validation.errorMessage());

        // The leaves here are subtrees of sideAst, already visited once during this same
        // side's single-pattern generation above — ctx (and therefore `flags`) already
        // reflects everything they need; regenerating their pattern strings is cheap and
        // does not require recomputing flags.
        List<String> leafPatterns = PatternDecomposer.decompose(candidate.sideAst(), ctx);
        return validateDecomposedLeaves(candidate.sideAst(), leafPatterns, flags, originalTerm, sideLabel, warnings);
    }

    /**
     * True when a Hyperscan validation failure message indicates the pattern
     * was rejected for being too large/complex to compile — as opposed to
     * being rejected for a genuine syntax/semantic problem, which
     * decomposition cannot fix. Hyperscan's own wording for this case is
     * "Pattern is too large" (propagated verbatim by
     * {@code HyperscanCompiler.buildErrorMessage} into
     * {@link HyperscanCompiler.ValidationResult#errorMessage()}), so a
     * simple case-insensitive substring check is robust without being so
     * broad it would misfire on an unrelated error.
     */
    private static boolean isPatternTooLargeError(String errorMessage) {
        return errorMessage != null && errorMessage.toLowerCase(java.util.Locale.ROOT).contains("too large");
    }

    /**
     * Validates every already-generated leaf pattern against real Hyperscan
     * (using the term's final flags), records the precision-trade-off
     * warning on success, and returns the decomposed {@link SideResult}.
     *
     * @throws TranslationException if there are fewer than 2 leaves (nothing
     *                               to decompose — the side is already maximally
     *                               flat and still over budget/rejected), or if
     *                               any individual leaf is ALSO rejected by real
     *                               Hyperscan on its own (decomposition cannot
     *                               help there; the leaf itself needs simplifying
     *                               by the author)
     */
    private SideResult validateDecomposedLeaves(Ast sideAst, List<String> leafPatterns, int flags,
                                                 String originalTerm, String sideLabel, List<String> warnings) {
        int wholeScore = PatternComplexityAnalyzer.estimate(sideAst);

        if (leafPatterns.size() < 2) {
            throw new TranslationException(
                    "This term's " + sideLabel + " expression is too structurally complex for Hyperscan to"
                    + " compile as a single pattern (estimated complexity " + wholeScore + ", budget "
                    + PatternComplexityAnalyzer.COMPLEXITY_BUDGET + ") in term: '" + originalTerm + "',"
                    + " and has no NEAR/FOLLOWEDBY structure to decompose — it is a single flat"
                    + " expression (e.g. one large OR/AND group), so splitting it into independent"
                    + " parts would not reduce its own complexity. To fix: reduce the number of"
                    + " OR-alternatives, reduce wildcard usage, or split this into multiple simpler"
                    + " lexicon terms.");
        }

        for (String leafPattern : leafPatterns) {
            HyperscanCompiler.ValidationResult leafValidation = compiler.validate(leafPattern, flags);
            if (!leafValidation.isPass()) {
                throw new TranslationException(
                        "This term's " + sideLabel + " expression (estimated complexity " + wholeScore
                        + ", budget " + PatternComplexityAnalyzer.COMPLEXITY_BUDGET + ") was decomposed into "
                        + leafPatterns.size() + " independent parts to avoid \"Pattern is too large\", but one"
                        + " part ('" + leafPattern + "') was STILL rejected by Hyperscan: "
                        + leafValidation.errorMessage() + " — decomposition cannot help here, since that part"
                        + " has no further NEAR/FOLLOWEDBY structure of its own to split. In term: '"
                        + originalTerm + "'. To fix: reduce the number of OR-alternatives or wildcard usage"
                        + " within that specific part.");
            }
        }

        warnings.add(
                "This term's " + sideLabel + " expression (estimated complexity " + wholeScore + ", budget "
                + PatternComplexityAnalyzer.COMPLEXITY_BUDGET + ") was too structurally complex for Hyperscan"
                + " to compile as one pattern, and was DECOMPOSED into " + leafPatterns.size()
                + " independent parts (each individually Hyperscan-validated) — see"
                + ("excluded (AND NOT)".equals(sideLabel) ? " exclusionPattern" : " translatedPattern")
                + " in the response. IMPORTANT — this changes the term's matching semantics: decomposition"
                + " discards the original NEAR/FOLLOWEDBY ordering/distance constraint BETWEEN parts. The"
                + " decomposed parts are combined with a boolean AND (natively via Hyperscan's logical"
                + " combination for /compile/bundle, or by the caller for /compile and /compile/csv) and"
                + " match when ALL parts are found ANYWHERE in the message, independently of each other —"
                + " NOT tied to the specific order or proximity the original term expressed relative to ONE"
                + " ANOTHER. Each part after the first still carries its own original NEAR/FOLLOWEDBY gap as"
                + " a literal prefix in its own pattern text (so it cannot match with nothing preceding it),"
                + " but that gap is no longer anchored to the specific part that preceded it in the original"
                + " term — only the cross-part relationship is lost, not the gap width itself."
                + " Term: '" + originalTerm + "'.");

        return new SideResult(List.copyOf(leafPatterns));
    }

    // ── Pre-processing ────────────────────────────────────────────────────────

    /**
     * Normalises the raw expression before tokenizing:
     * <ol>
     *   <li>Trim whitespace</li>
     *   <li>Unescape CSV double-quote encoding: {@code ""} → {@code "}</li>
     *   <li>Unicode NFC normalisation (ICU4J) for consistent multi-language handling</li>
     * </ol>
     *
     * <p>Outer {@code "..."} wrapping is intentionally NOT stripped here —
     * {@link Tokenizer} recognises a quoted phrase as its own token type,
     * and {@link ExpressionParser}/{@link PatternCodeGenerator} handle its
     * content as always-literal. Stripping quotes at this stage would expose
     * their content to normal tokenization, silently losing the "always
     * literal" guarantee quotes are supposed to provide.
     */
    private String preprocess(String raw) {
        String normalizedText = raw.trim();
        normalizedText = normalizedText.replace("\"\"", "\u0000DQ\u0000");
        normalizedText = normalizedText.replace("\u0000DQ\u0000", "\"");
        try {
            normalizedText = Normalizer2.getNFCInstance().normalize(normalizedText);
        } catch (Exception e) {
            log.debug("ICU4J normalisation skipped: {}", e.getMessage());
        }
        return normalizedText;
    }
}
