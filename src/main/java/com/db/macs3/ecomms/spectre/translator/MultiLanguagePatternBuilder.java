package com.db.macs3.ecomms.spectre.translator;

import com.db.macs3.ecomms.spectre.model.ScriptType;
import com.db.macs3.ecomms.spectre.util.ScriptDetector;
import com.gliwka.hyperscan.wrapper.CompileErrorException;
import com.gliwka.hyperscan.wrapper.Database;
import com.gliwka.hyperscan.wrapper.Expression;
import com.gliwka.hyperscan.wrapper.ExpressionFlag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.function.IntFunction;

/**
 * Builds gap-embedded NEAR and FOLLOWEDBY patterns whose gap is chosen by the operands' script.
 *
 * <p><b>Where it is used.</b> {@link PatternCodeGenerator#generateNear}/{@code generateFollowedBy}
 * call it in two situations: (1) {@code TermSyntaxTranslator#resolveSide} generating the single
 * self-contained pattern it prefers for any side with proximity; (2) a NEAR/FOLLOWEDBY nested inside
 * a multi-operand {@code OR}, which is never decomposed and so has no fallback. The clamp and the
 * adaptive retry below therefore matter for both.
 *
 * <p><b>Word gap or character gap.</b> "Within n words" has no single meaning across scripts.
 * {@link ScriptDetector#detectCombined} classifies the operand pair and {@link ScriptType#isCharBased()}
 * picks the gap:
 * <table border="1">
 *   <caption>Gap by script</caption>
 *   <tr><th>Script(s)</th><th>Gap</th><th>Requested width</th></tr>
 *   <tr><td>Latin (incl. Greek, Cyrillic), Arabic, Hebrew, Devanagari and other Indic, mixed RTL + Latin/Indic</td>
 *       <td>word: {@code (?:\s+\S+){0,n}\s+}</td><td>n words, at most {@link #MAX_WORD_GAP}</td></tr>
 *   <tr><td>CJK (Han), Kana</td><td>character: {@code [\s\S]{0,N}}</td><td>N = n × 3 + n</td></tr>
 *   <tr><td>Hangul</td><td>character</td><td>N = n × 5 + n</td></tr>
 *   <tr><td>Thai, Lao, Myanmar</td><td>character</td><td>N = n × 6 + n</td></tr>
 *   <tr><td>any space-free script mixed with anything else (MIXED_CJK)</td><td>character</td><td>N = n × 4 + n</td></tr>
 *   <tr><td>any other mixture (MIXED)</td><td>character</td><td>N = n × 6 + n</td></tr>
 * </table>
 * The "× avgCharsPerWord + n" formula (the extra {@code n} is a buffer for punctuation, spaces and
 * mixed characters) is what {@link #effectiveGapWidth} computes; N is then clamped to
 * {@link #MAX_CHAR_GAP}. A character gap is used when whitespace cannot reliably separate words;
 * {@code [\s\S]} also matches whitespace, so it is safe for Korean text with or without spaces.
 * Any pair containing a space-free script forces a character gap even if the other operand uses spaces.
 *
 * <p><b>Clamping and adaptive narrowing.</b> Hyperscan rejects a bounded repeat as "Pattern is too
 * large" once its bound gets large, independent of what is repeated, so both gaps are capped
 * ({@link #MAX_WORD_GAP}, {@link #MAX_CHAR_GAP}). A wide OR group next to the gap can lower the
 * safe width further, so when the static cap already applies the width is narrowed term by term by
 * trial-compiling the real pattern against Hyperscan ({@link #charBasedGap}, {@link #wordBasedGap}).
 * Either kind of narrowing puts a precision-loss warning on the {@link BuildResult}; it never
 * fails the term.
 *
 * <p><b>NEAR</b> is bidirectional, {@code (?:A<gap>B|B<gap>A)}. <b>FOLLOWEDBY</b> is
 * directional, {@code A<gap>B}, in logical (stored) order — which is reading order for purely
 * Arabic or Hebrew text. FOLLOWEDBY with mixed RTL and LTR operands adds a warning, because the
 * author's visual notion of "after" may differ from stored order.
 *
 * <p>The pattern's script also gives {@link BuildResult#recommendedHsFlags()}: {@code CASELESS+DOTALL}
 * for Latin, plus {@code UTF8+UCP} for every other script.
 */
