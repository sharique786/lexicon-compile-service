package com.db.macs3.ecomms.spectre.model;

/**
 * The dominant Unicode script family of a text, with the properties the pattern builder needs to
 * choose a NEAR/FOLLOWEDBY gap and the Hyperscan flags.
 *
 * <ul>
 *   <li><b>Word-based</b> ({@link #isSpaceDelimited()}): scripts that separate words with
 *       whitespace — Latin (and Greek, Cyrillic, Armenian, Georgian), Arabic, Hebrew, Indic.
 *       Gap: {@code (?:\s+\S+){0,n}\s+}.</li>
 *   <li><b>Character-based</b> ({@link #isCharBased()}): scripts with no reliable inter-word
 *       whitespace — CJK, Kana, Hangul, Thai/Lao/Myanmar — and every mixture that contains one, plus
 *       the catch-all {@link #MIXED}. Gap: {@code [\s\S]{0,N}} with
 *       {@code N = n × avgCharsPerWord + n}, clamped. It also matches spaced Korean.</li>
 * </ul>
 *
 * <p><b>RTL.</b> Arabic and Hebrew are stored in logical order (the order typed and read), and the
 * regex engine works on stored order, so {@code A FOLLOWEDBY B} means A is stored before B; for
 * purely Arabic or Hebrew text that is always the intended reading order. {@link #isRightToLeft()}
 * lets callers warn when one operand is RTL and the other is LTR.
 */
public enum ScriptType {

    // ── Space-delimited scripts (WORD-BASED gap) ──────────────────────────
    /**
     * English, French, German, Spanish, Russian, Greek, etc.
     */
    LATIN(false, true, 8),
    /**
     * Arabic script — RTL, space-delimited words.
     */
    ARABIC(true, true, 7),
    /**
     * Hebrew script — RTL, space-delimited words.
     */
    HEBREW(true, true, 7),
    /**
     * Devanagari — Hindi, Sanskrit, Marathi, Nepali.
     */
    DEVANAGARI(false, true, 7),

    // ── Character-based scripts (CHAR-BASED gap) ──────────────────────────
    /**
     * Chinese (Simplified + Traditional) and Japanese Kanji.
     * No spaces between characters; a "word" averages 2 characters.
     * avgCharsPerWord = 3 (2 chars + 1 safety buffer per gap unit).
     */
    CJK(false, false, 3),
    /**
     * Japanese Hiragana / Katakana syllabaries.
     * No spaces; a word averages ~3 kana characters.
     */
    KANA(false, false, 3),
    /**
     * Korean Hangul syllable blocks.
     * Formal writing uses spaces between eojeol units, but spaces are
     * frequently omitted in chat / SNS text.  Character-based gap handles
     * both cases safely.  Average eojeol ≈ 4–5 syllable characters.
     */
    HANGUL(false, false, 5),
    /**
     * Thai, Lao, Myanmar — no whitespace between words.
     * Average word ≈ 5–6 characters.
     */
    THAI(false, false, 6),

    // ── Mixed-script combinations ─────────────────────────────────────────
    /**
     * At least one operand is CJK / Kana / Hangul / Thai mixed with any
     * other script.  Character-based gap is mandatory because the
     * space-free side of the pair has no word separators.
     */
    MIXED_CJK(false, false, 4),
    /**
     * RTL scripts (Arabic or Hebrew) mixed with Latin-only scripts.
     * Both sides use spaces, so word-based gap applies.
     */
    MIXED_RTL(false, true, 8),
    /**
     * Any other multi-script combination not covered above.
     * Conservative character-based gap to avoid false negatives.
     */
    MIXED(false, false, 6);

    // ─────────────────────────────────────────────────────────────────────
    private final boolean rightToLeft;
    private final boolean spaceDelimited;
    private final int avgCharsPerWord;

    ScriptType(boolean rightToLeft, boolean spaceDelimited, int avgCharsPerWord) {
        this.rightToLeft = rightToLeft;
        this.spaceDelimited = spaceDelimited;
        this.avgCharsPerWord = avgCharsPerWord;
    }

    /**
     * True for Arabic and Hebrew — visually RTL in display.
     */
    public boolean isRightToLeft() {
        return rightToLeft;
    }

    /**
     * True when words are space-delimited — word-based gap is appropriate.
     */
    public boolean isSpaceDelimited() {
        return spaceDelimited;
    }

    /**
     * Average characters per "word" in this script, used to turn an n-word distance into a character
     * window: {@code n × getAvgCharsPerWord() + n}, clamped (see
     * {@code MultiLanguagePatternBuilder#effectiveGapWidth}).
     */
    public int getAvgCharsPerWord() {
        return avgCharsPerWord;
    }

    /**
     * True when a character-based {@code [\s\S]{0,N}} gap must be used because whitespace cannot
     * reliably separate words.
     */
    public boolean isCharBased() {
        return !spaceDelimited;
    }

    /**
     * The recommended Hyperscan flag bitmask: {@code CASELESS|DOTALL} (3) for {@link #LATIN}, and
     * {@code CASELESS|DOTALL|UTF8|UCP} (99) for every other script.
     *
     * <pre>
     *  1  = HS_FLAG_CASELESS
     *  2  = HS_FLAG_DOTALL
     *  32 = HS_FLAG_UTF8
     *  64 = HS_FLAG_UCP — makes \s, \S and \w honour Unicode properties; without it {@code \S+}
     *                     only matches ASCII non-whitespace and skips Arabic, Hebrew and CJK text
     * </pre>
     */
    public int recommendedHsFlags() {
        // Latin needs only CASELESS(1) + DOTALL(2).
        // Every other script needs UTF8(32) + UCP(64) in addition.
        return (this == LATIN) ? 3 : 99;
    }
}
