package com.db.macs3.ecomms.spectre.translator;

import com.db.macs3.ecomms.spectre.hyperscan.HyperscanCompiler;
import com.ibm.icu.text.Normalizer2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Translates lexicon term descriptions (the operator language) into Hyperscan-validated PCRE patterns.
 *
 * <p><b>Pipeline</b>
 * <pre>
 * raw text → preprocess → Tokenizer → ExpressionParser → Ast (+ warnings)
 *          → per side (required; excluded, for AND NOT):
 *              PatternDecomposer.decompose()  → resolvedText (always) + independent leaf patterns (fallback)
 *              resolveSide()                  → the pattern(s) that become regexPattern:
 *                                               one gap-embedded pattern when safe, else the leaves
 *              HyperscanCompiler.validate()   → the real compiler has the final say
 * </pre>
 * Each stage is a separate, independently testable class. Every pattern in a returned
 * {@link TranslationResult.Success} has already been validated by real Hyperscan, so callers need
 * not validate it again. (The {@code Regex} term type never passes through here; it is validated
 * where it is compiled.)
 *
 * <p><b>One pattern when safe, leaves as the fallback.</b> {@link PatternDecomposer#decompose} runs
 * for every side, and its {@code resolvedText} is always what {@code resolvedPatterns} reports.
 * {@link #resolveSide} then decides separately whether {@code regexPattern} is a single pattern with
 * the gap embedded (built by {@link PatternCodeGenerator}, letting Hyperscan enforce distance and
 * order natively — strictly more precise) or the decomposed leaves. The single pattern is tried
 * first, gated by {@link PatternComplexityAnalyzer#isOverBudget} as a cheap pre-check; whatever
 * that lets through is trial-validated by real Hyperscan, and a rejection for any reason falls back
 * to the leaves. A NEAR/FOLLOWEDBY inside a multi-operand {@code OR} has no leaf form and is always
 * one pattern.
 *
 * <p><b>Whole-word matching and flags.</b> Literals are wrapped in {@code \b} (see
 * {@link PatternCodeGenerator}) unless the term contains non-ASCII text, which needs UCP and so
 * cannot use {@code \b}; that case adds a warning. Leaves that will become native COMBINATION
 * sub-expressions (a fallback term without AND NOT) get only a leading {@code \b}, because Hyperscan
 * rejects a combination sub-expression that ends in an assertion; that adds a warning too.
 *
 * <p><b>Operator precedence</b> (tightest first): atoms (word, {@code "quoted phrase"}, wildcard,
 * parenthesised group) → {@code NEAR{n}}/{@code FOLLOWEDBY{n}} → {@code AND} → {@code AND NOT} →
 * {@code OR}. See {@link ExpressionParser}.
 *
 * <p><b>AND</b> compiles to one pattern (every ordering of the operands joined by
 * {@code [\s\S]*}); <b>AND NOT</b> returns two pattern lists — {@link TranslationResult.Success#hsPatterns()}
 * and {@link TranslationResult.Success#exclusionRegexs()} — and the term matches iff every required
 * entry is found and the exclusion is not.
 *
 * <p><b>Examples</b> (flags omitted; {@code \b} shown as it is emitted)
 * <pre>
 * (crap OR bad) NEAR{3} (bonus OR comp)      — merged into ONE pattern
 *   hsPatterns:      [(?:(?:\bcrap\b|\bbad\b)(?:\s+\S+){0,3}\s+(?:\bbonus\b|\bcomp\b)
 *                     |(?:\bbonus\b|\bcomp\b)(?:\s+\S+){0,3}\s+(?:\bcrap\b|\bbad\b))]
 *   resolvedPattern: "(?:\bcrap\b|\bbad\b) NEAR{3} (?:\bbonus\b|\bcomp\b)"
 *
 * (F) FOLLOWEDBY{1} (((me) OR (cking)))
 *   hsPatterns:      [\bF\b(?:\s+\S+){0,1}\s+(?:\bme\b|\bcking\b)]
 *
 * price AND rigging
 *   hsPatterns:      [(?:\bprice\b[\s\S]*\brigging\b|\brigging\b[\s\S]*\bprice\b)]
 *
 * ((fix) OR (rig)) FOLLOWEDBY{2} (the rate) AND NOT (fed rate move)
 *   hsPatterns:      [(?:\bfix\b|\brig\b)(?:\s+\S+){0,2}\s+\bthe rate\b]
 *   exclusionRegexs: [\bfed rate move\b]      (requiresExclusionCheck = true)
 *
 * ((he?d kill) OR (she?d kill))      — '?' is exactly one character
 *   hsPatterns:      [(?:\bhe\Sd kill\b|\bshe\Sd kill\b)]
 *
 * (check her out) OR (chimp*)        — a wildcard edge gets no \b
 *   hsPatterns:      [(?:\bcheck her out\b|\bchimp\S*)]
 *
 * 내부자 NEAR{3} 거래                 — Hangul: character gap, N = 3 × 5 + 3 = 18, UTF8+UCP, no \b
 *   hsPatterns:      [(?:내부자[\s\S]{0,18}거래|거래[\s\S]{0,18}내부자)]
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
     * Translates one term description into pattern(s), or explains why it cannot be.
     *
     * <p>Checks, in order: null/blank input; a U+FFFD replacement character (the term was mis-encoded
     * before it arrived, so it could never match real text); then tokenizing, parsing, nested-AND-NOT
     * rejection and per-side Hyperscan validation, each of which reports a specific message through
     * {@link TranslationResult.Error}. Whole-word matching is decided once here, up front, for the
     * whole term.
     *
     * @param rawExpression the "Term Description" from the request
     * @return {@link TranslationResult.Success}, or {@link TranslationResult.Error} with an actionable message
     */
    public TranslationResult translate(String rawExpression) {
        if (rawExpression == null || rawExpression.isBlank()) {
            return TranslationResult.error("Term description is null or blank");
        }
        if (rawExpression.indexOf('\uFFFD') >= 0) {
            // U+FFFD is what a UTF-8 decoder substitutes for bytes it could not decode — the term
            // was already corrupted before it reached this service (e.g. a client or CSV saved in
            // a legacy code page, so 'ü' arrived as '?'-like garbage). It would compile to PASS
            // but can never match real text, so fail loudly instead of silently missing words.
            return TranslationResult.error("Term contains the Unicode replacement character U+FFFD, "
                    + "meaning it was mis-encoded before reaching this service. Re-send the request as "
                    + "UTF-8 (JSON body with 'Content-Type: application/json; charset=UTF-8', CSV saved "
                    + "as UTF-8). Term: '" + rawExpression + "'");
        }
        try {
            String preprocessed = preprocess(rawExpression);
            log.debug("Translating: '{}'", preprocessed);

            List<Token> tokens = Tokenizer.tokenize(preprocessed);
            ExpressionParser.ParseResult parseResult = ExpressionParser.parse(tokens, preprocessed);
            Ast ast = parseResult.ast();
            List<String> warnings = new ArrayList<>(parseResult.warnings());

            // Whole-word matching (\b) is decided once, for the whole term, up front: a term with
            // any non-ASCII text gets UCP, which Hyperscan rejects \b under — so it keeps plain
            // substring matching, and says so.
            boolean wordBoundaries = !PatternCodeGenerator.containsNonAscii(ast);
            if (!wordBoundaries && PatternCodeGenerator.hasBoundaryCandidate(ast)) {
                warnings.add("Whole-word matching was not applied: this term contains non-ASCII "
                        + "characters (Unicode mode), where Hyperscan does not support word boundaries, "
                        + "so its words also match inside longer words. Use only ASCII text in a term "
                        + "if whole-word matching is required.");
            }
            ParseContext ctx = new ParseContext(wordBoundaries);

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
                // content would have required. resolvedPatterns is ALWAYS built from this
                // unconditional decomposition, regardless of which pattern(s) actually end up in
                // regexPattern/exclusionRegex — see resolveSide().
                PatternDecomposer.Result requiredResult = PatternDecomposer.decompose(andNot.required(), ctx);
                PatternDecomposer.Result excludedResult = PatternDecomposer.decompose(excludedCombined, ctx);
                int flags = ctx.computeFlags();

                SideResult required
                        = resolveSide(andNot.required(), requiredResult, flags, wordBoundaries,
                        preprocessed, "required", warnings);
                SideResult excluded
                        = resolveSide(excludedCombined, excludedResult, flags, wordBoundaries,
                        preprocessed, "excluded (AND NOT)", warnings);
                warnings.addAll(ctx.getWarnings());

                String resolvedPattern = requiredResult.resolvedText()
                        + " AND NOT (" + excludedResult.resolvedText() + ")";

                log.debug("Translated (AND NOT): '{}' -> required={} excluded={} flags={} warnings={}",
                        rawExpression, required, excluded, flags, warnings.size());

                return new TranslationResult.Success(
                        required.patterns(), flags, true, excluded.patterns(),
                        List.copyOf(warnings), resolvedPattern,
                        required.formulaTemplate(), excluded.formulaTemplate());
            }

            // The root is NOT AndNot — per rejectNestedAndNot() Javadoc, an AndNot node
            // ANYWHERE in this tree would otherwise be silently mishandled: PatternCodeGenerator
            // still has a case for it (so no exception is thrown by code generation itself), but
            // its only effect is a side-channel ctx.setexclusionRegex() call whose result is
            // then discarded entirely, since this branch always sets requiresExclusionCheck=false.
            rejectNestedAndNot(ast, preprocessed);

            PatternDecomposer.Result fullResult = PatternDecomposer.decompose(ast, ctx);
            PatternDecomposer.Result result = fullResult;
            if (wordBoundaries && fullResult.leaves().size() > 1) {
                // These leaves become sub-expressions of a native COMBINATION if the single merged
                // pattern turns out not to be safe — and Hyperscan rejects a combination whose
                // sub-expression ends in \b ("Have unordered match in sub-expressions"). So the
                // fallback leaves are regenerated with a LEADING boundary only; the full-boundary
                // decomposition above is kept for the merged single pattern's resolvedPatterns text.
                result = PatternDecomposer.decompose(ast, new ParseContext(true, false));
            }
            int flags = ctx.computeFlags();
            SideResult required = resolveSide(ast, result, flags, wordBoundaries, preprocessed, "term", warnings);
            warnings.addAll(ctx.getWarnings());

            String resolvedPattern = fullResult.resolvedText();
            if (required.isDecomposed()) {
                // resolvedPatterns must stay byte-identical to the leaves actually used.
                resolvedPattern = result.resolvedText();
                if (wordBoundaries && PatternCodeGenerator.hasBoundaryCandidate(ast)) {
                    warnings.add("Whole-word matching is start-of-word only for this term: it was split "
                            + "into independent leaves combined natively by Hyperscan, which cannot "
                            + "express an end-of-word boundary, so a word may still match as the "
                            + "prefix of a longer word (e.g. 'pd' in 'pdf', but not in 'updates').");
                }
            }

            log.debug("Translated: '{}' -> {} flags={} warnings={}",
                    rawExpression, required, flags, warnings.size());

            return new TranslationResult.Success(
                    required.patterns(), flags, false, null,
                    List.copyOf(warnings), resolvedPattern,
                    required.formulaTemplate(), null);

        } catch (TranslationException te) {
            log.warn("Translation failed for '{}': {}", rawExpression, te.getMessage());
            return TranslationResult.error(te.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error translating '{}': {}", rawExpression, e.getMessage(), e);
            return TranslationResult.error("Unexpected error: " + e.getMessage());
        }
    }

    /**
     * Rejects an {@code AND NOT} anywhere except the root of a term (and not inside another
     * top-level AND NOT's own sides). AND NOT is grammatically legal wherever a parenthesised group is
     * (for example {@code (A AND NOT (B)) NEAR{5} C}), but only a root AND NOT is evaluated: the exclusion
     * of a nested one would be silently discarded and the term would compile as if it were not there.
     *
     * <p>Rewriting the exclusion to the top level automatically was rejected, because it would change
     * what the term means (an exclusion scoped to one operand of a NEAR would become term-wide). The
     * error tells the author how to restructure, e.g. {@code (A NEAR{n} C) AND NOT (B)}. Chained
     * {@code A AND NOT (B) AND NOT (C)} is several excluded operands of ONE node and is unaffected.
     *
     * @throws TranslationException naming the unsupported nesting
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

    // ── Per-side translation: decompose, try to re-merge, then validate ────────

    /**
     * One side's outcome: a list of pattern(s) — one entry for a side with no proximity or one merged
     * into a single pattern, several for decomposed leaves (see {@link TranslationResult}).
     *
     * @param formulaTemplate {@code "{0}"} for a single pattern, otherwise the decomposed leaves'
     *                        {@link PatternDecomposer.Result#formulaTemplate()}
     */
    private record SideResult(List<String> patterns, String formulaTemplate) {
        boolean isDecomposed() {
            return patterns.size() > 1;
        }

        @Override public String toString() {
            return isDecomposed() ? "decomposed(" + patterns.size() + " leaves)" : "'" + patterns.getFirst() + "'";
        }
    }

    /**
     * Decides, for one side, between one self-contained gap-embedded pattern (generated directly from
     * {@code sideAst}) and the gap-less leaves {@code decomposed} already computed. The single pattern
     * is preferred because Hyperscan then enforces distance and order itself instead of degrading to
     * "all parts appear somewhere".
     *
     * <p>It is attempted only when the side split into more than one leaf and
     * {@link PatternComplexityAnalyzer#isOverBudget} does not already predict it too large; the real
     * compiler then validates it, and a rejection (any reason) falls back to the leaves. A side with
     * one leaf has nothing to re-attempt.
     *
     * @param sideAst        this side's AST
     * @param decomposed     this side's decomposition: the fallback leaves and the resolved text
     * @param flags          the term's final Hyperscan flag bitmask
     * @param wordBoundaries whether literals get {@code \b} edges — must match what {@code decomposed}
     *                       was generated with
     * @param originalTerm   the term text, for messages
     * @param sideLabel      "term", "required" or "excluded (AND NOT)", for messages
     * @param warnings       mutable list this method appends to
     */
    private SideResult resolveSide(Ast sideAst, PatternDecomposer.Result decomposed, int flags,
                                    boolean wordBoundaries, String originalTerm, String sideLabel, List<String> warnings) {
        if (decomposed.leaves().size() > 1 && !PatternComplexityAnalyzer.isOverBudget(sideAst)) {
            // Fresh, scratch context: this attempt's own flag/warning discoveries are
            // only real if this path is actually used — discarding them on rejection
            // avoids polluting the term's final flags/warnings with a candidate that
            // was never returned to the caller. The single pattern's operand text is
            // the same content decomposed's leaves already accounted for, so reusing
            // the already-computed `flags` for validation is correct either way.
            ParseContext trialCtx = new ParseContext(wordBoundaries);
            String singlePattern = PatternCodeGenerator.generate(sideAst, trialCtx);
            HyperscanCompiler.ValidationResult validation = compiler.validate(singlePattern, flags);
            if (validation.isPass()) {
                warnings.addAll(trialCtx.getWarnings());
                return new SideResult(List.of(singlePattern), "{0}");
            }
            log.debug("Single gap-embedded pattern for this term's {} expression was rejected by Hyperscan "
                    + "({}) — falling back to {} independent decomposed leaf(ves). Term: '{}'.",
                    sideLabel, validation.errorMessage(), decomposed.leaves().size(), originalTerm);
        }
        return new SideResult(
                validateLeaves(decomposed.leaves(), flags, originalTerm, sideLabel, warnings),
                decomposed.leaves().size() == 1 ? "{0}" : decomposed.formulaTemplate());
    }

    /**
     * Validates every leaf against real Hyperscan with the term's final flags. Used for a side with no
     * proximity (one leaf) and for the decomposed-leaves fallback; a leaf Hyperscan rejects for any
     * reason is a hard failure, since nothing further can be split. When there are several leaves an
     * explanatory warning is added: they match when all are present anywhere, and the proximity between
     * them lives only in {@code resolvedPatterns}.
     */
    private List<String> validateLeaves(List<String> leafPatterns, int flags, String originalTerm,
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
            return List.of(pattern);
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
                "This term's " + sideLabel + " expression contains NEAR/FOLLOWEDBY structure; its single"
                + " gap-embedded pattern was not safe to compile (predicted over budget, or rejected by real"
                + " Hyperscan), so it was split into " + leafPatterns.size() + " independent parts instead"
                + " (each individually Hyperscan-validated)"
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

        return List.copyOf(leafPatterns);
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
