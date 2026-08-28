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
 * Builds NEAR and FOLLOWEDBY Hyperscan PCRE patterns with full multi-language support.
 *
 * <p><b>Narrowed to one residual caller as of the {@code resolvedPatterns}
 * change — no longer the general NEAR/FOLLOWEDBY path</b>
 * <p>NEAR/FOLLOWEDBY structure now unconditionally splits into independent
 * leaf patterns via {@link PatternDecomposer}, with the gap conveyed as
 * literal {@code NEAR{n}}/{@code FOLLOWEDBY{n}} keyword text in
 * {@code resolvedPatterns} instead of being compiled into a regex fragment
 * — see that class's Javadoc "the one exception". This builder's
 * {@link #buildNear}/{@link #buildFollowedBy} (and therefore the static
 * clamp and adaptive real-Hyperscan retry below) are reachable from exactly
 * ONE remaining path: {@link PatternCodeGenerator#generateNear}/
 * {@link PatternCodeGenerator#generateFollowedBy}, called only when a
 * NEAR/FOLLOWEDBY node is nested inside a multi-operand {@code OR} — a case
 * confirmed to be real, currently-used functionality that cannot be
 * losslessly flattened into a flat leaf list, so it deliberately keeps
 * compiling as a single gap-embedded pattern exactly as before. Both the
 * static clamp and the adaptive retry stay live specifically because this
 * residual path can still, in principle, produce a gap Hyperscan rejects as
 * "too large" — removing either here would silently reintroduce that bug
 * for this narrower case. Do not "finish disconnecting" this class without
 * first re-reading {@code PatternDecomposer}'s Javadoc on why OR-nested
 * proximity is excluded from unconditional splitting.
 *
 * <p><b>Problem with the previous implementation</b>
 * <p>The old builder always used a word-token gap:
 * <pre>{@code (?:\s+\S+){0,n}\s+}</pre>
 * This requires whitespace between terms.  It works for space-delimited
 * languages (English, Arabic, Hebrew) but <em>always fails</em> for CJK
 * (Chinese / Japanese), Thai, and informal Korean where no spaces appear
 * between characters.
 *
 * <p><b>Strategy per script family</b>
 * <table border="1">
 *   <tr><th>Script</th><th>Gap type</th><th>Pattern</th></tr>
 *   <tr><td>Latin (English, etc.)</td><td>Word-based</td>
 *       <td>{@code (?:\\s+\\S+){0,n}\\s+}</td></tr>
 *   <tr><td>Arabic / Hebrew (RTL)</td><td>Word-based + UCP flag</td>
 *       <td>{@code (?:\\s+\\S+){0,n}\\s+} with UTF8+UCP</td></tr>
 *   <tr><td>CJK / Kana (Chinese, Japanese)</td><td>Char-based</td>
 *       <td>{@code [\\s\\S]{0,N}} where N = n × 3</td></tr>
 *   <tr><td>Hangul (Korean)</td><td>Char-based</td>
 *       <td>{@code [\\s\\S]{0,N}} where N = n × 5</td></tr>
 *   <tr><td>Thai / Myanmar</td><td>Char-based</td>
 *       <td>{@code [\\s\\S]{0,N}} where N = n × 6</td></tr>
 *   <tr><td>Mixed CJK + any</td><td>Char-based</td>
 *       <td>{@code [\\s\\S]{0,N}} where N = n × 4</td></tr>
 *   <tr><td>Mixed RTL + Latin</td><td>Word-based</td>
 *       <td>{@code (?:\\s+\\S+){0,n}\\s+} with UTF8+UCP</td></tr>
 * </table>
 *
 * <p>Every char-based {@code N} above is clamped to {@link #MAX_CHAR_GAP} —
 * real Hyperscan rejects {@code [\s\S]{0,N}} under this script family's
 * required UTF8+UCP flags well before the raw formula's {@code N} gets large,
 * independent of which script it is (see {@link #MAX_CHAR_GAP} Javadoc for
 * the calibration data). A clamp emits a warning rather than failing the term.
 *
 * <p><b>NEAR — always bidirectional</b>
 * <p>NEAR{n} means A is within n word/char gaps of B, in either order:
 * <pre>{@code (?:A<gap>B|B<gap>A)}</pre>
 *
 * <p><b>FOLLOWEDBY — directional with RTL awareness</b>
 * <p>FOLLOWEDBY{n} means A appears before B in logical (stored) order:
 * <pre>{@code A<gap>B}</pre>
 * For purely RTL text (Arabic or Hebrew), the regex engine processes bytes
 * in logical order which IS the reading order, so no reversal is needed.
 * When operands mix RTL and LTR scripts, a warning is logged because the
 * "before" relationship may not match the user's visual expectation.
 *
 * <p><b>Integration with existing translator</b>
 * <p>Replace calls in {@code LexiconTermTranslator} (or equivalent) with:
 * <pre>{@code
 * // OLD — hard-coded word-only gap:
 * String gap  = "(?:\\s+\\S+){0,%d}\\s+".formatted(n);
 * String near = "(?:%s%s%s|%s%s%s)".formatted(a, gap, b, b, gap, a);
 *
 * // NEW — language-aware:
 * BuildResult result = MultiLanguagePatternBuilder.buildNear(termA, termB, maxDistance);
 * String near    = result.pattern();
 * int    hsFlags = result.recommendedHsFlags();
 * }</pre>
 */
public final class MultiLanguagePatternBuilder {

    private static final Logger log = LoggerFactory.getLogger(MultiLanguagePatternBuilder.class);

    /**
     * Hard ceiling on a character-based gap's width ({@code N} in
     * {@code [\s\S]{0,N}}) — empirically calibrated, not reasoned from first
     * principles. Real Hyperscan, compiled under exactly the flags
     * {@code HyperscanCompiler.toExpressionFlags()} produces for a non-Latin
     * script (CASELESS + DOTALL + SOM_LEFTMOST + UTF8 + UCP — the same flags
     * {@code TermSyntaxTranslator}'s validation step actually uses), rejects
     * {@code [\s\S]{0,N}} with "Pattern is too large" once {@code N} reaches
     * the low-to-mid 30s — confirmed by a bisection sweep run against the
     * real native library (Docker, {@code linux-x86_64}) across CJK, Thai,
     * and Hangul operands, at both a 2-character pair and a ~20-character
     * pair, in both the bidirectional NEAR shape
     * ({@code (?:A[\s\S]{0,N}B|B[\s\S]{0,N}A)}) and the standalone
     * decomposed-leaf shape ({@code [\s\S]{0,N}B}):
     * <pre>
     *   CJK-short   (内幕/交易):  safeNear=32  safeLeaf=31
     *   CJK-long    (~20 chars):  safeNear=31  safeLeaf=31
     *   THAI-short  (ราคา/การซื้อขาย): safeNear=31  safeLeaf=31
     *   HANGUL-short(내부자/거래):  safeNear=31  safeLeaf=31
     * </pre>
     * The boundary is remarkably script- and shape-independent (31-32
     * everywhere tested), consistent with a fixed internal Hyperscan limit
     * for bounded repeats of a wide/multi-byte character class under UTF8,
     * not something that scales with the specific script's average word
     * length. 30 is one below the smallest measured safe value, as a margin
     * against Hyperscan-version/input variance the sweep didn't cover.
     * Re-tune if a future Hyperscan version or a wider sweep changes this.
     *
     * <p><b>Not sufficient on its own for OR-heavy operands</b> — the sweep
     * behind this number used plain two-word operand pairs on each side of
     * the gap. A real lexicon term's operand is very often itself an OR
     * group ({@code ((内幕) OR (正常) OR (的) OR (商业)) FOLLOWEDBY{10}
     * ((活动) OR (记录))}), which increases compiled automaton state count
     * beyond what this single calibration point covers — real Hyperscan can
     * still reject {@code [\s\S]{0,30}} sitting next to a wide alternation
     * with "Pattern is too large" even though 30 is safe for a plain pair.
     * {@link #charBasedGap(ScriptType, int, IntFunction)} handles this by
     * test-compiling the ACTUAL term shape against the real Hyperscan
     * library and adaptively reducing the width further, term by term, when
     * even this static ceiling isn't safe for it — see that method's Javadoc.
     */
    static final int MAX_CHAR_GAP = 30;

    /**
     * Flags used ONLY for the adaptive trial-compiles in
     * {@link #compilesUnderHyperscan}, deciding whether a candidate
     * character-gap width is safe for a SPECIFIC term's actual operand
     * structure — never used to compile a pattern that is actually returned
     * to a caller. Matches the flag set the {@code GapWidthCalibrationProbe}
     * test used to calibrate {@link #MAX_CHAR_GAP} itself:
     * {@code CASELESS + DOTALL + SOM_LEFTMOST + UTF8 + UCP} — the same set
     * {@code HyperscanCompiler.toExpressionFlags()} produces for non-Latin
     * content, and the flag set a simple (non-decomposed) PASS term's final
     * expression actually compiles under. A decomposition leaf really
     * compiles under a narrower set ({@code CASELESS + QUIET} only, no
     * SOM_LEFTMOST/UTF8/UCP — see {@code HyperscanCompiler.toSubExpressionFlags}),
     * so trial-testing a leaf shape under this richer set is deliberately
     * conservative: fewer flags generally means Hyperscan tracks less
     * per-match state, so a width that compiles here is expected to compile
     * under the leaf's real, lighter flag set too.
     */
    private static final EnumSet<ExpressionFlag> TRIAL_COMPILE_FLAGS = EnumSet.of(
            ExpressionFlag.CASELESS, ExpressionFlag.DOTALL,
            ExpressionFlag.SOM_LEFTMOST, ExpressionFlag.UTF8, ExpressionFlag.UCP);

    private MultiLanguagePatternBuilder() {
    }

    // ── Result records ────────────────────────────────────────────────────

    /**
     * Holds a gap sub-pattern and, when the requested width had to be
     * clamped to {@link #MAX_CHAR_GAP}, a non-null precision-loss warning.
     */
    record GapResult(String pattern, String warning) {
        boolean hasWarning() {
            return warning != null && !warning.isBlank();
        }
    }

    /**
     * Holds the generated PCRE pattern and the recommended Hyperscan flags.
     *
     * @param pattern            the Hyperscan-compatible PCRE pattern
     * @param scriptType         detected script combination
     * @param recommendedHsFlags flag bitmask (1=CASELESS, 2=DOTALL, 32=UTF8, 64=UCP)
     * @param warning            non-null when there is a known limitation to report
     *                           (e.g. mixed RTL+LTR FOLLOWEDBY)
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
     * Builds a bidirectional NEAR pattern.
     *
     * <p>NEAR{n} matches when {@code termA} appears within n words/characters
     * of {@code termB} in either order — i.e.:
     * {@code termA…termB} OR {@code termB…termA}.
     *
     * @param termA       first operand (already escaped for Hyperscan PCRE)
     * @param termB       second operand
     * @param maxDistance maximum distance in "word gaps" (or character multiples for CJK)
     * @return {@link BuildResult} containing the pattern and recommended flags
     */
    public static BuildResult buildNear(String termA, String termB, int maxDistance) {
        ScriptType script = ScriptDetector.detectCombined(termA, termB);
        // Trial shape used only to test-compile candidate gap widths against real
        // Hyperscan (see charBasedGap/wordBasedGap) — the real bidirectional NEAR
        // shape, with this term's actual (possibly OR-expanded) operand text baked
        // in, so the adaptive reduction reflects THIS term's real automaton cost,
        // not a generic two-word calibration. Gap-fragment format depends on script
        // (char-based [\s\S]{0,n} vs. word-based (?:\s+\S+){0,n}\s+ — see buildGap).
        IntFunction<String> trial = script.isCharBased()
                ? n -> "(?:%s[\\s\\S]{0,%d}%s|%s[\\s\\S]{0,%d}%s)".formatted(termA, n, termB, termB, n, termA)
                : n -> "(?:%s(?:\\s+\\S+){0,%d}\\s+%s|%s(?:\\s+\\S+){0,%d}\\s+%s)"
                        .formatted(termA, n, termB, termB, n, termA);
        GapResult gr = buildGap(script, maxDistance, trial);
        String gap = gr.pattern();

        // Bidirectional: (A gap B) OR (B gap A)
        String pattern = "(?:%s%s%s|%s%s%s)".formatted(termA, gap, termB, termB, gap, termA);

        log.debug("NEAR{} built: script={}, gap={}, pattern={}",
                maxDistance, script, gap, pattern);

        return new BuildResult(pattern, script, script.recommendedHsFlags(), gr.warning());
    }

    /**
     * Builds a directional FOLLOWEDBY pattern.
     *
     * <p>FOLLOWEDBY{n} matches when {@code termA} appears before {@code termB}
     * in logical (stored) order, with at most n word gaps between them.
     *
     * <p><b>RTL note</b>
     * <p>For <em>purely</em> Arabic or Hebrew terms, logical order equals
     * reading order — no special handling is needed.
     * For <em>mixed</em> RTL + LTR terms, a warning is included in the
     * result because the user's visual intent (e.g. Arabic word "comes after"
     * an English word) may not align with logical-order matching.
     *
     * @param termA       first operand (expected to appear first in text)
     * @param termB       second operand (expected to follow termA)
     * @param maxDistance maximum gap distance
     * @return {@link BuildResult} containing the pattern and recommended flags
     */
    public static BuildResult buildFollowedBy(String termA, String termB, int maxDistance) {
        ScriptType script = ScriptDetector.detectCombined(termA, termB);
        // See buildNear for why this trial shape matters — here it's the real
        // directional FOLLOWEDBY shape instead of the bidirectional NEAR one.
        IntFunction<String> trial = script.isCharBased()
                ? n -> "%s[\\s\\S]{0,%d}%s".formatted(termA, n, termB)
                : n -> "%s(?:\\s+\\S+){0,%d}\\s+%s".formatted(termA, n, termB);
        GapResult gr = buildGap(script, maxDistance, trial);
        String gap = gr.pattern();

        // Directional: A then B
        String pattern = "%s%s%s".formatted(termA, gap, termB);

        // Warn for mixed RTL+LTR FOLLOWEDBY — reading order may differ visually
        String rtlWarning = null;
        if (ScriptDetector.hasRtlComponent(termA, termB)
                && !ScriptDetector.isPurelyRtl(termA, termB)) {
            rtlWarning = "FOLLOWEDBY with mixed RTL+LTR operands matches in logical "
                    + "(stored) byte order, not visual reading order. "
                    + "Verify the intended direction for: '" + termA + "' FOLLOWEDBY '" + termB + "'";
            log.warn(rtlWarning);
        }
        if (gr.hasWarning()) {
            log.warn(gr.warning());
        }
        String warning = combineWarnings(rtlWarning, gr.warning());

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
     * @param termA first operand
     * @param termB second operand
     * @return Hyperscan flag bitmask (includes UTF8+UCP for non-Latin scripts)
     */
    public static int recommendedHsFlags(String termA, String termB) {
        return ScriptDetector.detectCombined(termA, termB).recommendedHsFlags();
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
     * Builds the gap sub-pattern that sits between the two term operands.
     *
     * <p>Dispatches to either a word-based or character-based gap based on
     * the detected {@link ScriptType}. {@code trialPatternForWidth}, when
     * non-null, is forwarded to
     * {@link #charBasedGap(ScriptType, int, IntFunction)} or
     * {@link #wordBasedGap(int, IntFunction)} respectively for adaptive
     * reduction against real Hyperscan — see those methods' Javadoc.
     *
     * <p><b>Word-based gaps need this too — confirmed, not assumed</b> — an
     * earlier revision of this Javadoc claimed the word-token gap
     * ({@code (?:\s+\S+){0,n}\s+}) never needs adaptive reduction, reasoning
     * that it doesn't carry the char-based gap's wide/multi-byte
     * character-class cost. That reasoning didn't hold up: real Hyperscan
     * testing (raising {@code Tokenizer.MAX_PROXIMITY_DISTANCE} from 9 to
     * 50 surfaced this directly) rejects a plain {@code (a) NEAR{49} (b)}
     * with "Pattern is too large" — the SAME bounded-repeat state-count
     * limit that drives {@link #MAX_CHAR_GAP} turns out to apply to
     * {@code {0,n}} repeats generally, independent of what's inside the
     * repeated group.
     */
    static GapResult buildGap(ScriptType script, int maxDistance, IntFunction<String> trialPatternForWidth) {
        if (script.isCharBased()) {
            return charBasedGap(script, maxDistance, trialPatternForWidth);
        }
        return wordBasedGap(maxDistance, trialPatternForWidth);
    }

    /**
     * Word-based gap for space-delimited languages (Latin, Arabic, Hebrew).
     *
     * <p>Pattern: {@code (?:\\s+\\S+){0,n}\\s+}
     * <ul>
     *   <li>{@code \\s+} — one or more Unicode whitespace characters</li>
     *   <li>{@code \\S+} — one or more Unicode non-whitespace characters
     *       (= one word; with UCP flag this matches Arabic/Hebrew words too)</li>
     *   <li>{@code {0,n}} — up to n intervening words</li>
     *   <li>Final {@code \\s+} — whitespace before the second term</li>
     * </ul>
     *
     * <p><b>Why \\S+ works for Arabic/Hebrew:</b> with the {@code HS_FLAG_UCP}
     * flag, {@code \\S} matches any Unicode non-whitespace code point, including
     * Arabic (U+0600–U+06FF) and Hebrew (U+0590–U+05FF) characters.
     *
     * @param maxDistance maximum number of intervening words
     */
    static String wordBasedGap(int maxDistance) {
        return "(?:\\s+\\S+){0,%d}\\s+".formatted(maxDistance);
    }

    /**
     * Ceiling on the word-based gap's own {@code {0,n}} bound — calibrated
     * the same way as {@link #MAX_CHAR_GAP}, by bisection sweep against the
     * real native Hyperscan library under {@code CASELESS + DOTALL +
     * SOM_LEFTMOST}, for {@code (?:\s+\S+){0,N}\s+} in both the
     * bidirectional NEAR shape ({@code (?:a<gap>b|b<gap>a)}) and the
     * standalone decomposed-leaf shape ({@code <gap>b}):
     * <pre>
     *   NEAR-bidirectional: safeMaxN=30
     *   decomposed-leaf:    safeMaxN=30
     * </pre>
     * Confirms the SAME internal Hyperscan bounded-repeat state-count limit
     * that drives {@link #MAX_CHAR_GAP} (measured 31-32 there) applies here
     * too, independent of what the repeated group actually contains — this
     * is a property of {@code {0,N}} itself, not of the character-class
     * width. 29 is one below the smallest measured safe value (30), as the
     * same margin {@link #MAX_CHAR_GAP} takes against Hyperscan-version/input
     * variance the sweep didn't cover.
     */
    static final int MAX_WORD_GAP = 29;

    /**
     * Word-based gap, adaptively narrowed beyond the static
     * {@link #MAX_WORD_GAP} clamp when even that clamp still produces
     * "Pattern is too large" for THIS term's actual operand structure —
     * the word-based equivalent of
     * {@link #charBasedGap(ScriptType, int, IntFunction)}; see that
     * method's Javadoc for the full mechanism (this one is identical,
     * just narrowing a word-count bound instead of a character-count one).
     *
     * <p>{@code trialPatternForWidth == null} skips adaptive reduction
     * entirely but still applies the static {@link #MAX_WORD_GAP} clamp —
     * equivalent to what {@link #wordBasedGap(int)} would produce if it were
     * itself clamped. This is what {@link #buildGap(ScriptType, int)} (the
     * no-trial 2-arg overload, used by callers with no real pattern to
     * trial-compile) resolves to for a word-based script.
     *
     * <p>Same performance guard as {@link #charBasedGap(ScriptType, int, IntFunction)}:
     * only triggered once {@code maxDistance} already exceeds
     * {@link #MAX_WORD_GAP} — an ordinary, comfortably-small-distance term
     * (the overwhelming majority) never pays for a Hyperscan trial compile.
     *
     * @param maxDistance          maximum "word" distance specified by the lexicon term author
     * @param trialPatternForWidth given a candidate word-count width, builds the full
     *                             real pattern that width would produce for this term —
     *                             or {@code null} to skip adaptive reduction
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
     * Builds the precision-loss warning for {@link #wordBasedGap(int, IntFunction)} —
     * same three-case structure as {@link #charGapWarning}, in terms of words
     * instead of characters.
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
     * Character-based gap for space-free languages (CJK, Thai, Korean).
     *
     * <p>Pattern: {@code [\\s\\S]{0,N}} where {@code N = n × avgCharsPerWord}.
     *
     * <ul>
     *   <li>{@code [\\s\\S]} — any character (whitespace or not); handles
     *       both the space-free case (CJK) and the spaced case (formal Korean)</li>
     *   <li>{@code {0,N}} — bounded window; N is the character-count ceiling</li>
     * </ul>
     *
     * <p>The ceiling {@code N} is intentionally generous to account for
     * mixed-width content (kanji interspersed with kana or Latin), punctuation,
     * and furigana.  The trade-off is slightly more false positives vs.
     * fewer false negatives — acceptable for a surveillance alerting system.
     *
     * <p><b>Clamped to {@link #MAX_CHAR_GAP}</b> — the raw formula below can
     * produce a width real Hyperscan refuses to compile ("Pattern is too
     * large") well before any structural complexity budget would ever flag
     * the term, since a simple two-word NEAR/FOLLOWEDBY has no nesting for
     * {@code PatternComplexityAnalyzer} to penalize. When the raw width
     * exceeds {@link #MAX_CHAR_GAP}, the returned {@link GapResult} carries a
     * non-null warning describing the precision loss instead of silently
     * narrowing the match window.
     *
     * <p>No adaptive reduction beyond the static clamp — equivalent to
     * {@code charBasedGap(script, maxDistance, null)}. Existing callers with
     * no operand context (e.g. {@link PatternComplexityAnalyzer}'s
     * pre-codegen cost estimate, which only has raw un-codegen'd lexicon
     * text to work with, not a real trial-compilable pattern) keep this
     * cheap, Hyperscan-free behaviour. See
     * {@link #charBasedGap(ScriptType, int, IntFunction)} for the adaptive
     * version {@link #buildNear} and {@link #buildFollowedBy} actually use.
     *
     * @param script      the resolved script type (provides avgCharsPerWord)
     * @param maxDistance maximum "word" distance specified by the lexicon term author
     */
    static GapResult charBasedGap(ScriptType script, int maxDistance) {
        return charBasedGap(script, maxDistance, null);
    }

    /**
     * Character-based gap, adaptively narrowed beyond the static
     * {@link #MAX_CHAR_GAP} clamp when even that clamp still produces
     * "Pattern is too large" for THIS term's actual operand structure.
     *
     * <p><b>Why the static clamp alone isn't enough</b> — {@link #MAX_CHAR_GAP}
     * was calibrated against plain two-word operand pairs (see its Javadoc).
     * A real lexicon term's operand is very often a wide OR group instead
     * — {@code ((内幕) OR (正常) OR (的) OR (商业)) FOLLOWEDBY{10} ((活动) OR
     * (记录))} — and the extra alternation increases compiled automaton
     * state count beyond what the calibration point covers, so {@code
     * [\s\S]{0,30}} sitting next to a wide alternation can still be
     * rejected by real Hyperscan even though 30 is safe for a plain pair.
     *
     * <p><b>What this does about it</b> — starting from the same
     * statically-clamped width {@link #charBasedGap(ScriptType, int)} would
     * use, this test-compiles {@code trialPatternForWidth.apply(width)} —
     * the REAL candidate pattern for this term, shape and all (bidirectional
     * NEAR, directional FOLLOWEDBY, or a decomposed leaf's gap-prefix — see
     * {@link #buildNear}, {@link #buildFollowedBy}, and {@code
     * PatternDecomposer} for the three trial shapes actually supplied) —
     * against the real Hyperscan native library (under
     * {@link #TRIAL_COMPILE_FLAGS}). If it fails to compile, the width is
     * decremented and retried, continuing down to a floor of 0, until a
     * width is found that compiles or 0 itself is reached. This keeps this
     * class's own design principle intact one step earlier than usual: by
     * the time the gap this method returns is baked into the term's final
     * pattern, real Hyperscan has already validated the shape it appears
     * in, at this exact width — not just guessed to be safe from a generic
     * calibration point.
     *
     * <p>{@code trialPatternForWidth == null} skips this entirely and
     * behaves exactly like {@link #charBasedGap(ScriptType, int)} — for
     * callers with no real operand pattern to trial-compile.
     *
     * <p><b>Only triggered once the static clamp itself would already
     * apply</b> — when the raw formula width is already {@code <=}
     * {@link #MAX_CHAR_GAP}, this returns immediately with no Hyperscan
     * call at all, exactly like the non-adaptive overload. Real Hyperscan
     * compilation is comparatively expensive (tens of milliseconds), and
     * every ordinary, comfortably-small-distance term would otherwise pay
     * for a trial compile it essentially never needs — this keeps that
     * common case exactly as cheap as before. Only a term whose raw width
     * ALREADY exceeds the calibrated ceiling (the same regime
     * {@link #MAX_CHAR_GAP} itself was introduced for) pays for the extra
     * trial-compile(s), which is also exactly the regime where a term is
     * actually at risk of "Pattern is too large".
     *
     * @param script                the resolved script type (provides avgCharsPerWord)
     * @param maxDistance           maximum "word" distance specified by the lexicon term author
     * @param trialPatternForWidth  given a candidate character width, builds the full
     *                              real pattern that width would produce for this term —
     *                              or {@code null} to skip adaptive reduction
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
     * Attempts to compile {@code pattern} under {@link #TRIAL_COMPILE_FLAGS}
     * against the real Hyperscan native library, purely to decide whether a
     * candidate gap width is safe — see
     * {@link #charBasedGap(ScriptType, int, IntFunction)}. Returns
     * {@code true} on any compile-unrelated failure (e.g. the native
     * library itself being unavailable) rather than blocking pattern
     * generation on an environment problem this method has no way to fix —
     * the REAL, authoritative compile check still happens later in
     * {@code HyperscanCompiler.validate()}, per this class's own design
     * principle that only the real Hyperscan compiler has final say.
     */
    private static boolean compilesUnderHyperscan(String pattern) {
        try {
            Expression expression = new Expression(pattern, TRIAL_COMPILE_FLAGS);
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
     * Builds the precision-loss warning for {@link #charBasedGap}, covering
     * three cases: no clamp needed at all ({@code null}); clamped to the
     * static {@link #MAX_CHAR_GAP} ceiling but no further reduction needed;
     * and adaptively reduced further still, because even that ceiling
     * failed to compile for this term's real operand structure.
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
     * The clamped character-gap width {@link #charBasedGap} will actually
     * use for {@code script}/{@code maxDistance} — the single source of
     * truth for this calculation, shared with {@link PatternComplexityAnalyzer}
     * so its cost estimate matches the pattern that will really be generated.
     *
     * @return {@code 0} for a word-based script (no character-width concept
     * applies); otherwise {@code min(maxDistance * avgCharsPerWord +
     * maxDistance, MAX_CHAR_GAP)}
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
    public static String nearPattern(String termA, String termB, int maxDistance) {
        return buildNear(termA, termB, maxDistance).pattern();
    }

    /**
     * Returns only the FOLLOWEDBY pattern string (no metadata).
     */
    public static String followedByPattern(String termA, String termB, int maxDistance) {
        return buildFollowedBy(termA, termB, maxDistance).pattern();
    }
}
