package com.db.macs3.ecomms.spectre.model;

/**
 * Classifies a text segment by its dominant Unicode script family,
 * carrying the properties the pattern builder needs to choose the
 * correct NEAR / FOLLOWEDBY gap strategy.
 *
 * <h2>Gap strategy selection</h2>
 * <ul>
 *   <li><b>Word-based</b> — scripts that use whitespace between words
 *       (Latin, Arabic, Hebrew, Cyrillic, …).
 *       Gap pattern: {@code (?:\\s+\\S+){0,n}\\s+}</li>
 *   <li><b>Char-based</b> — scripts with no reliable inter-word whitespace
 *       (CJK, Thai, …) or where whitespace is inconsistent (Korean).
 *       Gap pattern: {@code [\\s\\S]{0,N}} where {@code N = n × avgCharsPerWord}.
 *       This also handles the with-space case, making it safe for Korean.</li>
 * </ul>
 *
 * <h2>RTL (Arabic / Hebrew)</h2>
 * <p>Arabic and Hebrew are stored in Unicode <em>logical order</em> — the
 * order characters are typed and read, independent of visual rendering.
 * The regex engine operates on stored (logical) order, so
 * {@code A FOLLOWEDBY B} means A sits at a lower byte index than B.
 * For purely Arabic or Hebrew text this is always correct.  The
 * {@link #isRightToLeft()} flag is exposed so callers can emit a warning
 * when one operand is RTL and the other is LTR.
 */
public enum ScriptType {

    // ── Space-delimited scripts (WORD-BASED gap) ──────────────────────────
    /** English, French, German, Spanish, Russian, Greek, etc. */
    LATIN      (false, true,  8),
    /** Arabic script — RTL, space-delimited words. */
    ARABIC     (true,  true,  7),
    /** Hebrew script — RTL, space-delimited words. */
    HEBREW     (true,  true,  7),
    /** Devanagari — Hindi, Sanskrit, Marathi, Nepali. */
    DEVANAGARI (false, true,  7),

    // ── Character-based scripts (CHAR-BASED gap) ──────────────────────────
    /**
     * Chinese (Simplified + Traditional) and Japanese Kanji.
     * No spaces between characters; a "word" averages 2 characters.
     * avgCharsPerWord = 3 (2 chars + 1 safety buffer per gap unit).
     */
    CJK        (false, false, 3),
    /**
     * Japanese Hiragana / Katakana syllabaries.
     * No spaces; a word averages ~3 kana characters.
     */
    KANA       (false, false, 3),
    /**
     * Korean Hangul syllable blocks.
     * Formal writing uses spaces between eojeol units, but spaces are
     * frequently omitted in chat / SNS text.  Character-based gap handles
     * both cases safely.  Average eojeol ≈ 4–5 syllable characters.
     */
    HANGUL     (false, false, 5),
    /**
     * Thai, Lao, Myanmar — no whitespace between words.
     * Average word ≈ 5–6 characters.
     */
    THAI       (false, false, 6),

    // ── Mixed-script combinations ─────────────────────────────────────────
    /**
     * At least one operand is CJK / Kana / Hangul / Thai mixed with any
     * other script.  Character-based gap is mandatory because the
     * space-free side of the pair has no word separators.
     */
    MIXED_CJK  (false, false, 4),
    /**
     * RTL scripts (Arabic or Hebrew) mixed with Latin-only scripts.
     * Both sides use spaces, so word-based gap applies.
     */
    MIXED_RTL  (false, true,  8),
    /**
     * Any other multi-script combination not covered above.
     * Conservative character-based gap to avoid false negatives.
     */
    MIXED      (false, false, 6);

    // ─────────────────────────────────────────────────────────────────────
    private final boolean rightToLeft;
    private final boolean spaceDelimited;
    private final int     avgCharsPerWord;

    ScriptType(boolean rightToLeft, boolean spaceDelimited, int avgCharsPerWord) {
        this.rightToLeft     = rightToLeft;
        this.spaceDelimited  = spaceDelimited;
        this.avgCharsPerWord = avgCharsPerWord;
    }

    /** True for Arabic and Hebrew — visually RTL in display. */
    public boolean isRightToLeft()   { return rightToLeft; }

    /** True when words are space-delimited — word-based gap is appropriate. */
    public boolean isSpaceDelimited(){ return spaceDelimited; }

    /**
     * Average Unicode character count per "word" in this script.
     * Used to convert an n-word gap distance into a character ceiling:
     * {@code maxChars = n * getAvgCharsPerWord()}.
     */
    public int getAvgCharsPerWord()  { return avgCharsPerWord; }

    /**
     * True when a character-based ({@code [\\s\\S]{0,N}}) gap must be used
     * because whitespace cannot reliably separate words.
     */
    public boolean isCharBased()     { return !spaceDelimited; }

    /**
     * Recommended Hyperscan expression flag bitmask for this script type.
     *
     * <pre>
     *  Bit 0 (1)  = HS_FLAG_CASELESS
     *  Bit 1 (2)  = HS_FLAG_DOTALL
     *  Bit 5 (32) = HS_FLAG_UTF8
     *  Bit 6 (64) = HS_FLAG_UCP  — makes \\s / \\S / \\w honour Unicode
     *                               character properties (critical for
     *                               Arabic, Korean, CJK, etc.)
     * </pre>
     *
     * Without UCP, {@code \\S+} only matches ASCII non-whitespace and
     * silently skips Arabic / Hebrew / CJK characters.
     */
    public int recommendedHsFlags() {
        // Latin needs only CASELESS(1) + DOTALL(2).
        // Every other script needs UTF8(32) + UCP(64) in addition.
        return (this == LATIN) ? 3 : 99;
    }
}
