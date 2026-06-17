package com.db.macs3.ecomms.spectre.util;

import com.db.macs3.ecomms.spectre.model.ScriptType;
import com.ibm.icu.lang.UScript;

import java.util.EnumSet;
import java.util.Set;

/**
 * Detects the dominant Unicode script family in a text string and maps
 * it to a {@link ScriptType} that drives NEAR/FOLLOWEDBY gap-strategy
 * selection in the pattern builder.
 *
 * <h2>Detection mechanism</h2>
 * <p>Uses ICU4J {@link UScript#getScript(int)} (already a project dependency
 * via {@code com.ibm.icu:icu4j}) rather than Java's built-in
 * {@link Character.UnicodeScript#of(int)}.  ICU4J covers more Unicode scripts,
 * handles supplementary-plane code points correctly, and is already used in
 * the codebase ({@code Normalizer2} in {@code TermSyntaxTranslator}).
 *
 * <h2>Priority resolution (most restrictive wins)</h2>
 * <ol>
 *   <li><b>Pure single-script</b> — checked first so that "내부자" → HANGUL
 *       and "内幕" → CJK, not MIXED_CJK.</li>
 *   <li><b>Mixed space-free</b> — any CJK / Kana / Hangul / Thai forces
 *       MIXED_CJK (char-based gap) because the space-free side of the pair
 *       cannot rely on whitespace separators.</li>
 *   <li><b>Mixed RTL + space-delimited</b> → MIXED_RTL (word-based + UCP).</li>
 *   <li><b>Pure RTL</b> → ARABIC or HEBREW.</li>
 *   <li><b>Pure Latin / Indic / fallback</b> → LATIN / DEVANAGARI / MIXED.</li>
 * </ol>
 *
 * <h2>ASCII and regex metacharacters</h2>
 * <p>Code points ≤ U+007F are skipped entirely so that regex fragments
 * such as {@code \\s+} or {@code \\x{4E00}} embedded in pattern strings
 * do not affect script classification.
 *
 * <h2>Why not Apache Tika?</h2>
 * <p>Tika's {@code LanguageDetector} identifies the <em>language</em>
 * (e.g. "zh", "ja", "ar") using statistical n-gram models.  It is unreliable
 * for short strings (fewer than ~20 characters) and cannot distinguish scripts
 * (e.g. Hangul vs. CJK) better than Unicode code-point inspection.  For our
 * gap-strategy selection Unicode script detection is the correct tool.
 * See {@link TikaLanguageSupport} for an optional complement that adds
 * language-level disambiguation (Chinese vs. Japanese) after script detection.
 */
public final class ScriptDetector {

    private ScriptDetector() {}

    // ── Internal category enum ─────────────────────────────────────────────
    // Coarser than Unicode script; aligned to gap-strategy requirements.

