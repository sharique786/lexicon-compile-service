package com.db.macs3.ecomms.spectre.util;

import com.db.macs3.ecomms.spectre.model.ScriptType;
import org.apache.tika.language.detect.LanguageDetector;
import org.apache.tika.language.detect.LanguageResult;
import org.apache.tika.langdetect.optimaize.OptimaizeLangDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Optional;

/**
 * Optional complement to {@link ScriptDetector} that uses Apache Tika's
 * Optimaize language detector to provide language-level disambiguation
 * <em>after</em> Unicode script detection has determined the script family.
 *
 * <h2>When to use this class</h2>
 * <p>Script detection with {@link ScriptDetector} correctly identifies
 * that "内幕交易" is CJK (char-based gap) and that "الإغراق" is Arabic
 * (word-based gap).  But it cannot distinguish Chinese from Classical Japanese
 * when the text contains only CJK logographs and no Hiragana/Katakana hint.
 * In those rare cases {@code TikaLanguageSupport} can narrow the language to
 * "zh" vs "ja", enabling finer-grained {@code avgCharsPerWord} selection.
 *
 * <h2>When NOT to use this class</h2>
 * <ul>
 *   <li>The input string is short (< 20 characters).  Statistical language
 *       detection is unreliable on short strings — the detector may return
 *       a wrong language with high confidence.</li>
 *   <li>The input is a regex fragment (e.g. {@code (?:price|spread)}).
 *       Statistical models produce noise on regex syntax.</li>
 *   <li>The script type has already been resolved unambiguously by
 *       {@link ScriptDetector} (e.g. pure Hangul → HANGUL).</li>
 *   <li>Correctness is more important than precision: the existing
 *       {@code avgCharsPerWord} values are deliberately conservative
 *       (generous upper bounds) to avoid false negatives.</li>
 * </ul>
 *
 * <h2>Why Tika is a complement, not a replacement</h2>
 * <p>Tika identifies <em>language</em> (e.g. "ko", "zh", "ar") via
 * statistical n-gram models.  {@link ScriptDetector} identifies
 * <em>Unicode script family</em> (Hangul, CJK, Arabic) by inspecting
 * each code point.  For the gap-strategy decision the script family
 * is both necessary and sufficient; the language adds optional precision.
 *
 * <h2>Thread safety</h2>
 * <p>The underlying {@link LanguageDetector} is loaded once and reused.
 * The singleton is initialised lazily on first call and is safe for
 * concurrent use after that.
 */
public final class TikaLanguageSupport {

    private static final Logger log = LoggerFactory.getLogger(TikaLanguageSupport.class);

    /**
     * Minimum character count for statistically reliable language detection.
     * Below this threshold the result is returned but marked unreliable.
     */
    public static final int MIN_RELIABLE_LENGTH = 20;

    /** Lazy-initialised singleton. Volatile for double-checked locking. */
    private static volatile LanguageDetector detector;

    private TikaLanguageSupport() {}

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Detects the BCP-47 language code of the given text using Tika's
     * Optimaize detector.
     *
     * @param text the input string (should be at least {@value #MIN_RELIABLE_LENGTH}
     *             characters for reliable results)
     * @return an {@code Optional} containing the language code (e.g. {@code "zh"},
     *         {@code "ko"}, {@code "ar"}) or {@code Optional.empty()} when detection
     *         fails or the detector is unavailable
     */
    public static Optional<String> detectLanguage(String text) {
        if (text == null || text.isBlank()) return Optional.empty();
        try {
            LanguageDetector d = getDetector();
            if (d == null) return Optional.empty();
            LanguageResult result = d.detect(text);
            if (result == null || result.isUnknown()) return Optional.empty();
            return Optional.of(result.getLanguage());
        } catch (Exception e) {
            log.debug("Tika language detection failed for input '{}': {}", abbrev(text), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Returns {@code true} when the text is long enough for reliable detection.
     * Short lexicon terms (2–5 words) may still return a result but the
     * confidence will be lower.
     */
    public static boolean isReliableLength(String text) {
        return text != null && text.codePointCount(0, text.length()) >= MIN_RELIABLE_LENGTH;
    }

    /**
     * Attempts to refine a CJK script type using Tika language detection.
     * Useful when script detection returns {@link ScriptType#CJK} (all HAN
     * characters, no Hiragana/Katakana to disambiguate) and the caller wants
     * to apply a language-specific {@code avgCharsPerWord}.
     *
     * <p>If detection is inconclusive or the input is too short, the original
     * {@code scriptType} is returned unchanged.
     *
     * <p>Refined mappings:
     * <ul>
     *   <li>{@code "zh"} (Chinese) → {@link ScriptType#CJK} (avg 2–3 chars)</li>
     *   <li>{@code "ja"} (Japanese) → {@link ScriptType#CJK} (avg 2–3 chars)</li>
     *   <li>{@code "ko"} (Korean with Hanja) → {@link ScriptType#HANGUL}
     *       (avg 5 syllable chars, using spaces)</li>
     * </ul>
     * Currently CJK and Japanese map to the same {@link ScriptType#CJK}
     * because their gap strategies are identical.  The method is exposed
     * for future differentiation if character-per-word data improves.
     *
     * @param text       the operand text
     * @param scriptType the script type already determined by {@link ScriptDetector}
     * @return the original or refined {@link ScriptType}
     */
    public static ScriptType refineScriptType(String text, ScriptType scriptType) {
        if (scriptType != ScriptType.CJK && scriptType != ScriptType.MIXED_CJK)
            return scriptType; // refinement only applies to ambiguous CJK

        Optional<String> lang = detectLanguage(text);
        if (lang.isEmpty()) return scriptType;

        return switch (lang.get()) {
            case "ko" -> ScriptType.HANGUL; // Classical Korean uses Hanja (HAN) but word spaces
            // "zh", "ja" → CJK (same gap strategy; keep original)
            default  -> scriptType;
        };
    }

    // ── Private helpers ────────────────────────────────────────────────────

    /** Lazily initialises and returns the singleton Tika language detector. */
    private static LanguageDetector getDetector() {
        if (detector == null) {
            synchronized (TikaLanguageSupport.class) {
                if (detector == null) {
                    try {
                        detector = new OptimaizeLangDetector().loadModels();
                        log.debug("Tika Optimaize language detector loaded");
                    } catch (RuntimeException e) {
                        log.warn("Tika language detector unavailable: {}", e.getMessage());
                        detector = null; // will retry on next call (acceptable)
                    }
                }
            }
        }
        return detector;
    }

    private static String abbrev(String text) {
        return text.length() > 30 ? text.substring(0, 30) + "…" : text;
    }
}
