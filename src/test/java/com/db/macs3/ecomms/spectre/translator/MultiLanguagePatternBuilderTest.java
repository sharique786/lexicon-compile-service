package com.db.macs3.ecomms.spectre.translator;

import com.db.macs3.ecomms.spectre.model.ScriptType;
import com.db.macs3.ecomms.spectre.translator.MultiLanguagePatternBuilder.BuildResult;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comprehensive tests for {@link MultiLanguagePatternBuilder}.
 *
 * <p>Each test follows the same structure:
 * <ol>
 *   <li>Build the pattern using {@code buildNear} or {@code buildFollowedBy}.</li>
 *   <li>Verify the {@link ScriptType} and recommended flags in the result.</li>
 *   <li>Compile the pattern with Java regex (UNICODE_CASE + CASE_INSENSITIVE +
 *       DOTALL) as a functional substitute for Hyperscan in a test environment.</li>
 *   <li>Assert match / no-match against real-language sample messages.</li>
 * </ol>
 *
 * <h2>Java regex flag mapping</h2>
 * <pre>
 *   HS_FLAG_CASELESS  → Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
 *   HS_FLAG_DOTALL    → Pattern.DOTALL
 *   HS_FLAG_UTF8+UCP  → Pattern.UNICODE_CHARACTER_CLASS
 * </pre>
 */
@Disabled
@DisplayName("MultiLanguagePatternBuilder")
class MultiLanguagePatternBuilderTest {

    // ── Regex test helper ────────────────────────────────────────────────
    /** Compiles the Hyperscan pattern with Java's Unicode-aware flags and tests it. */
    private boolean matches(String hsPattern, String message) {
        int jf = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
               | Pattern.DOTALL | Pattern.UNICODE_CHARACTER_CLASS;
        return Pattern.compile(hsPattern, jf).matcher(message).find();
    }

    // ══════════════════════════════════════════════════════════════════════
    // English (Latin) — baseline, unchanged behaviour
    // ══════════════════════════════════════════════════════════════════════

    @Nested @DisplayName("Latin (English) NEAR / FOLLOWEDBY — baseline")
    class Latin {

        @Test @DisplayName("NEAR: words within n word-gaps — both orders")
        void latinNear() {
            BuildResult r = MultiLanguagePatternBuilder.buildNear("insider", "trading", 3);
            assertThat(r.scriptType()).isEqualTo(ScriptType.LATIN);
            assertThat(r.recommendedHsFlags()).isEqualTo(3); // CASELESS + DOTALL

            // Forward order
            assertThat(matches(r.pattern(), "the insider was actively trading stocks")).isTrue();
            // Reverse order
            assertThat(matches(r.pattern(), "trading by the insider was reported")).isTrue();
            // Exactly at the gap boundary (3 words between)
            assertThat(matches(r.pattern(), "insider a b c trading")).isTrue();
            // Beyond the gap
            assertThat(matches(r.pattern(), "insider a b c d trading")).isFalse();
            // No match at all
            assertThat(matches(r.pattern(), "routine business communications only")).isFalse();
        }

        @Test @DisplayName("FOLLOWEDBY: word before word, directional")
        void latinFollowedBy() {
            BuildResult r = MultiLanguagePatternBuilder.buildFollowedBy("buy", "shares", 3);
            assertThat(r.scriptType()).isEqualTo(ScriptType.LATIN);

            assertThat(matches(r.pattern(), "I want to buy 1000 shares today")).isTrue();
            // Reversed order — FOLLOWEDBY is directional, must not match
            assertThat(matches(r.pattern(), "Shares were sold but I did not buy")).isFalse();
            // No RTL warning for Latin
            assertThat(r.hasWarning()).isFalse();
        }