public final class MultiLanguagePatternBuilder {

    private static final Logger log = LoggerFactory.getLogger(MultiLanguagePatternBuilder.class);

    /**
     * Hard ceiling on a character gap's width ({@code N} in {@code [\s\S]{0,N}}), calibrated against
     * the real native library rather than reasoned. Under the flags a non-Latin term compiles with
     * (CASELESS + DOTALL + SOM_LEFTMOST + UTF8 + UCP) Hyperscan rejects the gap once {@code N} reaches
     * the low 30s. A bisection sweep over CJK, Thai and Hangul operands (a 2-character and a
     * ~20-character pair), in both the bidirectional NEAR shape and the standalone decomposed-leaf
     * shape, found the largest safe {@code N}:
     * <pre>
     *   CJK-short    (内幕/交易):            safeNear=32  safeLeaf=31
     *   CJK-long     (~20 chars):           safeNear=31  safeLeaf=31
     *   THAI-short   (ราคา/การซื้อขาย):      safeNear=31  safeLeaf=31
     *   HANGUL-short (내부자/거래):          safeNear=31  safeLeaf=31
     * </pre>
     * The boundary barely varies with script or shape, so it looks like a fixed Hyperscan limit on
     * bounded repeats of a wide multi-byte class. 30 is one below the smallest safe value, as a margin.
     * Re-tune if a future Hyperscan version or wider sweep changes this.
     *
     * <p>The cap alone is not enough for OR-heavy operands (for example
     * {@code ((内幕) OR (正常) OR (的) OR (商业)) FOLLOWEDBY{10} ((活动) OR (记录))}): the extra
     * alternation raises state count, so {@link #charBasedGap(ScriptType, int, IntFunction)} narrows
     * the width further by trial compilation.
     */
    static final int MAX_CHAR_GAP = 30;

    /**
     * Flags for the adaptive trial compiles in {@link #compilesUnderHyperscan}, used only to decide
     * whether a candidate gap width is safe — never to compile a pattern that is returned. They
     * match {@code HyperscanCompiler.toExpressionFlags()} for non-Latin content (CASELESS + DOTALL +
     * SOM_LEFTMOST + UTF8 + UCP), which is also what {@link #MAX_CHAR_GAP} was calibrated under. A
     * decomposed leaf really compiles under a lighter set (CASELESS + QUIET), so testing under this
     * one is deliberately conservative.
     */
    private static final EnumSet<ExpressionFlag> TRIAL_COMPILE_FLAGS = EnumSet.of(
            ExpressionFlag.CASELESS, ExpressionFlag.DOTALL,
            ExpressionFlag.SOM_LEFTMOST, ExpressionFlag.UTF8, ExpressionFlag.UCP);

    /**
     * The trial flags for a pattern containing {@code \b}: the same set without UTF8/UCP, which
     * is what an ASCII whole-word term really compiles under (Hyperscan rejects {@code \b} in UCP mode).
     */
    private static final EnumSet<ExpressionFlag> TRIAL_COMPILE_FLAGS_ASCII = EnumSet.of(
            ExpressionFlag.CASELESS, ExpressionFlag.DOTALL, ExpressionFlag.SOM_LEFTMOST);

    private MultiLanguagePatternBuilder() {
    }

    // ── Result records ────────────────────────────────────────────────────

    /**
     * A gap sub-pattern plus a precision-loss warning, non-null when the requested width had to
     * be clamped or narrowed.
     */
    record GapResult(String pattern, String warning) {
        boolean hasWarning() {
            return warning != null && !warning.isBlank();
        }
    }