    private enum Category {
        /** CJK logographs: Chinese (Simplified/Traditional), Japanese Kanji. */
        CJK,
        /** Japanese syllabaries: Hiragana, Katakana. */
        KANA,
        /** Korean Hangul syllable blocks. */
        HANGUL,
        /** Arabic script and Arabic-script languages (Farsi, Urdu, etc.). */
        ARABIC,
        /** Hebrew script. */
        HEBREW,
        /** Other RTL scripts: Thaana (Dhivehi), N'Ko, Samaritan, Mandaic. */
        OTHER_RTL,
        /**
         * Space-free Southeast Asian scripts.
         * Thai, Lao, and Myanmar do not use whitespace between words.
         * Note: Tibetan uses tsheg marks between syllables but does use spaces
         *       between words and therefore belongs in INDIC, not here.
         */
        THAI,
        /**
         * Space-delimited Indic scripts.
         * Devanagari (Hindi/Sanskrit/Marathi), Bengali, Gurmukhi, Gujarati,
         * Oriya, Tamil, Telugu, Kannada, Malayalam, Sinhala, and Tibetan.
         * All use whitespace between words → word-based gap applies.
         */
        INDIC,
        /** Latin-family and related European scripts (Greek, Cyrillic, etc.). */
        LATIN
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Detects the script type of a single text operand.
     *
     * @param text lexicon term (may include regex fragments)
     * @return detected {@link ScriptType}; {@link ScriptType#LATIN} when the
     *         text contains only ASCII characters or is null/blank
     */
    public static ScriptType detect(String text) {
        if (text == null || text.isBlank()) return ScriptType.LATIN;
        return resolveType(scanCategories(text));
    }

    /**
     * Determines the combined script type for a NEAR / FOLLOWEDBY operand pair.
     * Applies the most conservative gap strategy when scripts differ —
     * e.g. Latin + Korean → MIXED_CJK (char-based) because the Korean side
     * has no reliable whitespace word separators.
     *
     * @param termA first operand (may be a translated PCRE fragment)
     * @param termB second operand
     * @return combined {@link ScriptType}
     */
    public static ScriptType detectCombined(String termA, String termB) {
        Set<Category> combined = scanCategories(termA);
        combined.addAll(scanCategories(termB));
        return resolveType(combined);
    }

    /**
     * Returns {@code true} when at least one operand contains characters
     * from a right-to-left script (Arabic, Hebrew, or other RTL scripts).
     * Used by {@code MultiLanguagePatternBuilder} to emit a warning for
     * mixed-direction FOLLOWEDBY terms.
     */
    public static boolean hasRtlComponent(String termA, String termB) {
        Set<Category> a = scanCategories(termA);
        Set<Category> b = scanCategories(termB);
        return containsRtl(a) || containsRtl(b);
    }

    /**
     * Returns {@code true} when <em>both</em> operands are exclusively from
     * RTL scripts with no LTR, CJK, Indic, or space-free script content.
     * Pure Arabic+Arabic or Hebrew+Hebrew FOLLOWEDBY requires no direction
     * warning because logical order equals reading order in those scripts.
     */
    public static boolean isPurelyRtl(String termA, String termB) {
        Set<Category> a = scanCategories(termA);
        Set<Category> b = scanCategories(termB);
        return isPurelyRtlSet(a) && isPurelyRtlSet(b);
    }

    // ── Private: category scanning ─────────────────────────────────────────

    /**
     * Walks every Unicode code point, maps each to a {@link Category}, and
     * returns the set of distinct categories found.
     *
     * <h3>ASCII handling — root cause of the "Latin + Korean → HANGUL" bug</h3>
     * <p>ASCII code points (≤ U+007F) fall into two groups:
     * <ul>
     *   <li><b>ASCII letters (a–z, A–Z)</b> — genuine Latin-script content.
     *       Words like {@code "insider"} or {@code "price"} must register as
     *       {@link Category#LATIN} so that
     *       {@code detectCombined("insider", "내부자")} produces
     *       {@code {LATIN, HANGUL}} → {@code MIXED_CJK}, not just
     *       {@code {HANGUL}} → {@code HANGUL}.</li>
     *   <li><b>Everything else ASCII</b> — digits, whitespace, punctuation,
     *       and regex metacharacters ({@code ( ) ? : | + * . \ { }}) — are
     *       ignored so they cannot pollute script detection.</li>
     * </ul>
     *
     * <p>Non-ASCII combining marks (Mn, Mc) and zero-width control characters
     * are also skipped so they do not pollute the result.
     */
    private static Set<Category> scanCategories(String text) {
        Set<Category> found = EnumSet.noneOf(Category.class);
        if (text == null) return found;

        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);

            if (cp <= 0x007F) {
                // ASCII Latin letters (a–z, A–Z) → register as LATIN.
                // All other ASCII (digits, punctuation, regex metacharacters) → skip.
                if ((cp >= 'a' && cp <= 'z') || (cp >= 'A' && cp <= 'Z')) {
                    found.add(Category.LATIN);
                }
                continue;
            }

            if (isInvisible(cp)) continue;  // skip combining marks, ZWJ/ZWNJ, BOM…

            Category cat = toCategory(cp);
            if (cat != null) found.add(cat);
        }
        return found;
    }

