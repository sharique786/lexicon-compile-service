package com.db.macs3.ecomms.spectre.translator;

import com.db.macs3.ecomms.spectre.model.ScriptType;
import com.db.macs3.ecomms.spectre.util.ScriptDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds NEAR and FOLLOWEDBY Hyperscan PCRE patterns with full multi-language support.
 *
 * <h2>Problem with the previous implementation</h2>
 * <p>The old builder always used a word-token gap:
 * <pre>{@code (?:\s+\S+){0,n}\s+}</pre>
 * This requires whitespace between terms.  It works for space-delimited
 * languages (English, Arabic, Hebrew) but <em>always fails</em> for CJK
 * (Chinese / Japanese), Thai, and informal Korean where no spaces appear
 * between characters.
 *
 * <h2>Strategy per script family</h2>
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
 * <h2>NEAR — always bidirectional</h2>
 * <p>NEAR{n} means A is within n word/char gaps of B, in either order:
 * <pre>{@code (?:A<gap>B|B<gap>A)}</pre>
 *
 * <h2>FOLLOWEDBY — directional with RTL awareness</h2>
 * <p>FOLLOWEDBY{n} means A appears before B in logical (stored) order:
 * <pre>{@code A<gap>B}</pre>
 * For purely RTL text (Arabic or Hebrew), the regex engine processes bytes
 * in logical order which IS the reading order, so no reversal is needed.
 * When operands mix RTL and LTR scripts, a warning is logged because the
 * "before" relationship may not match the user's visual expectation.
 *
 * <h2>Integration with existing translator</h2>
 * <p>Replace calls in {@code LexiconTermTranslator} (or equivalent) with:
 * <pre>{@code
 * // OLD — hard-coded word-only gap:
 * String gap  = "(?:\\s+\\S+){0,%d}\\s+".formatted(n);
 * String near = "(?:%s%s%s|%s%s%s)".formatted(a, gap, b, b, gap, a);
 *
 * // NEW — language-aware:
 * BuildResult result = MultiLanguagePatternBuilder.buildNear(a, b, n);
 * String near    = result.pattern();
 * int    hsFlags = result.recommendedHsFlags();
 * }</pre>
 */
public final class MultiLanguagePatternBuilder {

    private static final Logger log = LoggerFactory.getLogger(MultiLanguagePatternBuilder.class);

    private MultiLanguagePatternBuilder() {}

    // ── Result record ─────────────────────────────────────────────────────

    /**
     * Holds the generated PCRE pattern and the recommended Hyperscan flags.
     *
     * @param pattern           the Hyperscan-compatible PCRE pattern
     * @param scriptType        detected script combination
     * @param recommendedHsFlags flag bitmask (1=CASELESS, 2=DOTALL, 32=UTF8, 64=UCP)
     * @param warning           non-null when there is a known limitation to report
     *                          (e.g. mixed RTL+LTR FOLLOWEDBY)
     */
    public record BuildResult(
            String     pattern,
            ScriptType scriptType,
            int        recommendedHsFlags,
            String     warning
    ) {
        /** Convenience constructor for no-warning results. */
        public BuildResult(String pattern, ScriptType scriptType, int flags) {
            this(pattern, scriptType, flags, null);
        }
        public boolean hasWarning() { return warning != null && !warning.isBlank(); }
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
     * @param termA first operand (already escaped for Hyperscan PCRE)
     * @param termB second operand
     * @param n     maximum distance in "word gaps" (or character multiples for CJK)
     * @return {@link BuildResult} containing the pattern and recommended flags
     */
    public static BuildResult buildNear(String termA, String termB, int n) {
        ScriptType script = ScriptDetector.detectCombined(termA, termB);
        String gap = buildGap(script, n);

        // Bidirectional: (A gap B) OR (B gap A)
        String pattern = "(?:%s%s%s|%s%s%s)".formatted(termA, gap, termB, termB, gap, termA);

        log.debug("NEAR{} built: script={}, gap={}, pattern={}",
                n, script, gap, pattern);

        return new BuildResult(pattern, script, script.recommendedHsFlags());
    }

    /**
     * Builds a directional FOLLOWEDBY pattern.
     *
     * <p>FOLLOWEDBY{n} matches when {@code termA} appears before {@code termB}
     * in logical (stored) order, with at most n word gaps between them.
     *
     * <h3>RTL note</h3>
     * <p>For <em>purely</em> Arabic or Hebrew terms, logical order equals
     * reading order — no special handling is needed.
     * For <em>mixed</em> RTL + LTR terms, a warning is included in the
     * result because the user's visual intent (e.g. Arabic word "comes after"
     * an English word) may not align with logical-order matching.
     *
     * @param termA first operand (expected to appear first in text)
     * @param termB second operand (expected to follow termA)
     * @param n     maximum gap distance
     * @return {@link BuildResult} containing the pattern and recommended flags
     */
    public static BuildResult buildFollowedBy(String termA, String termB, int n) {
        ScriptType script = ScriptDetector.detectCombined(termA, termB);
        String gap = buildGap(script, n);

        // Directional: A then B
        String pattern = "%s%s%s".formatted(termA, gap, termB);

        // Warn for mixed RTL+LTR FOLLOWEDBY — reading order may differ visually
        String warning = null;
        if (ScriptDetector.hasRtlComponent(termA, termB)
                && !ScriptDetector.isPurelyRtl(termA, termB)) {
            warning = "FOLLOWEDBY with mixed RTL+LTR operands matches in logical "
                    + "(stored) byte order, not visual reading order. "
                    + "Verify the intended direction for: '" + termA + "' FOLLOWEDBY '" + termB + "'";
            log.warn(warning);
        }

        log.debug("FOLLOWEDBY{} built: script={}, gap={}, pattern={}",
                n, script, gap, pattern);

        return new BuildResult(pattern, script, script.recommendedHsFlags(), warning);
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
    static String buildGap(ScriptType script, int n) {
        if (script.isCharBased()) {
            return charBasedGap(script, n);
        }
        return wordBasedGap(n);
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
     * @param n maximum number of intervening words
     */
    static String wordBasedGap(int n) {
        return "(?:\\s+\\S+){0,%d}\\s+".formatted(n);
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
     * @param script   the resolved script type (provides avgCharsPerWord)
     * @param n        maximum "word" distance specified by the lexicon term author
     */
    static String charBasedGap(ScriptType script, int n) {
        // N = n words × average chars per word
        // A small additive buffer (+n) covers punctuation, spaces, and mixed chars
        int maxChars = n * script.getAvgCharsPerWord() + n;
        return "[\\s\\S]{0,%d}".formatted(maxChars);
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
    public static String nearPattern(String termA, String termB, int n) {
        return buildNear(termA, termB, n).pattern();
    }

    /**
     * Returns only the FOLLOWEDBY pattern string (no metadata).
     */
    public static String followedByPattern(String termA, String termB, int n) {
        return buildFollowedBy(termA, termB, n).pattern();
    }
}
