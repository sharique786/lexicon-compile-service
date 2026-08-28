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
 * <p><b>Pipeline</b>
 * <pre>
 * raw text → preprocess → Tokenizer → List&lt;Token&gt; → ExpressionParser → Ast (+ warnings)
 *          → per side (required; excluded, if AND NOT):
 *              PatternDecomposer.decompose() → independent leaf pattern(s) + literal-keyword
 *                resolvedText, EACH leaf real-Hyperscan-validated
 * </pre>
 *
 * <p>Each stage is a separate, independently-testable class.
 *
 * <p><b>NEAR/FOLLOWEDBY always split into independent leaves — never a gap-regex</b>
 * <p>{@link PatternDecomposer} is now the unconditional path for ANY side
 * containing NEAR/FOLLOWEDBY structure — not a complexity-driven fallback
 * (see {@link PatternComplexityAnalyzer}'s class Javadoc for why that
 * heuristic is no longer consulted here at all). The gap between two
 * proximity operands is NEVER compiled into a regex fragment for this path
 * — instead, {@link PatternDecomposer.Result#resolvedText()} conveys the
 * relationship as literal {@code NEAR{n}}/{@code FOLLOWEDBY{n}}/
 * {@code AND NOT} keyword text, using the term author's raw, un-clamped
 * distance, populated into {@link TranslationResult.Success#resolvedPattern()}.
 * One narrow, deliberate exception remains: a NEAR/FOLLOWEDBY nested inside
 * a multi-operand {@code OR} still compiles as a single gap-embedded
 * pattern, exactly as before — see {@link PatternDecomposer} class Javadoc
 * "the one exception" for why that case can't be flattened losslessly.
 *
 * <p>A side's leaf pattern(s) are always validated against the REAL
 * Hyperscan compiler ({@link HyperscanCompiler#validate}) before being
 * accepted — a leaf Hyperscan itself rejects, for any reason, is a hard
 * translation failure; there is nothing further for decomposition to try,
 * since {@link PatternDecomposer} has already split every NEAR/FOLLOWEDBY
 * boundary it safely can.
 *
 * <p>Because every pattern this class returns has therefore ALREADY been
 * validated against real Hyperscan, {@code LexiconCompileService} and
 * {@code LexiconCompileBundleService} no longer need to independently
 * re-validate a translator-produced pattern — they trust
 * {@link TranslationResult.Success} as already Hyperscan-clean. (The
 * separate {@code Regex} term type — raw, caller-supplied PCRE that never
 * passes through this translator at all — is unaffected and still validated
 * directly where it is compiled; it has no parsed AST/proximity structure to
 * decompose either, and gets no {@code resolvedPattern}.)
 *
 * <p><b>Operator precedence (grammar — see {@link ExpressionParser})</b>
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
 * <p><b>AND: same technique as before, unaffected by this class's NEAR/FOLLOWEDBY changes</b>
 * <p>{@code price AND rigging} compiles to a single, fully correct Hyperscan
 * pattern using bidirectional-alternation over an unbounded gap — see
 * {@link PatternCodeGenerator} class Javadoc. No lookahead, no post-filter,
 * no scan-time cooperation needed from the caller. An {@code AND} whose
 * operand contains NEAR/FOLLOWEDBY structure is the one case that DOES
 * split now — see {@link PatternDecomposer} class Javadoc "AND is
 * flattened".
 *
 * <p><b>AND NOT: a two-pattern contract, not a single regex</b>
 * <p>Hyperscan cannot express "absent from the whole message" — that is
 * exactly what negative lookaround is for, and Hyperscan supports none.
 * {@code A AND NOT B} therefore returns TWO independently Hyperscan-valid
 * pattern lists: {@link TranslationResult.Success#hsPatterns()} (A) and
 * {@link TranslationResult.Success#exclusionRegexs()} (B). The term is
 * correctly matched only when EVERY entry of {@code hsPatterns} matches the
 * message AND NOT every entry of {@code exclusionRegexs} does — see
 * {@link TranslationResult} class Javadoc for the exact contract.
 *
 * <p><b>Pattern examples</b>
 * <pre>
 * (crap OR bad) NEAR{3} (bonus OR comp)
 *   → hsPatterns:     [(?:crap|bad), (?:bonus|comp)]
 *     resolvedPattern: "(?:crap|bad) NEAR{3} (?:bonus|comp)"
 *
 * (F) FOLLOWEDBY{1} (((me) OR (cking)))
 *   → hsPatterns:     [F, (?:me|cking)]
 *     resolvedPattern: "F FOLLOWEDBY{1} (?:me|cking)"
 *
 * price AND rigging
 *   → hsPatterns: [(?:price[\s\S]*rigging|rigging[\s\S]*price)]   (still one self-contained pattern — AND alone is unaffected)
 *   Matches "...price change and market rigging is going on" (both present,
 *   any order, any distance apart). Does NOT match "There's price change"
 *   alone (rigging never appears).
 *
 * ((fix) OR (rig)) FOLLOWEDBY{2} (the rate) AND NOT (fed rate move)
 *   → hsPatterns:            [(?:fix|rig), the rate]
 *     requiresExclusionCheck: true
 *     exclusionRegexs:        [(?:fed rate move)]
 *     resolvedPattern:        "(?:fix|rig) FOLLOWEDBY{2} the rate AND NOT ((?:fed rate move))"
 *   The caller must check BOTH: matched iff every hsPatterns entry matches
 *   AND not every exclusionRegexs entry does — see README "AND NOT: the
 *   two-pattern contract". A caller that also needs to enforce the
 *   FOLLOWEDBY{2} proximity itself (Hyperscan no longer does) reads
 *   resolvedPattern.
 *
 * ((he?d kill) OR (she?d kill))
 *   → (?:he\?d kill|she\?d kill)     — '?' is always literal
 *
 * (check her out) OR (chimp*)
 *   → (?:check her out|chimp\S*)
 *
 * 내부자 NEAR{3} 거래    (Korean insider NEAR trading)
 *   → hsPatterns:     [내부자, 거래]
 *     resolvedPattern: "내부자 NEAR{3} 거래"   (raw distance 3 — no avgCharsPerWord multiplication any more)
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
     * (or, for a side split by NEAR/FOLLOWEDBY, a set of independent
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
                // for the non-split case — see PatternDecomposer for how this Or is then seen
                // through when it has exactly one operand.
                Ast excludedCombined = new Ast.Or(andNot.excluded());

                // Decompose BOTH sides before validating EITHER — same flag-accuracy reason as
                // before: a side validated before the OTHER side has even been generated could
                // be checked against a flags value missing UTF8/UCP only the other side's
                // content would have required.
                PatternDecomposer.Result requiredResult = PatternDecomposer.decompose(andNot.required(), ctx);
                PatternDecomposer.Result excludedResult = PatternDecomposer.decompose(excludedCombined, ctx);
                int flags = ctx.computeFlags();

                SideResult required
                        = validateLeaves(requiredResult.leaves(), flags,
                        preprocessed, "required", warnings);
                SideResult excluded
                        = validateLeaves(excludedResult.leaves(), flags,
                        preprocessed, "excluded (AND NOT)", warnings);
                warnings.addAll(ctx.getWarnings());

                String resolvedPattern = requiredResult.resolvedText()
                        + " AND NOT (" + excludedResult.resolvedText() + ")";

                log.debug("Translated (AND NOT): '{}' -> required={} excluded={} flags={} warnings={}",
                        rawExpression, required, excluded, flags, warnings.size());

                return new TranslationResult.Success(
                        required.patterns(), flags, true, excluded.patterns(),
                        List.copyOf(warnings), resolvedPattern);
            }

            // The root is NOT AndNot — per rejectNestedAndNot() Javadoc, an AndNot node
            // ANYWHERE in this tree would otherwise be silently mishandled: PatternCodeGenerator
            // still has a case for it (so no exception is thrown by code generation itself), but
            // its only effect is a side-channel ctx.setexclusionRegex() call whose result is
            // then discarded entirely, since this branch always sets requiresExclusionCheck=false.
            rejectNestedAndNot(ast, preprocessed);

            PatternDecomposer.Result result = PatternDecomposer.decompose(ast, ctx);
            int flags = ctx.computeFlags();
            SideResult required = validateLeaves(result.leaves(), flags, preprocessed, "term", warnings);
            warnings.addAll(ctx.getWarnings());

            log.debug("Translated: '{}' -> {} flags={} warnings={}",
                    rawExpression, required, flags, warnings.size());

            return new TranslationResult.Success(
                    required.patterns(), flags, false, null,
                    List.copyOf(warnings), result.resolvedText());

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
     * effect is a side-channel {@code ctx.setexclusionRegex(...)} call
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
            case Ast.Not ignored -> throw new IllegalStateException(
                    "unreachable — every Ast.Not is folded into Ast.AndNot (or rejected) by "
                    + "ExpressionParser.parseAnd() before an Ast is ever returned; see Ast.Not Javadoc");
            case Ast.Word ignored -> { /* leaf: no children to check */ }
            case Ast.Phrase ignored -> { /* leaf: no children to check */ }
            case Ast.QuotedPhrase ignored -> { /* leaf: no children to check */ }
        }
    }

    // ── Per-side translation: decompose, then validate every leaf ──────────────

    /**
     * One side's translation outcome — always a list: exactly one entry for
     * a side with no NEAR/FOLLOWEDBY structure, two or more when
     * {@link PatternDecomposer} split it. See {@link TranslationResult}
     * class Javadoc for why there is no separate boolean "was this split"
     * flag any more — the caller just checks {@code patterns.size()}.
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
     * Validates every leaf {@link PatternDecomposer#decompose} already
     * produced for one side against the REAL Hyperscan compiler, using the
     * term's final, complete flags. {@link PatternDecomposer} has already
     * done all the splitting this class is willing to do — a leaf Hyperscan
     * rejects here (for any reason, including "too large") is a hard
     * failure, since there is no further NEAR/FOLLOWEDBY structure left to
     * split it with.
     *
     * @param leafPatterns already-generated, already-decomposed leaf pattern(s) for this side
     * @param flags        the term's final Hyperscan flag bitmask (computed once,
     *                     after every side's decomposition — see {@link #translate})
     * @param originalTerm the original term text, for error/warning messages
     * @param sideLabel    "term", "required", or "excluded (AND NOT)" — for error/warning messages
     * @param warnings     mutable list this method appends to when the side has more than one leaf
     */
    private SideResult validateLeaves(List<String> leafPatterns, int flags, String originalTerm,
                                       String sideLabel, List<String> warnings) {
        if (leafPatterns.size() == 1) {
            String pattern = leafPatterns.getFirst();
            HyperscanCompiler.ValidationResult validation = compiler.validate(pattern, flags);
            if (!validation.isPass()) {
                throw new TranslationException(
                        "This term's " + sideLabel + " expression translated to a pattern Hyperscan rejected: "
                        + validation.errorMessage() + " In term: '" + originalTerm + "'. This expression has"
                        + " no NEAR/FOLLOWEDBY structure to split further — to fix, reduce the number of"
                        + " OR-alternatives, reduce wildcard usage, or split this into multiple simpler"
                        + " lexicon terms.");
            }
            return new SideResult(List.of(pattern));
        }

        for (String leafPattern : leafPatterns) {
            HyperscanCompiler.ValidationResult leafValidation = compiler.validate(leafPattern, flags);
            if (!leafValidation.isPass()) {
                throw new TranslationException(
                        "This term's " + sideLabel + " expression was split into " + leafPatterns.size()
                        + " independent parts (see resolvedPatterns for the full NEAR/FOLLOWEDBY/AND NOT"
                        + " structure), but one part ('" + leafPattern + "') was rejected by Hyperscan: "
                        + leafValidation.errorMessage() + " — decomposition cannot help here, since that part"
                        + " has no further NEAR/FOLLOWEDBY structure of its own to split. In term: '"
                        + originalTerm + "'. To fix: reduce the number of OR-alternatives or wildcard usage"
                        + " within that specific part.");
            }
        }

        warnings.add(
                "This term's " + sideLabel + " expression contains NEAR/FOLLOWEDBY structure and was split"
                + " into " + leafPatterns.size() + " independent parts (each individually Hyperscan-validated)"
                + " — see" + ("excluded (AND NOT)".equals(sideLabel) ? " exclusionRegex" : " regexPattern")
                + " in the response. IMPORTANT — the parts are combined with a boolean AND (natively via"
                + " Hyperscan's logical combination for /compile/bundle, or by the caller for /compile and"
                + " /compile/csv) and match when ALL parts are found ANYWHERE in the message, independently"
                + " of each other. The NEAR/FOLLOWEDBY distance and order relationship between the parts is"
                + " NOT encoded in these patterns at all — it is conveyed separately, as literal operator"
                + " text, in resolvedPatterns; a caller that needs to enforce proximity precisely must read"
                + " resolvedPatterns and apply that logic itself (Hyperscan here only confirms every"
                + " individual part appears somewhere in the message)."
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
        normalizedText = normalizedText.replace("\"\"", "\"");
        try {
            normalizedText = Normalizer2.getNFCInstance().normalize(normalizedText);
        } catch (Exception e) {
            log.debug("ICU4J normalisation skipped: {}", e.getMessage());
        }
        return normalizedText;
    }
}