    /**
     * Maps a single Unicode code point to its {@link Category} using ICU4J
     * {@link UScript#getScript(int)}.
     *
     * <p><b>Tibetan note:</b> Tibetan text uses tsheg marks between syllables
     * but uses spaces between words, making it functionally word-delimited
     * (like Devanagari) rather than space-free (like Thai/Lao/Myanmar).
     * It is therefore classified as INDIC, not THAI.
     */
    private static Category toCategory(int cp) {
        int script = UScript.getScript(cp);

        // CJK logographic
        if (script == UScript.HAN || script == UScript.BOPOMOFO)
            return Category.CJK;

        // Japanese syllabaries
        if (script == UScript.HIRAGANA || script == UScript.KATAKANA)
            return Category.KANA;

        // Korean
        if (script == UScript.HANGUL)
            return Category.HANGUL;

        // Arabic script (Farsi/Urdu/Pashto also use Arabic script)
        if (script == UScript.ARABIC)
            return Category.ARABIC;

        // Hebrew
        if (script == UScript.HEBREW)
            return Category.HEBREW;

        // Other RTL scripts
        if (script == UScript.THAANA   || script == UScript.NKO
         || script == UScript.SAMARITAN|| script == UScript.MANDAIC)
            return Category.OTHER_RTL;

        // Space-free Southeast Asian (Thai, Lao, Myanmar have NO word spaces)
        if (script == UScript.THAI || script == UScript.LAO || script == UScript.MYANMAR)
            return Category.THAI;

        // Space-delimited Indic scripts (words ARE separated by whitespace)
        // Tibetan: uses tsheg between syllables but spaces between words → INDIC
        if (script == UScript.DEVANAGARI || script == UScript.BENGALI
         || script == UScript.GURMUKHI   || script == UScript.GUJARATI
         || script == UScript.ORIYA      || script == UScript.TAMIL
         || script == UScript.TELUGU     || script == UScript.KANNADA
         || script == UScript.MALAYALAM  || script == UScript.SINHALA
         || script == UScript.TIBETAN)
            return Category.INDIC;

        // Latin-family and closely related European scripts
        if (script == UScript.LATIN    || script == UScript.GREEK
         || script == UScript.CYRILLIC || script == UScript.ARMENIAN
         || script == UScript.GEORGIAN)
            return Category.LATIN;

        return null; // emoji, symbols, private-use, historic scripts → ignore
    }

    // ── Private: resolution ────────────────────────────────────────────────

