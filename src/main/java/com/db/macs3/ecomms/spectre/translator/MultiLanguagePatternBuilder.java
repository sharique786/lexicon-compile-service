package com.db.macs3.ecomms.spectre.translator;

import com.db.macs3.ecomms.spectre.model.ScriptType;
import com.db.macs3.ecomms.spectre.util.ScriptDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds NEAR and FOLLOWEDBY Hyperscan PCRE patterns with full multi-language support.
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
     */
    static final int MAX_CHAR_GAP = 30;

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
        GapResult gr = buildGap(script, maxDistance);
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
        GapResult gr = buildGap(script, maxDistance);
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
     * Builds the gap sub-pattern that sits between the two term operands.
     *
     * <p>Dispatches to either a word-based or character-based gap based on
     * the detected {@link ScriptType}.
     */
    static GapResult buildGap(ScriptType script, int maxDistance) {
        if (script.isCharBased()) {
            return charBasedGap(script, maxDistance);
        }
        return new GapResult(wordBasedGap(maxDistance), null);
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
     * @param script      the resolved script type (provides avgCharsPerWord)
     * @param maxDistance maximum "word" distance specified by the lexicon term author
     */
    static GapResult charBasedGap(ScriptType script, int maxDistance) {
        // rawChars = maxDistance words × average chars per word, plus a small
        // additive buffer (+maxDistance) covering punctuation, spaces, and mixed chars
        int rawChars = maxDistance * script.getAvgCharsPerWord() + maxDistance;
        int actualChars = effectiveGapWidth(script, maxDistance);
        String pattern = "[\\s\\S]{0,%d}".formatted(actualChars);
        String warning = (actualChars < rawChars)
                ? ("NEAR/FOLLOWEDBY gap for %s at distance %d would require [\\s\\S]{0,%d}, which real "
                        + "Hyperscan testing found unsafe to compile under this script's required UTF8+UCP "
                        + "flags (\"Pattern is too large\"); clamped to the calibrated safe maximum "
                        + "[\\s\\S]{0,%d}. PRECISION LOSS: matches requiring more than %d intervening "
                        + "characters between the two operands will be missed. Consider a smaller "
                        + "NEAR/FOLLOWEDBY distance for this term.")
                        .formatted(script, maxDistance, rawChars, actualChars, actualChars)
                : null;
        return new GapResult(pattern, warning);
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