    /**
     * A generated pattern with the script it was built for.
     *
     * @param pattern            the Hyperscan-compatible PCRE pattern
     * @param scriptType         the detected script combination of the two operands
     * @param recommendedHsFlags flag bitmask (1=CASELESS, 2=DOTALL, 32=UTF8, 64=UCP)
     * @param warning            non-null for a known limitation (mixed RTL+LTR FOLLOWEDBY, or a
     *                           clamped/narrowed gap)
     */
    public record BuildResult(
            String pattern,
            ScriptType scriptType,
            int recommendedHsFlags,
            String warning
    ) {
        /**
         * Convenience constructor for no-warning results.
         */
        public BuildResult(String pattern, ScriptType scriptType, int flags) {
            this(pattern, scriptType, flags, null);
        }

        public boolean hasWarning() {
            return warning != null && !warning.isBlank();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Public API
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Builds a bidirectional NEAR pattern: {@code leftOperand} within n words/characters of
     * {@code rightOperand}, in either order — {@code (?:A<gap>B|B<gap>A)}.
     *
     * <p>The trial shape passed to the gap builder is this same bidirectional pattern with the term's
     * actual (possibly OR-expanded) operand text, so any adaptive narrowing reflects this term's real
     * automaton cost.
     *
     * @param leftOperand  first operand (already escaped for Hyperscan PCRE)
     * @param rightOperand second operand
     * @param maxDistance  the author's distance n, in word gaps
     */
    public static BuildResult buildNear(String leftOperand, String rightOperand, int maxDistance) {
        ScriptType script = ScriptDetector.detectCombined(leftOperand, rightOperand);
        // Trial shape used only to test-compile candidate gap widths against real
        // Hyperscan (see charBasedGap/wordBasedGap) — the real bidirectional NEAR
        // shape, with this term's actual (possibly OR-expanded) operand text baked
        // in, so the adaptive reduction reflects THIS term's real automaton cost,
        // not a generic two-word calibration. Gap-fragment format depends on script
        // (char-based [\s\S]{0,n} vs. word-based (?:\s+\S+){0,n}\s+ — see buildGap).
        IntFunction<String> trial = script.isCharBased()
                ? n -> "(?:%s[\\s\\S]{0,%d}%s|%s[\\s\\S]{0,%d}%s)"
                        .formatted(leftOperand, n, rightOperand, rightOperand, n, leftOperand)
                : n -> "(?:%s(?:\\s+\\S+){0,%d}\\s+%s|%s(?:\\s+\\S+){0,%d}\\s+%s)"
                        .formatted(leftOperand, n, rightOperand, rightOperand, n, leftOperand);
        GapResult gapResult = buildGap(script, maxDistance, trial);
        String gap = gapResult.pattern();

        // Bidirectional: (A gap B) OR (B gap A)
        String pattern = "(?:%s%s%s|%s%s%s)".formatted(leftOperand, gap, rightOperand, rightOperand, gap, leftOperand);

        log.debug("NEAR{} built: script={}, gap={}, pattern={}",
                maxDistance, script, gap, pattern);

        return new BuildResult(pattern, script, script.recommendedHsFlags(), gapResult.warning());
    }

    /**
     * Builds a directional FOLLOWEDBY pattern: {@code leftOperand} before {@code rightOperand} in
     * logical (stored) order, at most n words/characters apart — {@code A<gap>B}.
     *
     * <p>For purely Arabic or Hebrew operands logical order equals reading order, so nothing special
     * is needed. For mixed RTL + LTR operands the result carries a warning, because the author's
     * visual notion of "after" may not match stored order.
     *
     * @param leftOperand  first operand (expected to appear first)
     * @param rightOperand second operand (expected to follow)
     * @param maxDistance  the author's distance n, in word gaps
     */
    public static BuildResult buildFollowedBy(String leftOperand, String rightOperand, int maxDistance) {
        ScriptType script = ScriptDetector.detectCombined(leftOperand, rightOperand);
        // See buildNear for why this trial shape matters — here it's the real
        // directional FOLLOWEDBY shape instead of the bidirectional NEAR one.
        IntFunction<String> trial = script.isCharBased()
                ? n -> "%s[\\s\\S]{0,%d}%s".formatted(leftOperand, n, rightOperand)
                : n -> "%s(?:\\s+\\S+){0,%d}\\s+%s".formatted(leftOperand, n, rightOperand);
        GapResult gapResult = buildGap(script, maxDistance, trial);
        String gap = gapResult.pattern();

        // Directional: A then B
        String pattern = "%s%s%s".formatted(leftOperand, gap, rightOperand);

        // Warn for mixed RTL+LTR FOLLOWEDBY — reading order may differ visually
        String rtlWarning = null;
        if (ScriptDetector.hasRtlComponent(leftOperand, rightOperand)
                && !ScriptDetector.isPurelyRtl(leftOperand, rightOperand)) {
            rtlWarning = "FOLLOWEDBY with mixed RTL+LTR operands matches in logical "
                    + "(stored) byte order, not visual reading order. "
                    + "Verify the intended direction for: '" + leftOperand + "' FOLLOWEDBY '" + rightOperand + "'";
            log.warn(rtlWarning);
        }
        if (gapResult.hasWarning()) {
            log.warn(gapResult.warning());
        }
        String warning = combineWarnings(rtlWarning, gapResult.warning());

        log.debug("FOLLOWEDBY{} built: script={}, gap={}, pattern={}",
                maxDistance, script, gap, pattern);

        return new BuildResult(pattern, script, script.recommendedHsFlags(), warning);
    }

    /**
     * Joins any number of possibly-null/blank warning fragments with a space,
     * skipping the blank ones; returns {@code null} when nothing remains.
     */
    private static String combineWarnings(String... parts) {
        String joined = java.util.Arrays.stream(parts)
                .filter(p -> p != null && !p.isBlank())
                .collect(java.util.stream.Collectors.joining(" "));
        return joined.isBlank() ? null : joined;
    }

    /**
     * Returns the recommended Hyperscan flag bitmask for a given pair of operands.
     * Useful when the caller needs the flags independently of pattern construction.
     *
     * @param leftOperand  first operand
     * @param rightOperand second operand
     * @return Hyperscan flag bitmask (includes UTF8+UCP for non-Latin scripts)
     */
    public static int recommendedHsFlags(String leftOperand, String rightOperand) {
        return ScriptDetector.detectCombined(leftOperand, rightOperand).recommendedHsFlags();
    }

    // ══════════════════════════════════════════════════════════════════════
    // Gap builders
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Builds the gap sub-pattern that sits between the two term operands,
     * with no adaptive-reduction trial-compile — equivalent to
     * {@code buildGap(script, maxDistance, null)}. See
     * {@link #buildGap(ScriptType, int, IntFunction)}.
     */
    static GapResult buildGap(ScriptType script, int maxDistance) {
        return buildGap(script, maxDistance, null);
    }

    /**
     * Builds the gap between two operands, dispatching on {@link ScriptType#isCharBased()} to
     * {@link #charBasedGap(ScriptType, int, IntFunction)} or {@link #wordBasedGap(int, IntFunction)}.
     * A non-null {@code trialPatternForWidth} enables adaptive narrowing against real Hyperscan for
     * both kinds of gap: the word gap needs it too, because the bounded-repeat state limit applies to
     * any {@code {0,n}} whatever it repeats (a plain {@code (a) NEAR{49} (b)} is already rejected).
     */
    static GapResult buildGap(ScriptType script, int maxDistance, IntFunction<String> trialPatternForWidth) {
        if (script.isCharBased()) {
            return charBasedGap(script, maxDistance, trialPatternForWidth);
        }
        return wordBasedGap(maxDistance, trialPatternForWidth);
    }

    /**
     * The unclamped word gap for space-delimited scripts: {@code (?:\s+\S+){0,n}\s+}.
     * <ul>
     *   <li>{@code \s+\S+} — whitespace then one word (a non-whitespace run); with UCP, {@code \S}
     *       also covers Arabic, Hebrew and other non-ASCII letters</li>
     *   <li>{@code {0,n}} — up to n intervening words</li>
     *   <li>the final {@code \s+} — whitespace before the second operand, so the gap always needs at
     *       least one whitespace character (two fragments inside one word can never match)</li>
     * </ul>
     *
     * @param maxDistance maximum number of intervening words
     */
    static String wordBasedGap(int maxDistance) {
        return "(?:\\s+\\S+){0,%d}\\s+".formatted(maxDistance);
    }

    /**
     * Ceiling on the word gap's {@code {0,n}} bound, calibrated like {@link #MAX_CHAR_GAP} by a
     * bisection sweep under CASELESS + DOTALL + SOM_LEFTMOST, for both the bidirectional NEAR shape
     * and the standalone leaf shape: the largest safe {@code n} was 30 in both. That is the same
     * internal bounded-repeat limit that governs the character gap, so it is a property of
     * {@code {0,N}} itself, not of the repeated class. 29 is one below the smallest safe value.
     */
    static final int MAX_WORD_GAP = 29;

    /**
     * Word gap clamped to {@link #MAX_WORD_GAP} and, when a trial shape is supplied, narrowed further
     * by trial compilation — the word-count equivalent of
     * {@link #charBasedGap(ScriptType, int, IntFunction)}, whose Javadoc describes the mechanism.
     *
     * <p>The trial compile only runs once {@code maxDistance} exceeds {@link #MAX_WORD_GAP}; an
     * ordinary small distance never pays for a Hyperscan compile. With a {@code null} trial only the
     * static clamp applies.
     *
     * @param maxDistance          the author's distance n, in words
     * @param trialPatternForWidth builds the full real pattern for a candidate width, or {@code null}
     */
    static GapResult wordBasedGap(int maxDistance, IntFunction<String> trialPatternForWidth) {
        int staticWidth = Math.min(maxDistance, MAX_WORD_GAP);
        boolean staticallyClamped = staticWidth < maxDistance;

        int width = staticWidth;
        boolean confirmedSafe = true;
        if (trialPatternForWidth != null && staticallyClamped) {
            confirmedSafe = compilesUnderHyperscan(trialPatternForWidth.apply(width));
            while (width > 0 && !confirmedSafe) {
                width--;
                confirmedSafe = compilesUnderHyperscan(trialPatternForWidth.apply(width));
            }
        }

        String pattern = wordBasedGap(width);
        String warning = wordGapWarning(maxDistance, staticWidth, width, confirmedSafe);
        return new GapResult(pattern, warning);
    }

    /**
     * The precision-loss warning for {@link #wordBasedGap(int, IntFunction)}: {@code null} when
     * nothing was clamped, otherwise one of two messages — clamped to the static maximum only, or
     * narrowed further by trial compilation. Same structure as {@link #charGapWarning}.
     */
    private static String wordGapWarning(int maxDistance, int staticWidth, int finalWidth, boolean confirmedSafe) {
        if (finalWidth >= maxDistance) {
            return null;
        }
        if (finalWidth == staticWidth) {
            return ("NEAR/FOLLOWEDBY word gap at distance %d would require (?:\\s+\\S+){0,%d}\\s+, which real "
                    + "Hyperscan testing found unsafe to compile (\"Pattern is too large\"); clamped to the "
                    + "calibrated safe maximum (?:\\s+\\S+){0,%d}\\s+. PRECISION LOSS: matches requiring more "
                    + "than %d intervening words between the two operands will be missed. Consider a smaller "
                    + "NEAR/FOLLOWEDBY distance for this term.")
                    .formatted(maxDistance, maxDistance, finalWidth, finalWidth);
        }
        String outcome = confirmedSafe
                ? "adaptively reduced further, by test-compiling this term's actual pattern against real "
                        + "Hyperscan, down to the largest width that compiles: (?:\\s+\\S+){0,%d}\\s+"
                        .formatted(finalWidth)
                : "adaptively reduced all the way down to (?:\\s+\\S+){0,0}\\s+ without finding a width that "
                        + "compiles — this term's OR-branch structure alone (independent of gap width) may "
                        + "still be too large for Hyperscan; the definitive answer comes from the real "
                        + "compile-validation step for this term";
        return ("NEAR/FOLLOWEDBY word gap at distance %d would require (?:\\s+\\S+){0,%d}\\s+; even the "
                + "calibrated safe maximum (?:\\s+\\S+){0,%d}\\s+ still produced \"Pattern is too large\" for "
                + "this term's actual operand structure (e.g. wide OR groups on either side of the gap "
                + "increase compiled state count beyond the generic two-word calibration baseline), so the "
                + "gap was %s. PRECISION LOSS: matches requiring more than %d intervening words between the "
                + "two operands will be missed. Consider a smaller NEAR/FOLLOWEDBY distance, or fewer OR "
                + "alternatives on one side, for this term.")
                .formatted(maxDistance, maxDistance, staticWidth, outcome, finalWidth);
    }

    /**
     * Character gap without adaptive narrowing — {@code charBasedGap(script, maxDistance, null)}.
     * For callers with no operand pattern to trial-compile.
     */
    static GapResult charBasedGap(ScriptType script, int maxDistance) {
        return charBasedGap(script, maxDistance, null);
    }

    /**
     * Character gap {@code [\s\S]{0,N}} for space-free scripts, where the raw width is
     * {@code N = n × avgCharsPerWord + n} (see the class Javadoc), clamped to {@link #MAX_CHAR_GAP}
     * and, when a trial shape is supplied, narrowed further for this term.
     *
     * <p>{@code [\s\S]} matches any character including whitespace. The window is deliberately
     * generous (kanji among kana or Latin, punctuation, furigana): slightly more false positives in
     * exchange for fewer false negatives, which suits alerting.
     *
     * <p><b>Adaptive narrowing.</b> The static cap was calibrated on plain two-word operand pairs. A
     * wide OR group next to the gap can push state count over Hyperscan's limit even at 30. Starting
     * from the static width, this test-compiles {@code trialPatternForWidth.apply(width)} — the real
     * candidate pattern (bidirectional NEAR or directional FOLLOWEDBY, as supplied by
     * {@link #buildNear}/{@link #buildFollowedBy}) — under {@link #TRIAL_COMPILE_FLAGS}, and
     * decrements the width until it compiles or reaches 0. So the gap that ends up in the term's
     * pattern has already been accepted by real Hyperscan in the shape it appears in.
     *
     * <p>The trial only runs when the static clamp actually applies (raw width above
     * {@link #MAX_CHAR_GAP}), because a Hyperscan compile costs tens of milliseconds and ordinary
     * small distances never need it. With a {@code null} trial only the static clamp applies.
     *
     * @param script               resolved script (provides avgCharsPerWord)
     * @param maxDistance          the author's distance n, in words
     * @param trialPatternForWidth builds the full real pattern for a candidate width, or {@code null}
     */
    static GapResult charBasedGap(ScriptType script, int maxDistance, IntFunction<String> trialPatternForWidth) {
        // rawChars = maxDistance words × average chars per word, plus a small
        // additive buffer (+maxDistance) covering punctuation, spaces, and mixed chars
        int rawChars = maxDistance * script.getAvgCharsPerWord() + maxDistance;
        int staticWidth = effectiveGapWidth(script, maxDistance);
        boolean staticallyClamped = staticWidth < rawChars;

        int width = staticWidth;
        boolean confirmedSafe = true;
        if (trialPatternForWidth != null && staticallyClamped) {
            confirmedSafe = compilesUnderHyperscan(trialPatternForWidth.apply(width));
            while (width > 0 && !confirmedSafe) {
                width--;
                confirmedSafe = compilesUnderHyperscan(trialPatternForWidth.apply(width));
            }
        }

        String pattern = "[\\s\\S]{0,%d}".formatted(width);
        String warning = charGapWarning(script, maxDistance, rawChars, staticWidth, width, confirmedSafe);
        return new GapResult(pattern, warning);
    }

    /**
     * Trial-compiles {@code pattern} under {@link #TRIAL_COMPILE_FLAGS}, to decide whether a
     * candidate gap width is safe. Returns {@code true} when the compile fails for a reason unrelated
     * to the pattern (for example the native library being unavailable): pattern generation must not
     * be blocked by an environment problem, and {@code HyperscanCompiler.validate()} remains the
     * authoritative check.
     */
    private static boolean compilesUnderHyperscan(String pattern) {
        // A pattern with a \b belongs to a whole-word (ASCII-only) term, which really compiles
        // without UTF8/UCP — and Hyperscan rejects \b under UCP, so trialling it under the
        // non-Latin flags would fail every width and collapse the gap to nothing.
        EnumSet<ExpressionFlag> flags = pattern.contains("\\b") ? TRIAL_COMPILE_FLAGS_ASCII : TRIAL_COMPILE_FLAGS;
        try {
            Expression expression = new Expression(pattern, flags);
            try (Database db = Database.compile(expression)) {
                return true;
            }
        } catch (CompileErrorException e) {
            return false;
        } catch (RuntimeException | LinkageError e) {
            log.debug("Char-gap trial compile could not run ({}); assuming width is safe and "
                    + "deferring to the real HyperscanCompiler.validate() step.", e.toString());
            return true;
        }
    }

    /**
     * The precision-loss warning for {@link #charBasedGap}: {@code null} when nothing was clamped;
     * otherwise either "clamped to the static {@link #MAX_CHAR_GAP}" or "narrowed further because
     * even that failed to compile for this term's operand structure".
     */
    private static String charGapWarning(ScriptType script, int maxDistance, int rawChars,
                                          int staticWidth, int finalWidth, boolean confirmedSafe) {
        if (finalWidth >= rawChars) {
            return null;
        }
        if (finalWidth == staticWidth) {
            return ("NEAR/FOLLOWEDBY gap for %s at distance %d would require [\\s\\S]{0,%d}, which real "
                    + "Hyperscan testing found unsafe to compile under this script's required UTF8+UCP "
                    + "flags (\"Pattern is too large\"); clamped to the calibrated safe maximum "
                    + "[\\s\\S]{0,%d}. PRECISION LOSS: matches requiring more than %d intervening "
                    + "characters between the two operands will be missed. Consider a smaller "
                    + "NEAR/FOLLOWEDBY distance for this term.")
                    .formatted(script, maxDistance, rawChars, finalWidth, finalWidth);
        }
        String outcome = confirmedSafe
                ? "adaptively reduced further, by test-compiling this term's actual pattern against "
                        + "real Hyperscan, down to the largest width that compiles: [\\s\\S]{0,%d}"
                        .formatted(finalWidth)
                : "adaptively reduced all the way down to [\\s\\S]{0,0} without finding a width that "
                        + "compiles — this term's OR-branch structure alone (independent of gap width) "
                        + "may still be too large for Hyperscan; the definitive answer comes from the "
                        + "real compile-validation step for this term";
        return ("NEAR/FOLLOWEDBY gap for %s at distance %d would require [\\s\\S]{0,%d}; even the "
                + "calibrated safe maximum [\\s\\S]{0,%d} still produced \"Pattern is too large\" for "
                + "this term's actual operand structure (e.g. wide OR groups on either side of the gap "
                + "increase compiled state count beyond the generic two-word calibration baseline), so "
                + "the gap was %s. PRECISION LOSS: matches requiring more than %d intervening characters "
                + "between the two operands will be missed. Consider a smaller NEAR/FOLLOWEDBY distance, "
                + "or fewer OR alternatives on one side, for this term.")
                .formatted(script, maxDistance, rawChars, staticWidth, outcome, finalWidth);
    }

    /**
     * The character-gap width {@link #charBasedGap} uses for {@code script}/{@code maxDistance}
     * before any adaptive narrowing — the single source of truth, shared with
     * {@link PatternComplexityAnalyzer} so its cost estimate matches the generated pattern.
     *
     * @return {@code 0} for a word-based script; otherwise
     *         {@code min(maxDistance × avgCharsPerWord + maxDistance, MAX_CHAR_GAP)}
     */
    static int effectiveGapWidth(ScriptType script, int maxDistance) {
        if (!script.isCharBased()) {
            return 0;
        }
        int rawChars = maxDistance * script.getAvgCharsPerWord() + maxDistance;
        return Math.min(rawChars, MAX_CHAR_GAP);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Convenience overloads — wraps BuildResult for callers that only need
    // the pattern string (existing translator code)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Returns only the NEAR pattern string (no metadata).
     * Convenience wrapper for callers that build the full CompileResponse
     * separately.
     */
    public static String nearPattern(String leftOperand, String rightOperand, int maxDistance) {
        return buildNear(leftOperand, rightOperand, maxDistance).pattern();
    }

    /**
     * Returns only the FOLLOWEDBY pattern string (no metadata).
     */
    public static String followedByPattern(String leftOperand, String rightOperand, int maxDistance) {
        return buildFollowedBy(leftOperand, rightOperand, maxDistance).pattern();
    }
}