    /**
     * Maps the accumulated category set to the most appropriate
     * {@link ScriptType} for gap-strategy selection.
     *
     * <h3>KEY INVARIANT — pure cases are checked BEFORE mixed cases</h3>
     * <p>Previously the code returned {@code MIXED_CJK} for ALL inputs that
     * contained any space-free script (CJK/Kana/Hangul/Thai), making the
     * per-script cases permanently unreachable.  The fix is to test for
     * a <em>single</em> script family before testing for mixtures.
     *
     * <h3>Why INDIC does not count as space-free</h3>
     * <p>Devanagari, Tamil, Bengali, etc. use whitespace between words.
     * They are handled like Latin for gap-strategy purposes (word-based gap)
     * and must not trigger the char-based MIXED_CJK path.
     */
    private static ScriptType resolveType(Set<Category> found) {
        if (found.isEmpty()) return ScriptType.LATIN;

        final boolean hasCjk    = found.contains(Category.CJK);
        final boolean hasKana   = found.contains(Category.KANA);
        final boolean hasHangul = found.contains(Category.HANGUL);
        final boolean hasThai   = found.contains(Category.THAI);
        final boolean hasIndic  = found.contains(Category.INDIC);
        final boolean hasArabic = found.contains(Category.ARABIC)
                               || found.contains(Category.OTHER_RTL);
        final boolean hasHebrew = found.contains(Category.HEBREW);
        final boolean hasLatin  = found.contains(Category.LATIN);

        final boolean hasRtl       = hasArabic || hasHebrew;
        // INDIC is NOT included here — Indic scripts use spaces between words
        final boolean hasSpaceFree = hasCjk || hasKana || hasHangul || hasThai;

        // ── Pure single-script cases (MUST come before mixed checks) ──────────
        //
        // Each condition requires one script family AND the absence of all others.
        // "Pure" means no contamination from a second script family.
        //
        // Examples:
        //   "内幕"         → {CJK}     → CJK
        //   "インサイダー" → {KANA}    → KANA
        //   "내부자"       → {HANGUL}  → HANGUL
        //   "ราคา"         → {THAI}    → THAI
        //   "السعر"        → {ARABIC}  → ARABIC
        //   "מידע"         → {HEBREW}  → HEBREW
        //   "मूल्य"        → {INDIC}   → DEVANAGARI
        //   "insider"      → {}        → LATIN (via isEmpty guard above)

        if (hasCjk && !hasKana && !hasHangul && !hasThai
                   && !hasRtl  && !hasLatin  && !hasIndic)
            return ScriptType.CJK;

        if (hasKana && !hasCjk && !hasHangul && !hasThai
                    && !hasRtl && !hasLatin  && !hasIndic)
            return ScriptType.KANA;

        if (hasHangul && !hasCjk && !hasKana && !hasThai
                      && !hasRtl && !hasLatin && !hasIndic)
            return ScriptType.HANGUL;

        if (hasThai && !hasCjk && !hasKana && !hasHangul
                    && !hasRtl && !hasLatin && !hasIndic)
            return ScriptType.THAI;

        if (hasArabic && !hasHebrew && !hasSpaceFree && !hasLatin && !hasIndic)
            return ScriptType.ARABIC;

        if (hasHebrew && !hasArabic && !hasSpaceFree && !hasLatin && !hasIndic)
            return ScriptType.HEBREW;

        // Pure Indic: Hindi, Tamil, Bengali, etc. with no other scripts.
        // Returns DEVANAGARI as the representative word-based Indic type.
        if (hasIndic && !hasSpaceFree && !hasRtl && !hasLatin)
            return ScriptType.DEVANAGARI;

        if (hasLatin && !hasSpaceFree && !hasRtl && !hasIndic)
            return ScriptType.LATIN;

        // ── Mixed-script cases ────────────────────────────────────────────────
        //
        // When a space-free script is present alongside anything else, char-based
        // gap is mandatory (the space-free side cannot rely on whitespace).
        if (hasSpaceFree)
            return ScriptType.MIXED_CJK;

        // RTL + space-delimited (Latin/Indic): both sides use spaces → word-based
        // gap still applies, but UTF8+UCP flags are required.
        if (hasRtl && (hasLatin || hasIndic))
            return ScriptType.MIXED_RTL;

        // Multiple RTL scripts (e.g. Arabic + Hebrew, Arabic + Thaana)
        if (hasRtl)
            return ScriptType.MIXED;

        // Latin + Indic (both space-delimited) → conservative word-based
        return ScriptType.MIXED;
    }

    // ── Private: RTL helpers ──────────────────────────────────────────────

    /** True when the category set contains any RTL script. */
    private static boolean containsRtl(Set<Category> cats) {
        return cats.contains(Category.ARABIC)
            || cats.contains(Category.HEBREW)
            || cats.contains(Category.OTHER_RTL);
    }

    /**
     * True when the category set contains ONLY RTL scripts (Arabic, Hebrew,
     * OTHER_RTL) and NO space-free (THAI), Indic, or Latin scripts.
     * A term like "السعر" is purely RTL; "السعر price" is not.
     */
    private static boolean isPurelyRtlSet(Set<Category> cats) {
        boolean hasAnyRtl = containsRtl(cats);
        boolean hasNonRtl = cats.contains(Category.LATIN)
                         || cats.contains(Category.CJK)
                         || cats.contains(Category.KANA)
                         || cats.contains(Category.HANGUL)
                         || cats.contains(Category.THAI)
                         || cats.contains(Category.INDIC);
        return hasAnyRtl && !hasNonRtl;
    }

    /**
     * Returns {@code true} for Unicode code points that should be ignored
     * during script detection:
     * <ul>
     *   <li>U+200C ZERO WIDTH NON-JOINER (common in Arabic / Persian)</li>
     *   <li>U+200D ZERO WIDTH JOINER</li>
     *   <li>U+200E/200F LTR / RTL marks</li>
     *   <li>U+FEFF BOM / zero-width no-break space</li>
     *   <li>Unicode combining marks (Mn = non-spacing, Mc = spacing-combining)</li>
     * </ul>
     */
    private static boolean isInvisible(int cp) {
        if (cp == 0x200C || cp == 0x200D
         || cp == 0x200E || cp == 0x200F
         || cp == 0xFEFF) return true;
        int type = Character.getType(cp);
        return type == Character.NON_SPACING_MARK
            || type == Character.COMBINING_SPACING_MARK;
    }
}