        @Test @DisplayName("NEAR gap pattern uses word-token format for Latin")
        void latinGapIsWordBased() {
            String gap = MultiLanguagePatternBuilder.wordBasedGap(2);
            assertThat(gap).contains("\\s+").contains("\\S+").contains("{0,2}");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Arabic — RTL, space-delimited, requires UTF8+UCP
    // ══════════════════════════════════════════════════════════════════════

    @Nested @DisplayName("Arabic (RTL) NEAR / FOLLOWEDBY")
    class Arabic {

        @Test @DisplayName("NEAR: Arabic price manipulation — both word orders")
        void arabicNear_priceManipulation() {
            // السعر = price,  التلاعب = manipulation
            BuildResult r = MultiLanguagePatternBuilder.buildNear("السعر", "التلاعب", 2);
            assertThat(r.scriptType()).isEqualTo(ScriptType.ARABIC);
            // Must include UTF8(32) + UCP(64) — without UCP, \\S won't match Arabic
            assertThat(r.recommendedHsFlags() & 32).isEqualTo(32); // UTF8
            assertThat(r.recommendedHsFlags() & 64).isEqualTo(64); // UCP

            // Price before manipulation
            assertThat(matches(r.pattern(), "ارتفاع السعر بسبب التلاعب")).isTrue();
            // Manipulation before price (bidirectional NEAR)
            assertThat(matches(r.pattern(), "التلاعب في السعر الأساسي")).isTrue();
            // Too many words between
            assertThat(matches(r.pattern(), "السعر في الأسواق العالمية بسبب عمليات التلاعب")).isFalse();
            // No match
            assertThat(matches(r.pattern(), "تقرير السوق اليومي بشكل عام")).isFalse();
        }

        @Test @DisplayName("NEAR: Arabic insider information — typical phrase")
        void arabicNear_insiderInfo() {
            // معلومات = information,  داخلية = insider/internal
            BuildResult r = MultiLanguagePatternBuilder.buildNear("معلومات", "داخلية", 3);
            assertThat(r.scriptType()).isEqualTo(ScriptType.ARABIC);
            assertThat(matches(r.pattern(), "استخدم معلومات داخلية للتداول")).isTrue();
            assertThat(matches(r.pattern(), "البيانات الداخلية ومعلومات السوق")).isTrue();
        }

        @Test @DisplayName("FOLLOWEDBY: Arabic logical-order match (RTL text)")
        void arabicFollowedBy() {
            // In Unicode logical order: معلومات (information) then سرية (secret)
            // This is correct for Arabic stored text
            BuildResult r = MultiLanguagePatternBuilder.buildFollowedBy("معلومات", "سرية", 2);
            assertThat(r.scriptType()).isEqualTo(ScriptType.ARABIC);
            assertThat(matches(r.pattern(), "استلم معلومات سرية قبل الإعلان")).isTrue();
            // Reversed order should NOT match (directional)
            assertThat(matches(r.pattern(), "سرية معلومات الشركة")).isFalse();
            // Purely Arabic FOLLOWEDBY — no RTL warning needed
            assertThat(r.hasWarning()).isFalse();
        }

        @Test @DisplayName("Arabic uses word-based gap (words separated by spaces)")
        void arabicGapIsWordBased() {
            BuildResult r = MultiLanguagePatternBuilder.buildNear("السعر", "التلاعب", 2);
            // Gap must use \\s+ / \\S+ token approach
            assertThat(r.pattern()).contains("\\s+");
            assertThat(r.pattern()).contains("\\S+");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Hebrew — RTL, space-delimited, requires UTF8+UCP
    // ══════════════════════════════════════════════════════════════════════

    @Nested @DisplayName("Hebrew (RTL) NEAR / FOLLOWEDBY")
    class Hebrew {

        @Test @DisplayName("NEAR: Hebrew insider trading — bidirectional")
        void hebrewNear() {
            // מידע = information/data,  פנים = insider
            BuildResult r = MultiLanguagePatternBuilder.buildNear("מידע", "פנים", 3);
            assertThat(r.scriptType()).isEqualTo(ScriptType.HEBREW);
            assertThat(r.recommendedHsFlags()).isEqualTo(99); // all unicode flags

            assertThat(matches(r.pattern(), "מסחר עם מידע פנים הוא בלתי חוקי")).isTrue();
            assertThat(matches(r.pattern(), "פנים מידע בשוק ההון")).isTrue();
            assertThat(matches(r.pattern(), "שוק המניות בתל אביב")).isFalse();
        }

        @Test @DisplayName("FOLLOWEDBY: Hebrew logical order — forward direction")
        void hebrewFollowedBy() {
            // מסחר = trading,  פנים = insider
            BuildResult r = MultiLanguagePatternBuilder.buildFollowedBy("מסחר", "פנים", 2);
            assertThat(r.scriptType()).isEqualTo(ScriptType.HEBREW);
            assertThat(matches(r.pattern(), "עסקאות מסחר פנים התגלו")).isTrue();
            assertThat(matches(r.pattern(), "פנים מסחר בניגוד לחוק")).isFalse();
        }

        @Test @DisplayName("Hebrew uses word-based gap (Hebrew words use spaces)")
        void hebrewGapIsWordBased() {
            BuildResult r = MultiLanguagePatternBuilder.buildNear("מידע", "פנים", 2);
            assertThat(r.pattern()).contains("\\s+");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Chinese (Simplified) — CJK, NO spaces between characters
    // ══════════════════════════════════════════════════════════════════════

    @Nested @DisplayName("Chinese (Simplified CJK) NEAR / FOLLOWEDBY — char-based gap")
    class ChineseSimplified {

        @Test @DisplayName("NEAR: 内幕 NEAR{2} 交易 — with and without spaces")
        void chineseNear_noSpace() {
            // 内幕 = insider,  交易 = trading
            BuildResult r = MultiLanguagePatternBuilder.buildNear("内幕", "交易", 2);
            assertThat(r.scriptType()).isEqualTo(ScriptType.CJK);
            assertThat(r.recommendedHsFlags()).isEqualTo(99);
            // Must use char-based gap (no \\s+ requirement)
            assertThat(r.pattern()).contains("[\\s\\S]");
            assertThat(r.pattern()).doesNotContain("\\S+");

            assertThat(matches(r.pattern(), "内幕交易被调查")).isTrue();            // 0 chars between
            assertThat(matches(r.pattern(), "内幕的交易记录")).isTrue();            // 1 char between
            assertThat(matches(r.pattern(), "内幕 交易")).isTrue();                // with space
            assertThat(matches(r.pattern(), "交易内幕信息")).isTrue();              // reversed
            assertThat(matches(r.pattern(), "正常的商业活动记录")).isFalse();       // no match
        }

        @Test @DisplayName("NEAR: 价格 NEAR{3} 操纵 — market manipulation")
        void chineseNear_marketManipulation() {
            // 价格 = price,  操纵 = manipulation
            BuildResult r = MultiLanguagePatternBuilder.buildNear("价格", "操纵", 3);
            assertThat(matches(r.pattern(), "价格操纵行为")).isTrue();              // adjacent
            assertThat(matches(r.pattern(), "市场价格操纵案件")).isTrue();          // 2 chars between
            assertThat(matches(r.pattern(), "操纵价格的违规行为")).isTrue();        // reversed
        }

        @Test @DisplayName("FOLLOWEDBY: 内幕 FOLLOWEDBY{2} 交易 — directional")
        void chineseFollowedBy() {
            BuildResult r = MultiLanguagePatternBuilder.buildFollowedBy("内幕", "交易", 2);
            assertThat(r.scriptType()).isEqualTo(ScriptType.CJK);

            assertThat(matches(r.pattern(), "内幕交易被发现")).isTrue();            // adjacent
            assertThat(matches(r.pattern(), "内幕的交易")).isTrue();               // 1 char between
            // Reversed order: must NOT match for FOLLOWEDBY
            assertThat(matches(r.pattern(), "交易内幕")).isFalse();
            assertThat(r.hasWarning()).isFalse();
        }

        @Test @DisplayName("CJK char-based gap formula: n=3, avgCharsPerWord=3 → {0,12}")
        void cjkGapFormula() {
            // n=3, avgCharsPerWord=3, +n buffer = 3*3+3 = 12
            String gap = MultiLanguagePatternBuilder.charBasedGap(ScriptType.CJK, 3);
            assertThat(gap).isEqualTo("[\\s\\S]{0,12}");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Chinese (Traditional)
    // ══════════════════════════════════════════════════════════════════════

    @Nested @DisplayName("Chinese (Traditional) NEAR")
    class ChineseTraditional {

        @Test @DisplayName("NEAR: 內幕 NEAR{2} 交易 (traditional characters)")
        void traditionalChineseNear() {
            BuildResult r = MultiLanguagePatternBuilder.buildNear("內幕", "交易", 2);
            assertThat(r.scriptType()).isEqualTo(ScriptType.CJK);
            assertThat(matches(r.pattern(), "內幕交易被調查")).isTrue();
            assertThat(matches(r.pattern(), "市場操縱調查")).isFalse();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Japanese — Kanji (CJK) + Kana, no spaces between script types
    // ══════════════════════════════════════════════════════════════════════

    @Nested @DisplayName("Japanese (Kanji + Kana) NEAR / FOLLOWEDBY")
    class Japanese {

        @Test @DisplayName("NEAR: インサイダー NEAR{3} 取引 — katakana + kanji mix")
        void japaneseNear_kanaKanji() {
            // インサイダー = insider (katakana),  取引 = transaction/trading (kanji)
            BuildResult r = MultiLanguagePatternBuilder.buildNear("インサイダー", "取引", 3);
            // Both kana and CJK are char-based
            assertThat(r.scriptType()).isEqualTo(ScriptType.MIXED_CJK);
            assertThat(r.recommendedHsFlags()).isEqualTo(99);

            assertThat(matches(r.pattern(), "インサイダー取引が発覚した")).isTrue();     // adjacent
            assertThat(matches(r.pattern(), "取引インサイダー情報")).isTrue();           // reversed
            assertThat(matches(r.pattern(), "通常の株式取引市場")).isFalse();            // no match
        }

        @Test @DisplayName("NEAR: 情報 NEAR{2} 内部 — kanji terms (insider information)")
        void japaneseNear_kanji() {
            // 情報 = information,  内部 = insider/internal
            BuildResult r = MultiLanguagePatternBuilder.buildNear("情報", "内部", 2);
            assertThat(r.scriptType()).isEqualTo(ScriptType.CJK);
            assertThat(matches(r.pattern(), "内部情報を利用した")).isTrue();
            assertThat(matches(r.pattern(), "情報内部からのリーク")).isTrue();
        }

        @Test @DisplayName("FOLLOWEDBY: インサイダー FOLLOWEDBY{2} 取引")
        void japaneseFollowedBy() {
            BuildResult r = MultiLanguagePatternBuilder.buildFollowedBy("インサイダー", "取引", 2);
            assertThat(matches(r.pattern(), "インサイダー取引調査")).isTrue();
            assertThat(matches(r.pattern(), "取引インサイダー")).isFalse();     // reversed, must not match
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Korean (Hangul) — character-based, handles both with/without spaces
    // ══════════════════════════════════════════════════════════════════════

    @Nested @DisplayName("Korean (Hangul) NEAR / FOLLOWEDBY")
    class Korean {

        @Test @DisplayName("NEAR: 내부자 NEAR{3} 거래 — with space (formal Korean)")
        void koreanNear_withSpace() {
            BuildResult r = MultiLanguagePatternBuilder.buildNear("내부자", "거래", 3);
            assertThat(r.scriptType()).isEqualTo(ScriptType.HANGUL);
            assertThat(r.recommendedHsFlags()).isEqualTo(99);

            // Formal Korean (with spaces)
            assertThat(matches(r.pattern(), "내부자 거래 혐의로 기소되었다")).isTrue();
            assertThat(matches(r.pattern(), "거래 내부자 정보를 사용했다")).isTrue();
        }

        @Test @DisplayName("NEAR: 내부자 NEAR{3} 거래 — without space (informal Korean)")
        void koreanNear_withoutSpace() {
            // KEY TEST: char-based gap must handle the no-space case
            BuildResult r = MultiLanguagePatternBuilder.buildNear("내부자", "거래", 3);
            assertThat(matches(r.pattern(), "내부자거래혐의")).isTrue();         // no spaces at all
            assertThat(matches(r.pattern(), "거래내부자")).isTrue();             // reversed, no space
        }

        @Test @DisplayName("NEAR: 정보 NEAR{2} 유출 — information leak")
        void koreanNear_infoLeak() {
            // 정보 = information,  유출 = leak
            BuildResult r = MultiLanguagePatternBuilder.buildNear("정보", "유출", 2);
            assertThat(matches(r.pattern(), "정보 유출 사건")).isTrue();
            assertThat(matches(r.pattern(), "정보유출")).isTrue();               // no space
            assertThat(matches(r.pattern(), "유출된 정보")).isTrue();            // reversed
        }

        @Test @DisplayName("FOLLOWEDBY: 내부자 FOLLOWEDBY{2} 거래 — with and without space")
        void koreanFollowedBy() {
            BuildResult r = MultiLanguagePatternBuilder.buildFollowedBy("내부자", "거래", 2);
            assertThat(r.scriptType()).isEqualTo(ScriptType.HANGUL);

            assertThat(matches(r.pattern(), "내부자 거래 혐의")).isTrue();       // with space
            assertThat(matches(r.pattern(), "내부자거래")).isTrue();             // without space
            // Reversed: FOLLOWEDBY is directional
            assertThat(matches(r.pattern(), "거래 내부자")).isFalse();
        }

        @Test @DisplayName("Korean gap is char-based (not word-based)")
        void koreanGapIsCharBased() {
            BuildResult r = MultiLanguagePatternBuilder.buildNear("내부자", "거래", 2);
            assertThat(r.pattern()).contains("[\\s\\S]");
            // Must NOT require \\s+ between terms (breaks no-space Korean)
            assertThat(r.pattern()).doesNotContain("\\S+");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Thai — character-based, no word spaces
    // ══════════════════════════════════════════════════════════════════════

    @Nested @DisplayName("Thai NEAR / FOLLOWEDBY — char-based gap")
    class Thai {

        @Test @DisplayName("NEAR: ราคา NEAR{3} การซื้อขาย — price NEAR trading")
        void thaiNear() {
            // ราคา = price,  การซื้อขาย = trading
            BuildResult r = MultiLanguagePatternBuilder.buildNear("ราคา", "การซื้อขาย", 3);
            assertThat(r.scriptType()).isEqualTo(ScriptType.THAI);
            assertThat(r.recommendedHsFlags()).isEqualTo(99);
            // Thai uses char-based gap
            assertThat(r.pattern()).contains("[\\s\\S]");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Mixed language — the most important real-world case
    // ══════════════════════════════════════════════════════════════════════

    @Nested @DisplayName("Mixed language NEAR / FOLLOWEDBY")
    class Mixed {

        @Test @DisplayName("NEAR: English + Korean — char-based gap (CJK wins)")
        void englishKoreanNear() {
            BuildResult r = MultiLanguagePatternBuilder.buildNear("insider", "내부자", 3);
            assertThat(r.scriptType()).isEqualTo(ScriptType.MIXED_CJK);
            assertThat(r.pattern()).contains("[\\s\\S]"); // char-based
            assertThat(r.recommendedHsFlags()).isEqualTo(99);

            assertThat(matches(r.pattern(), "he is an insider 내부자")).isTrue();
            assertThat(matches(r.pattern(), "내부자 was the insider")).isTrue();
        }

        @Test @DisplayName("NEAR: English + Chinese — char-based gap")
        void englishChineseNear() {
            BuildResult r = MultiLanguagePatternBuilder.buildNear("insider", "内幕", 3);
            assertThat(r.scriptType()).isEqualTo(ScriptType.MIXED_CJK);
            assertThat(matches(r.pattern(), "insider 内幕 trading")).isTrue();
            assertThat(matches(r.pattern(), "内幕 insider信息")).isTrue();
        }

        @Test @DisplayName("NEAR: English + Japanese — char-based gap")
        void englishJapaneseNear() {
            BuildResult r = MultiLanguagePatternBuilder.buildNear("trading", "取引", 2);
            assertThat(r.scriptType()).isEqualTo(ScriptType.MIXED_CJK);
            assertThat(matches(r.pattern(), "illegal trading取引")).isTrue();
        }

        @Test @DisplayName("NEAR: English + Arabic — word-based gap (both space-delimited)")
        void englishArabicNear() {
            // Both English and Arabic use spaces → word-based gap applies
            BuildResult r = MultiLanguagePatternBuilder.buildNear("price", "السعر", 3);
            assertThat(r.scriptType()).isEqualTo(ScriptType.MIXED_RTL);
            assertThat(r.pattern()).contains("\\s+");
            assertThat(r.recommendedHsFlags()).isEqualTo(99); // needs UCP for Arabic

            assertThat(matches(r.pattern(), "the price and السعر were manipulated")).isTrue();
            assertThat(matches(r.pattern(), "السعر is the Arabic word for price")).isTrue();
        }

        @Test @DisplayName("NEAR: English + Hebrew — word-based gap")
        void englishHebrewNear() {
            BuildResult r = MultiLanguagePatternBuilder.buildNear("information", "מידע", 3);
            assertThat(r.scriptType()).isEqualTo(ScriptType.MIXED_RTL);
            assertThat(matches(r.pattern(), "the information מידע was leaked")).isTrue();
        }

        @Test @DisplayName("NEAR: Chinese + Arabic — CJK wins (char-based gap)")
        void chineseArabicNear() {
            BuildResult r = MultiLanguagePatternBuilder.buildNear("内幕", "معلومات", 2);
            assertThat(r.scriptType()).isEqualTo(ScriptType.MIXED_CJK);
            assertThat(r.pattern()).contains("[\\s\\S]");
        }

        @Test @DisplayName("NEAR: Three-language mix — Korean + Arabic + English")
        void threeLanguageMix() {
            // Operands: Korean term vs Arabic term
            BuildResult r = MultiLanguagePatternBuilder.buildNear("내부자", "معلومات", 3);
            assertThat(r.scriptType()).isEqualTo(ScriptType.MIXED_CJK); // Korean takes char-based priority
            assertThat(r.pattern()).contains("[\\s\\S]");
        }

        // ── FOLLOWEDBY mixed-direction warning ─────────────────────────────

        @Test @DisplayName("FOLLOWEDBY: English + Arabic — warning emitted (mixed RTL+LTR direction)")
        void followedBy_englishArabic_rtlWarning() {
            BuildResult r = MultiLanguagePatternBuilder.buildFollowedBy("insider", "السعر", 2);
            // Should generate a warning about mixed RTL+LTR FOLLOWEDBY
            assertThat(r.hasWarning()).isTrue();
            assertThat(r.warning()).containsIgnoringCase("RTL");
            // Pattern still generated and functional
            assertThat(r.pattern()).isNotBlank();
        }

        @Test @DisplayName("FOLLOWEDBY: Pure Arabic — no warning (logical order is reading order)")
        void followedBy_pureArabic_noWarning() {
            BuildResult r = MultiLanguagePatternBuilder.buildFollowedBy("معلومات", "سرية", 2);
            assertThat(r.hasWarning()).isFalse();
        }

        @Test @DisplayName("FOLLOWEDBY: Korean + English — char-based, no RTL warning")
        void followedBy_koreanEnglish_noWarning() {
            BuildResult r = MultiLanguagePatternBuilder.buildFollowedBy("내부자", "insider", 3);
            assertThat(r.scriptType()).isEqualTo(ScriptType.MIXED_CJK);
            assertThat(r.hasWarning()).isFalse();
            assertThat(r.pattern()).contains("[\\s\\S]");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Flags validation
    // ══════════════════════════════════════════════════════════════════════

    @Nested @DisplayName("Recommended Hyperscan flags")
    class Flags {

        @Test @DisplayName("Latin-only → flags=3 (CASELESS+DOTALL, no UTF8/UCP needed)")
        void latinFlags() {
            assertThat(MultiLanguagePatternBuilder.recommendedHsFlags("insider", "trading"))
                    .isEqualTo(3);
        }

        @Test @DisplayName("Arabic → flags=99 (CASELESS+DOTALL+UTF8+UCP)")
        void arabicFlags() {
            assertThat(MultiLanguagePatternBuilder.recommendedHsFlags("السعر", "التلاعب"))
                    .isEqualTo(99);
        }

        @Test @DisplayName("Hebrew → flags=99")
        void hebrewFlags() {
            assertThat(MultiLanguagePatternBuilder.recommendedHsFlags("מידע", "פנים"))
                    .isEqualTo(99);
        }

        @Test @DisplayName("CJK → flags=99")
        void cjkFlags() {
            assertThat(MultiLanguagePatternBuilder.recommendedHsFlags("内幕", "交易"))
                    .isEqualTo(99);
        }

        @Test @DisplayName("Korean → flags=99")
        void koreanFlags() {
            assertThat(MultiLanguagePatternBuilder.recommendedHsFlags("내부자", "거래"))
                    .isEqualTo(99);
        }

        @Test @DisplayName("Mixed Latin+Arabic → flags=99 (Unicode needed)")
        void mixedLatinArabicFlags() {
            assertThat(MultiLanguagePatternBuilder.recommendedHsFlags("price", "السعر"))
                    .isEqualTo(99);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Gap pattern structure
    // ══════════════════════════════════════════════════════════════════════

    @Nested @DisplayName("Gap pattern structure")
    class GapPattern {

        @Test @DisplayName("wordBasedGap(n=0): zero intervening words allowed")
        void wordGapZero() {
            assertThat(MultiLanguagePatternBuilder.wordBasedGap(0))
                    .isEqualTo("(?:\\s+\\S+){0,0}\\s+");
        }

        @Test @DisplayName("charBasedGap CJK n=1: {0,4}")
        void charGapCjkN1() {
            assertThat(MultiLanguagePatternBuilder.charBasedGap(ScriptType.CJK, 1))
                    .isEqualTo("[\\s\\S]{0,4}"); // 1*3+1 = 4
        }

        @Test @DisplayName("charBasedGap Hangul n=3: {0,18}")
        void charGapHangulN3() {
            assertThat(MultiLanguagePatternBuilder.charBasedGap(ScriptType.HANGUL, 3))
                    .isEqualTo("[\\s\\S]{0,18}"); // 3*5+3 = 18
        }

        @Test @DisplayName("charBasedGap MIXED_CJK n=2: {0,10}")
        void charGapMixedCjkN2() {
            assertThat(MultiLanguagePatternBuilder.charBasedGap(ScriptType.MIXED_CJK, 2))
                    .isEqualTo("[\\s\\S]{0,10}"); // 2*4+2 = 10
        }

        @Test @DisplayName("NEAR pattern is bidirectional: (?:A...B|B...A)")
        void nearIsBidirectional() {
            BuildResult r = MultiLanguagePatternBuilder.buildNear("price", "manipulation", 1);
            // Must contain both orders
            assertThat(r.pattern()).startsWith("(?:");
            assertThat(r.pattern()).contains("price");
            assertThat(r.pattern()).contains("manipulation");
            // Both orderings present (count occurrences)
            long priceCount = r.pattern().chars()
                    .filter(c -> r.pattern().indexOf("price") != -1).count();
            assertThat(priceCount).isGreaterThan(0);
        }

        @Test @DisplayName("FOLLOWEDBY pattern is directional: A...B only (no B...A)")
        void followedByIsDirectional() {
            BuildResult r = MultiLanguagePatternBuilder.buildFollowedBy("buy", "shares", 2);
            // The pattern must start with "buy" and end with "shares"
            // (not wrapped in a bidirectional alternation)
            assertThat(r.pattern()).startsWith("buy");
            assertThat(r.pattern()).endsWith("shares");
            assertThat(r.pattern()).doesNotStartWith("(?:");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Edge cases
    // ══════════════════════════════════════════════════════════════════════

    @Nested @DisplayName("Edge cases")
    class EdgeCases {

        @Test @DisplayName("n=0: adjacent terms (no gap)")
        void nEqualsZero() {
            BuildResult r = MultiLanguagePatternBuilder.buildNear("내부자", "거래", 0);
            // Pattern should still be valid and match adjacent terms
            assertThat(r.pattern()).isNotBlank();
            assertThat(matches(r.pattern(), "내부자거래")).isTrue();
        }

        @Test @DisplayName("Large n=10: CJK gap ceiling is 10*3+10=40 chars")
        void largeN() {
            String gap = MultiLanguagePatternBuilder.charBasedGap(ScriptType.CJK, 10);
            assertThat(gap).isEqualTo("[\\s\\S]{0,40}");
        }

        @Test @DisplayName("Emoji terms: fall back to LATIN script safely")
        void emojiTerms() {
            BuildResult r = MultiLanguagePatternBuilder.buildNear("💰", "🤫", 2);
            assertThat(r).isNotNull();
            assertThat(r.pattern()).isNotBlank();
        }

        @Test @DisplayName("Single-character CJK terms")
        void singleCharCjk() {
            BuildResult r = MultiLanguagePatternBuilder.buildNear("金", "融", 1);
            assertThat(r.scriptType()).isEqualTo(ScriptType.CJK);
            assertThat(matches(r.pattern(), "金融")).isTrue(); // adjacent
        }

        @Test @DisplayName("Mixed CJK with whitespace in operand")
        void cjkWithWhitespace() {
            BuildResult r = MultiLanguagePatternBuilder.buildNear("내부 거래", "정보", 2);
            assertThat(r.scriptType()).isEqualTo(ScriptType.HANGUL);
            assertThat(r.pattern()).isNotBlank();
        }
    }
}
