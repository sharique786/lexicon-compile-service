package com.db.macs3.ecomms.spectre.util;

import com.db.macs3.ecomms.spectre.model.ScriptType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comprehensive unit tests for {@link ScriptDetector}.
 *
 * <h2>Test organisation</h2>
 * <ul>
 *   <li>SingleScript   — detect(String) for every supported script family</li>
 *   <li>Combined       — detectCombined(String, String) for all pair combinations</li>
 *   <li>RtlHelpers     — hasRtlComponent() and isPurelyRtl()</li>
 *   <li>IndicScripts   — Devanagari, Tamil, Bengali, etc. (word-based, NOT space-free)</li>
 *   <li>EdgeCases      — null, blank, ASCII-only, emoji, very short strings</li>
 * </ul>
 *
 * <h2>Previously failing tests (fixed by resolveType() rewrite)</h2>
 * <p>The old implementation returned MIXED_CJK for ALL CJK/Kana/Hangul/Thai
 * inputs because the {@code if (hasSpaceFree) return MIXED_CJK} block fired
 * first, making all per-script checks unreachable.  This fix corrects the
 * priority: pure single-script cases are checked BEFORE mixed cases.
 */
@DisplayName("ScriptDetector — complete coverage")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ScriptDetectorTest {

    // ═════════════════════════════════════════════════════════════════════════
    // Single-script detection
    // ═════════════════════════════════════════════════════════════════════════

    @Nested @DisplayName("Single-script detection — detect(String)")
    class SingleScript {

        // ── Latin family ──────────────────────────────────────────────────

        @Test @Order(1) @DisplayName("English → LATIN")
        void english() {
            assertThat(ScriptDetector.detect("insider")).isEqualTo(ScriptType.LATIN);
            assertThat(ScriptDetector.detect("front running")).isEqualTo(ScriptType.LATIN);
            assertThat(ScriptDetector.detect("market manipulation")).isEqualTo(ScriptType.LATIN);
        }

        @Test @Order(2) @DisplayName("French (accented Latin) → LATIN")
        void french() {
            assertThat(ScriptDetector.detect("délit d'initié")).isEqualTo(ScriptType.LATIN);
        }

        @Test @Order(3) @DisplayName("German (Umlaut Latin) → LATIN")
        void german() {
            assertThat(ScriptDetector.detect("Marktmanipulation")).isEqualTo(ScriptType.LATIN);
            assertThat(ScriptDetector.detect("Übernahme")).isEqualTo(ScriptType.LATIN);
        }

        @Test @Order(4) @DisplayName("Russian (Cyrillic) → LATIN (Cyrillic is word-based, same gap)")
        void russian() {
            // Cyrillic is space-delimited like Latin; classified LATIN for gap-strategy purposes
            assertThat(ScriptDetector.detect("инсайдерская торговля")).isEqualTo(ScriptType.LATIN);
        }

        @Test @Order(5) @DisplayName("Greek → LATIN (word-based)")
        void greek() {
            assertThat(ScriptDetector.detect("εσωτερική πληροφορία")).isEqualTo(ScriptType.LATIN);
        }

        // ── Arabic ────────────────────────────────────────────────────────

        @Test @Order(10) @DisplayName("Arabic → ARABIC")
        void arabic() {
            assertThat(ScriptDetector.detect("السعر")).isEqualTo(ScriptType.ARABIC);         // price
            assertThat(ScriptDetector.detect("التلاعب")).isEqualTo(ScriptType.ARABIC);       // manipulation
            assertThat(ScriptDetector.detect("معلومات داخلية")).isEqualTo(ScriptType.ARABIC); // insider info
        }

        @Test @Order(11) @DisplayName("Arabic with diacritics (harakat) → ARABIC (combining marks ignored)")
        void arabicWithDiacritics() {
            // Arabic vocalization marks are combining chars (U+064B–U+065F) — must be filtered
            assertThat(ScriptDetector.detect("السَّعرُ")).isEqualTo(ScriptType.ARABIC);
        }

        @Test @Order(12) @DisplayName("Farsi / Persian (Arabic script) → ARABIC")
        void farsi() {
            assertThat(ScriptDetector.detect("بازار")).isEqualTo(ScriptType.ARABIC); // market in Farsi
        }

        // ── Hebrew ────────────────────────────────────────────────────────

        @Test @Order(20) @DisplayName("Hebrew → HEBREW")
        void hebrew() {
            assertThat(ScriptDetector.detect("מידע")).isEqualTo(ScriptType.HEBREW);       // information
            assertThat(ScriptDetector.detect("פנים")).isEqualTo(ScriptType.HEBREW);       // insider
            assertThat(ScriptDetector.detect("מסחר פנים")).isEqualTo(ScriptType.HEBREW);  // insider trading
        }

        @Test @Order(21) @DisplayName("Hebrew with Niqqud (vowel points) → HEBREW (combining ignored)")
        void hebrewWithNiqqud() {
            assertThat(ScriptDetector.detect("מִידָע")).isEqualTo(ScriptType.HEBREW);
        }

        // ── CJK ───────────────────────────────────────────────────────────
        // BUG THAT WAS FIXED: all of these previously returned MIXED_CJK
        // because resolveType() fired hasSpaceFree → MIXED_CJK immediately.

        @Test @Order(30) @DisplayName("Simplified Chinese (HAN) → CJK [was MIXED_CJK — BUG FIXED]")
        void simplifiedChinese() {
            assertThat(ScriptDetector.detect("内幕")).isEqualTo(ScriptType.CJK);         // insider
            assertThat(ScriptDetector.detect("交易")).isEqualTo(ScriptType.CJK);         // trading
            assertThat(ScriptDetector.detect("内幕交易")).isEqualTo(ScriptType.CJK);     // insider trading
            assertThat(ScriptDetector.detect("价格操纵")).isEqualTo(ScriptType.CJK);     // price manipulation
        }

        @Test @Order(31) @DisplayName("Traditional Chinese (HAN) → CJK [was MIXED_CJK — BUG FIXED]")
        void traditionalChinese() {
            assertThat(ScriptDetector.detect("內幕交易")).isEqualTo(ScriptType.CJK);
            assertThat(ScriptDetector.detect("市場操縱")).isEqualTo(ScriptType.CJK);
        }

        @Test @Order(32) @DisplayName("Japanese Kanji (HAN) → CJK [was MIXED_CJK — BUG FIXED]")
        void japaneseKanji() {
            assertThat(ScriptDetector.detect("取引")).isEqualTo(ScriptType.CJK);   // transaction
            assertThat(ScriptDetector.detect("情報")).isEqualTo(ScriptType.CJK);   // information
            assertThat(ScriptDetector.detect("株価操作")).isEqualTo(ScriptType.CJK); // stock price manipulation
        }

        // ── Kana ──────────────────────────────────────────────────────────

        @Test @Order(40) @DisplayName("Katakana → KANA [was MIXED_CJK — BUG FIXED]")
        void katakana() {
            assertThat(ScriptDetector.detect("インサイダー")).isEqualTo(ScriptType.KANA); // insider
            assertThat(ScriptDetector.detect("マーケット")).isEqualTo(ScriptType.KANA);   // market
        }

        @Test @Order(41) @DisplayName("Hiragana → KANA [was MIXED_CJK — BUG FIXED]")
        void hiragana() {
            assertThat(ScriptDetector.detect("とりひき")).isEqualTo(ScriptType.KANA); // torihiki (trading)
            assertThat(ScriptDetector.detect("じょうほう")).isEqualTo(ScriptType.KANA); // jouhou (information)
        }

        // ── Hangul ────────────────────────────────────────────────────────

        @Test @Order(50) @DisplayName("Korean Hangul → HANGUL [was MIXED_CJK — BUG FIXED]")
        void korean() {
            assertThat(ScriptDetector.detect("내부자")).isEqualTo(ScriptType.HANGUL);  // insider
            assertThat(ScriptDetector.detect("거래")).isEqualTo(ScriptType.HANGUL);    // trading
            assertThat(ScriptDetector.detect("정보")).isEqualTo(ScriptType.HANGUL);    // information
            assertThat(ScriptDetector.detect("내부자거래")).isEqualTo(ScriptType.HANGUL); // insider trading (no space)
            assertThat(ScriptDetector.detect("내부자 거래")).isEqualTo(ScriptType.HANGUL); // with space
        }

        // ── Thai ──────────────────────────────────────────────────────────

        @Test @Order(60) @DisplayName("Thai → THAI [was MIXED_CJK — BUG FIXED]")
        void thai() {
            assertThat(ScriptDetector.detect("ราคา")).isEqualTo(ScriptType.THAI);      // price
            assertThat(ScriptDetector.detect("การซื้อขาย")).isEqualTo(ScriptType.THAI); // trading
            assertThat(ScriptDetector.detect("ข้อมูลภายใน")).isEqualTo(ScriptType.THAI); // insider info
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Indic scripts — formerly broken (grouped with THAI as space-free)
    // ═════════════════════════════════════════════════════════════════════════

    @Nested @DisplayName("Indic scripts — word-based (space-delimited)")
    class IndicScripts {

        @Test @Order(70) @DisplayName("Hindi (Devanagari) → DEVANAGARI")
        void hindi() {
            assertThat(ScriptDetector.detect("मूल्य")).isEqualTo(ScriptType.DEVANAGARI);      // price
            assertThat(ScriptDetector.detect("व्यापार")).isEqualTo(ScriptType.DEVANAGARI);    // trade
            assertThat(ScriptDetector.detect("अंदरूनी व्यापार")).isEqualTo(ScriptType.DEVANAGARI); // insider trading
        }

        @Test @Order(71) @DisplayName("Marathi (Devanagari) → DEVANAGARI")
        void marathi() {
            assertThat(ScriptDetector.detect("बाजार")).isEqualTo(ScriptType.DEVANAGARI); // market
        }

        @Test @Order(72) @DisplayName("Bengali → DEVANAGARI (word-based Indic)")
        void bengali() {
            assertThat(ScriptDetector.detect("মূল্য")).isEqualTo(ScriptType.DEVANAGARI);  // price
            assertThat(ScriptDetector.detect("ব্যবসা")).isEqualTo(ScriptType.DEVANAGARI); // business/trade
        }

        @Test @Order(73) @DisplayName("Tamil → DEVANAGARI (word-based Indic)")
        void tamil() {
            assertThat(ScriptDetector.detect("விலை")).isEqualTo(ScriptType.DEVANAGARI);    // price
            assertThat(ScriptDetector.detect("வணிகம்")).isEqualTo(ScriptType.DEVANAGARI);  // commerce
        }

        @Test @Order(74) @DisplayName("Gujarati → DEVANAGARI")
        void gujarati() {
            assertThat(ScriptDetector.detect("ભાવ")).isEqualTo(ScriptType.DEVANAGARI);     // price
        }

        @Test @Order(75) @DisplayName("Telugu → DEVANAGARI")
        void telugu() {
            assertThat(ScriptDetector.detect("ధర")).isEqualTo(ScriptType.DEVANAGARI);      // price
        }

        @Test @Order(76) @DisplayName("Indic scripts are word-based (NOT char-based like Thai)")
        void indicIsWordBased() {
            // CRITICAL: Devanagari was previously grouped with THAI (space-free)
            // causing it to return MIXED_CJK instead of DEVANAGARI.
            ScriptType hindi = ScriptDetector.detect("मूल्य");
            assertThat(hindi.isCharBased()).as("Hindi should be word-based (space-delimited)")
                    .isFalse();
            assertThat(hindi.isSpaceDelimited()).as("Hindi should use word-based gap").isTrue();
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Combined script detection
    // ═════════════════════════════════════════════════════════════════════════

    @Nested @DisplayName("Combined (two-operand) script detection")
    class Combined {

        // ── Pure-same-script pairs ─────────────────────────────────────────

        @Test @Order(80) @DisplayName("Latin + Latin → LATIN")
        void latinAndLatin() {
            assertThat(ScriptDetector.detectCombined("insider", "trading"))
                    .isEqualTo(ScriptType.LATIN);
        }

        @Test @Order(81) @DisplayName("Arabic + Arabic → ARABIC [was MIXED_CJK — BUG FIXED]")
        void arabicAndArabic() {
            assertThat(ScriptDetector.detectCombined("السعر", "التلاعب"))
                    .isEqualTo(ScriptType.ARABIC);
        }

        @Test @Order(82) @DisplayName("Hebrew + Hebrew → HEBREW")
        void hebrewAndHebrew() {
            assertThat(ScriptDetector.detectCombined("מידע", "פנים"))
                    .isEqualTo(ScriptType.HEBREW);
        }

        @Test @Order(83) @DisplayName("CJK + CJK → CJK [was MIXED_CJK — BUG FIXED]")
        void cjkAndCjk() {
            assertThat(ScriptDetector.detectCombined("内幕", "交易"))
                    .isEqualTo(ScriptType.CJK);
        }

        @Test @Order(84) @DisplayName("Hangul + Hangul → HANGUL [was MIXED_CJK — BUG FIXED]")
        void koreanAndKorean() {
            assertThat(ScriptDetector.detectCombined("내부자", "거래"))
                    .isEqualTo(ScriptType.HANGUL);
        }

        @Test @Order(85) @DisplayName("Kana + Kana → KANA [was MIXED_CJK — BUG FIXED]")
        void kanaAndKana() {
            assertThat(ScriptDetector.detectCombined("インサイダー", "マーケット"))
                    .isEqualTo(ScriptType.KANA);
        }

        @Test @Order(86) @DisplayName("Thai + Thai → THAI [was MIXED_CJK — BUG FIXED]")
        void thaiAndThai() {
            assertThat(ScriptDetector.detectCombined("ราคา", "การซื้อขาย"))
                    .isEqualTo(ScriptType.THAI);
        }

        @Test @Order(87) @DisplayName("Hindi + Hindi → DEVANAGARI")
        void hindiAndHindi() {
            assertThat(ScriptDetector.detectCombined("मूल्य", "व्यापार"))
                    .isEqualTo(ScriptType.DEVANAGARI);
        }

        // ── Mixed: CJK-family wins (char-based mandatory) ──────────────────

        @Test @Order(90) @DisplayName("Latin + Korean → MIXED_CJK (char-based)")
        void latinAndKorean() {
            assertThat(ScriptDetector.detectCombined("insider", "내부자"))
                    .isEqualTo(ScriptType.MIXED_CJK);
        }

        @Test @Order(91) @DisplayName("Latin + Chinese → MIXED_CJK")
        void latinAndChinese() {
            assertThat(ScriptDetector.detectCombined("insider", "内幕"))
                    .isEqualTo(ScriptType.MIXED_CJK);
        }

        @Test @Order(92) @DisplayName("Latin + Japanese Kana → MIXED_CJK")
        void latinAndKana() {
            assertThat(ScriptDetector.detectCombined("insider", "インサイダー"))
                    .isEqualTo(ScriptType.MIXED_CJK);
        }

        @Test @Order(93) @DisplayName("Latin + Thai → MIXED_CJK")
        void latinAndThai() {
            assertThat(ScriptDetector.detectCombined("price", "ราคา"))
                    .isEqualTo(ScriptType.MIXED_CJK);
        }

        @Test @Order(94) @DisplayName("Arabic + Korean → MIXED_CJK (CJK/space-free wins over word-based)")
        void arabicAndKorean() {
            assertThat(ScriptDetector.detectCombined("معلومات", "내부자"))
                    .isEqualTo(ScriptType.MIXED_CJK);
        }

        @Test @Order(95) @DisplayName("Hebrew + Chinese → MIXED_CJK")
        void hebrewAndChinese() {
            assertThat(ScriptDetector.detectCombined("מידע", "内幕"))
                    .isEqualTo(ScriptType.MIXED_CJK);
        }

        @Test @Order(96) @DisplayName("Chinese + Korean → MIXED_CJK (both char-based)")
        void chineseAndKorean() {
            assertThat(ScriptDetector.detectCombined("内幕", "거래"))
                    .isEqualTo(ScriptType.MIXED_CJK);
        }

        @Test @Order(97) @DisplayName("Hindi + Korean → MIXED_CJK (Korean/space-free wins)")
        void hindiAndKorean() {
            assertThat(ScriptDetector.detectCombined("मूल्य", "내부자"))
                    .isEqualTo(ScriptType.MIXED_CJK);
        }

        // ── Mixed: RTL + Latin (word-based) ────────────────────────────────

        @Test @Order(100) @DisplayName("Arabic + Latin → MIXED_RTL (word-based + UCP)")
        void arabicAndLatin() {
            assertThat(ScriptDetector.detectCombined("السعر", "price"))
                    .isEqualTo(ScriptType.MIXED_RTL);
        }

        @Test @Order(101) @DisplayName("Hebrew + Latin → MIXED_RTL")
        void hebrewAndLatin() {
            assertThat(ScriptDetector.detectCombined("מידע", "information"))
                    .isEqualTo(ScriptType.MIXED_RTL);
        }

        @Test @Order(102) @DisplayName("Arabic + Hindi → MIXED_RTL (both word-based)")
        void arabicAndHindi() {
            assertThat(ScriptDetector.detectCombined("السعر", "मूल्य"))
                    .isEqualTo(ScriptType.MIXED_RTL);
        }

        @Test @Order(103) @DisplayName("Hebrew + Cyrillic (Latin-family) → MIXED_RTL")
        void hebrewAndCyrillic() {
            assertThat(ScriptDetector.detectCombined("מסחר", "торговля"))
                    .isEqualTo(ScriptType.MIXED_RTL);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // RTL helper methods
    // ═════════════════════════════════════════════════════════════════════════

    @Nested @DisplayName("RTL helper methods")
    class RtlHelpers {

        @Test @Order(110) @DisplayName("hasRtlComponent: Arabic → true")
        void hasRtl_arabic() {
            assertThat(ScriptDetector.hasRtlComponent("السعر", "price")).isTrue();
        }

        @Test @Order(111) @DisplayName("hasRtlComponent: Hebrew → true")
        void hasRtl_hebrew() {
            assertThat(ScriptDetector.hasRtlComponent("מידע", "information")).isTrue();
        }

        @Test @Order(112) @DisplayName("hasRtlComponent: both Arabic → true")
        void hasRtl_bothArabic() {
            assertThat(ScriptDetector.hasRtlComponent("السعر", "التلاعب")).isTrue();
        }

        @Test @Order(113) @DisplayName("hasRtlComponent: Latin-only → false")
        void hasRtl_latinOnly() {
            assertThat(ScriptDetector.hasRtlComponent("insider", "trading")).isFalse();
        }

        @Test @Order(114) @DisplayName("hasRtlComponent: CJK-only → false")
        void hasRtl_cjkOnly() {
            assertThat(ScriptDetector.hasRtlComponent("内幕", "交易")).isFalse();
        }

        @Test @Order(115) @DisplayName("hasRtlComponent: Korean-only → false")
        void hasRtl_koreanOnly() {
            assertThat(ScriptDetector.hasRtlComponent("내부자", "거래")).isFalse();
        }

        @Test @Order(116) @DisplayName("hasRtlComponent: Hindi-only → false")
        void hasRtl_hindiOnly() {
            assertThat(ScriptDetector.hasRtlComponent("मूल्य", "व्यापार")).isFalse();
        }

        @Test @Order(120) @DisplayName("isPurelyRtl: both Arabic → true")
        void isPurelyRtl_bothArabic() {
            assertThat(ScriptDetector.isPurelyRtl("السعر", "التلاعب")).isTrue();
        }

        @Test @Order(121) @DisplayName("isPurelyRtl: both Hebrew → true")
        void isPurelyRtl_bothHebrew() {
            assertThat(ScriptDetector.isPurelyRtl("מידע", "פנים")).isTrue();
        }

        @Test @Order(122) @DisplayName("isPurelyRtl: Arabic + Latin → false")
        void isPurelyRtl_arabicAndLatin() {
            assertThat(ScriptDetector.isPurelyRtl("السعر", "price")).isFalse();
        }

        @Test @Order(123) @DisplayName("isPurelyRtl: Arabic + Korean → false (Korean is not RTL)")
        void isPurelyRtl_arabicAndKorean() {
            assertThat(ScriptDetector.isPurelyRtl("السعر", "내부자")).isFalse();
        }

        @Test @Order(124) @DisplayName("isPurelyRtl: Arabic + Hindi → false (Indic is not RTL)")
        void isPurelyRtl_arabicAndHindi() {
            // Previously broken: isPurelyRtl() did not check for THAI/INDIC
            assertThat(ScriptDetector.isPurelyRtl("السعر", "मूल्य")).isFalse();
        }

        @Test @Order(125) @DisplayName("isPurelyRtl: Arabic + Thai → false")
        void isPurelyRtl_arabicAndThai() {
            assertThat(ScriptDetector.isPurelyRtl("السعر", "ราคา")).isFalse();
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Edge cases
    // ═════════════════════════════════════════════════════════════════════════

    @Nested @DisplayName("Edge cases")
    class EdgeCases {

        @Test @Order(130) @DisplayName("null → LATIN (safe default)")
        void nullInput() {
            assertThat(ScriptDetector.detect(null)).isEqualTo(ScriptType.LATIN);
            assertThat(ScriptDetector.detectCombined(null, null)).isEqualTo(ScriptType.LATIN);
        }

        @Test @Order(131) @DisplayName("blank → LATIN")
        void blankInput() {
            assertThat(ScriptDetector.detect("")).isEqualTo(ScriptType.LATIN);
            assertThat(ScriptDetector.detect("   ")).isEqualTo(ScriptType.LATIN);
        }

        @Test @Order(132) @DisplayName("ASCII-only (digits, punctuation) → LATIN")
        void asciiOnly() {
            assertThat(ScriptDetector.detect("123")).isEqualTo(ScriptType.LATIN);
            assertThat(ScriptDetector.detect("price123")).isEqualTo(ScriptType.LATIN);
            assertThat(ScriptDetector.detect("$100.00")).isEqualTo(ScriptType.LATIN);
        }

        @Test @Order(133) @DisplayName("Emoji-only → LATIN (safe default, emoji ignored)")
        void emojiOnly() {
            // Emoji code points belong to UScript.COMMON or supplementary planes;
            // they do not map to any script category and are safely ignored.
            ScriptType r = ScriptDetector.detect("💰🤫");
            assertThat(r).isNotNull();
            assertThat(r).isEqualTo(ScriptType.LATIN);
        }

        @Test @Order(134) @DisplayName("Emoji + Korean → HANGUL (emoji ignored, Korean detected)")
        void emojiAndKorean() {
            assertThat(ScriptDetector.detect("💰 내부자")).isEqualTo(ScriptType.HANGUL);
        }

        @Test @Order(135) @DisplayName("Regex fragment with Korean → HANGUL (regex metacharacters ignored)")
        void regexFragmentWithKorean() {
            // PCRE patterns passed to ScriptDetector must not confuse it.
            // "(?:내부자|거래)" contains ASCII metacharacters (?:| ) that are skipped.
            assertThat(ScriptDetector.detect("(?:내부자|거래)")).isEqualTo(ScriptType.HANGUL);
        }

        @Test @Order(136) @DisplayName("Regex fragment with Chinese → CJK")
        void regexFragmentWithChinese() {
            assertThat(ScriptDetector.detect("(?:内幕|交易)")).isEqualTo(ScriptType.CJK);
        }

        @Test @Order(137) @DisplayName("Mixed Japanese Kana + Kanji → MIXED_CJK (common in Japanese text)")
        void mixedKanaAndKanji() {
            // Real Japanese text commonly mixes HIRAGANA/KATAKANA and HAN.
            // "インサイダー取引" (insider trading): KANA + CJK → MIXED_CJK
            assertThat(ScriptDetector.detect("インサイダー取引")).isEqualTo(ScriptType.MIXED_CJK);
        }

        @Test @Order(138) @DisplayName("ZWNJ in Arabic text (combining mark) → filtered, still ARABIC")
        void zwnjInArabic() {
            // U+200C ZERO WIDTH NON-JOINER appears in Farsi/Persian to prevent
            // ligature formation; it must not pollute script detection.
            String arabicWithZwnj = "می‌دانم"; // "I know" in Farsi with ZWNJ
            assertThat(ScriptDetector.detect(arabicWithZwnj)).isEqualTo(ScriptType.ARABIC);
        }

        @Test @Order(139) @DisplayName("Niqqud in Hebrew → filtered, still HEBREW")
        void niqqudInHebrew() {
            // Hebrew vowel points (U+05B0–U+05C7) are combining marks → filtered
            assertThat(ScriptDetector.detect("שָׁלוֹם")).isEqualTo(ScriptType.HEBREW);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Gap-strategy properties on ScriptType
    // ═════════════════════════════════════════════════════════════════════════

    @Nested @DisplayName("ScriptType gap-strategy properties")
    class GapStrategy {

        @Test @Order(150) @DisplayName("LATIN: word-based, not RTL, flags=3")
        void latinProperties() {
            assertThat(ScriptType.LATIN.isSpaceDelimited()).isTrue();
            assertThat(ScriptType.LATIN.isCharBased()).isFalse();
            assertThat(ScriptType.LATIN.isRightToLeft()).isFalse();
            assertThat(ScriptType.LATIN.recommendedHsFlags()).isEqualTo(3); // CASELESS+DOTALL
        }

        @Test @Order(151) @DisplayName("ARABIC: word-based, RTL, flags=99")
        void arabicProperties() {
            assertThat(ScriptType.ARABIC.isSpaceDelimited()).isTrue();
            assertThat(ScriptType.ARABIC.isCharBased()).isFalse();
            assertThat(ScriptType.ARABIC.isRightToLeft()).isTrue();
            assertThat(ScriptType.ARABIC.recommendedHsFlags()).isEqualTo(99);
        }

        @Test @Order(152) @DisplayName("CJK: char-based, not RTL, flags=99")
        void cjkProperties() {
            assertThat(ScriptType.CJK.isCharBased()).isTrue();
            assertThat(ScriptType.CJK.isRightToLeft()).isFalse();
            assertThat(ScriptType.CJK.recommendedHsFlags()).isEqualTo(99);
            assertThat(ScriptType.CJK.getAvgCharsPerWord()).isEqualTo(3);
        }

        @Test @Order(153) @DisplayName("HANGUL: char-based, flags=99, avg=5")
        void hangulProperties() {
            assertThat(ScriptType.HANGUL.isCharBased()).isTrue();
            assertThat(ScriptType.HANGUL.getAvgCharsPerWord()).isEqualTo(5);
            assertThat(ScriptType.HANGUL.recommendedHsFlags()).isEqualTo(99);
        }

        @Test @Order(154) @DisplayName("DEVANAGARI: word-based (NOT char-based), flags=99")
        void devanagariProperties() {
            // CRITICAL: Indic scripts are space-delimited → word-based gap
            assertThat(ScriptType.DEVANAGARI.isCharBased()).isFalse();
            assertThat(ScriptType.DEVANAGARI.isSpaceDelimited()).isTrue();
            assertThat(ScriptType.DEVANAGARI.isRightToLeft()).isFalse();
            assertThat(ScriptType.DEVANAGARI.recommendedHsFlags()).isEqualTo(99);
        }

        @Test @Order(155) @DisplayName("THAI: char-based (no word spaces), flags=99, avg=6")
        void thaiProperties() {
            assertThat(ScriptType.THAI.isCharBased()).isTrue();
            assertThat(ScriptType.THAI.isSpaceDelimited()).isFalse();
            assertThat(ScriptType.THAI.getAvgCharsPerWord()).isEqualTo(6);
        }

        @Test @Order(156) @DisplayName("MIXED_CJK: char-based, flags=99, avg=4")
        void mixedCjkProperties() {
            assertThat(ScriptType.MIXED_CJK.isCharBased()).isTrue();
            assertThat(ScriptType.MIXED_CJK.getAvgCharsPerWord()).isEqualTo(4);
        }

        @Test @Order(157) @DisplayName("MIXED_RTL: word-based (both sides use spaces), flags=99")
        void mixedRtlProperties() {
            assertThat(ScriptType.MIXED_RTL.isCharBased()).isFalse();
            assertThat(ScriptType.MIXED_RTL.isSpaceDelimited()).isTrue();
            assertThat(ScriptType.MIXED_RTL.recommendedHsFlags()).isEqualTo(99);
        }
    }
}
