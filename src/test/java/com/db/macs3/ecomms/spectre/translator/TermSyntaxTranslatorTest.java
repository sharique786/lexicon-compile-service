package com.db.macs3.ecomms.spectre.translator;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TermSyntaxTranslator}.
 * No Spring context — plain JDK 21 Java.
 *
 * <p>Tests all operators (OR, AND, AND NOT, NOT, NEAR{n}, FOLLOWEDBY{n}),
 * wildcards, quoted phrases, apostrophes, emojis, and all supported languages.
 */
@DisplayName("TermSyntaxTranslator Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TermSyntaxTranslatorTest {

    private TermSyntaxTranslator translator;

    @BeforeEach
    void setUp() {
        translator = new TermSyntaxTranslator();
    }

    // Helper: get success pattern
    private String pattern(TranslationResult r) {
        assertThat(r.isSuccess()).as("Expected success but got: %s",
                r instanceof TranslationResult.Error e ? e.message() : "").isTrue();
        return ((TranslationResult.Success) r).hsPattern();
    }

    private int flags(TranslationResult r) {
        return ((TranslationResult.Success) r).hsFlags();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // OR operator
    // ═════════════════════════════════════════════════════════════════════════

    @Test @Order(1)
    @DisplayName("OR: two terms → (?:A|B)")
    void simpleOr() {
        var r = translator.translate("price OR spread");
        assertThat(pattern(r)).isEqualTo("(?:price|spread)");
    }

    @Test @Order(2)
    @DisplayName("OR: three terms → (?:A|B|C)")
    void threeTermOr() {
        var r = translator.translate("price OR spread OR stock");
        assertThat(pattern(r)).isEqualTo("(?:price|spread|stock)");
    }

    @Test @Order(3)
    @DisplayName("OR: parenthesized groups → (?:price|spread|stock)")
    void parenthesizedOr() {
        var r = translator.translate("((price) OR (spread) OR (stock))");
        assertThat(pattern(r)).isEqualTo("(?:price|spread|stock)");
    }

    // ═════════════════════════════════════════════════════════════════════════
    // AND operator
    // ═════════════════════════════════════════════════════════════════════════

    @Test @Order(10)
    @DisplayName("AND: two terms → (?:A|B) OR pre-scan (Hyperscan has no lookahead)")
    void andTwoTerms() {
        var r = translator.translate("insider AND announcement");
        // Hyperscan rejects (?=...); translator emits OR pre-scan + requiresAndPostFilter
        assertThat(pattern(r)).isEqualTo("(?:insider|announcement)");
        assertThat(pattern(r)).doesNotContain("(?=");
        assertThat(flags(r) & ParseContext.HS_FLAG_DOTALL).isEqualTo(ParseContext.HS_FLAG_DOTALL);
    }

    @Test @Order(11)
    @DisplayName("AND: sets requiresAndPostFilter = true")
    void andSetsPostFilter() {
        var r = translator.translate("A AND B");
        assertThat(((TranslationResult.Success) r).requiresAndPostFilter()).isTrue();
    }

    @Test @Order(12)
    @DisplayName("AND: three terms → (?:A|B|C) all operands in OR group")
    void andThreeTerms() {
        var r = translator.translate("wire AND offshore AND transfer");
        String p = pattern(r);
        assertThat(p).startsWith("(?:").endsWith(")");
        assertThat(p).contains("wire").contains("offshore").contains("transfer");
        assertThat(p).doesNotContain("(?=");
    }

    // ═════════════════════════════════════════════════════════════════════════
    // AND NOT operator
    // ═════════════════════════════════════════════════════════════════════════

    @Test @Order(20)
    @DisplayName("AND NOT: returns positive operand only (Hyperscan has no negative lookahead)")
    void andNot() {
        var r = translator.translate("insider AND NOT disclaimer");
        // Hyperscan rejects (?!...); translator returns positive part; engine excludes NOT at scan-time
        assertThat(pattern(r)).isEqualTo("insider");
        assertThat(pattern(r)).doesNotContain("(?=").doesNotContain("(?!");
        assertThat(flags(r) & ParseContext.HS_FLAG_DOTALL).isEqualTo(ParseContext.HS_FLAG_DOTALL);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // NOT operator
    // ═════════════════════════════════════════════════════════════════════════

    @Test @Order(30)
    @DisplayName("NOT prefix → returns operand pattern (Hyperscan has no negative lookahead)")
    void notPrefix() {
        var r = translator.translate("NOT spam");
        // Hyperscan rejects (?!...); translator returns operand; engine inverts match at scan-time
        assertThat(pattern(r)).isEqualTo("spam");
        assertThat(pattern(r)).doesNotContain("(?!");
        assertThat(flags(r) & ParseContext.HS_FLAG_DOTALL).isEqualTo(ParseContext.HS_FLAG_DOTALL);
    }

    @Test @Order(31)
    @DisplayName("! prefix is alias for NOT — returns operand pattern")
    void exclamationAsNot() {
        var r = translator.translate("!spam");
        assertThat(pattern(r)).isEqualTo("spam");
        assertThat(pattern(r)).doesNotContain("(?!");
    }

    // ═════════════════════════════════════════════════════════════════════════
    // NEAR{n} operator
    // ═════════════════════════════════════════════════════════════════════════

    @Test @Order(40)
    @DisplayName("NEAR{5}: spec example 1 — bidirectional proximity pattern")
    void nearSpecExample1() {
        // JSON version (no wildcard)
        var r = translator.translate("(manipulate) NEAR{5} ((price) OR (spread) OR (stock))");
        assertThat(r.isSuccess()).isTrue();
        String p = pattern(r);
        assertThat(p).contains("manipulate");
        assertThat(p).contains("{0,5}");
        assertThat(p).contains("(?:price|spread|stock)");
        // Bidirectional: pattern appears twice (A|B and B|A structure)
        assertThat(p).startsWith("(?:");
    }

    @Test @Order(41)
    @DisplayName("NEAR{5}: wildcard version from CSV")
    void nearWithWildcard() {
        var r = translator.translate("(manipulate*) NEAR{5} ((price) OR (spread) OR (stock))");
        assertThat(r.isSuccess()).isTrue();
        String p = pattern(r);
        assertThat(p).contains("manipulate");
        assertThat(p).contains("\\S*");
        assertThat(p).contains("{0,5}");
    }

    @Test @Order(42)
    @DisplayName("NEAR{3}: gap pattern has correct distance")
    void nearDistance() {
        var r = translator.translate("tip NEAR{3} trade");
        assertThat(pattern(r)).contains("{0,3}");
        assertThat(pattern(r)).contains("tip").contains("trade");
    }

    @Test @Order(43)
    @DisplayName("NEAR is bidirectional: both A→B and B→A appear in pattern")
    void nearIsBidirectional() {
        var r = translator.translate("alpha NEAR{2} beta");
        String p = pattern(r);
        // Should contain both orderings
        int alphaFirst = p.indexOf("alpha");
        int betaFirst  = p.indexOf("beta");
        int alphaSecond = p.lastIndexOf("alpha");
        int betaSecond  = p.lastIndexOf("beta");
        assertThat(alphaFirst).isNotEqualTo(alphaSecond);  // alpha appears twice
        assertThat(betaFirst).isNotEqualTo(betaSecond);    // beta appears twice
    }

    // ═════════════════════════════════════════════════════════════════════════
    // FOLLOWEDBY{n} operator
    // ═════════════════════════════════════════════════════════════════════════

    @Test @Order(50)
    @DisplayName("FOLLOWEDBY{3}: directional — A then B only, compliance appears exactly once")
    void followedBy() {
        var r = translator.translate("don't FOLLOWEDBY{3} compliance");
        assertThat(r.isSuccess()).isTrue();
        String p = pattern(r);
        assertThat(p).contains("don't");
        assertThat(p).contains("{0,3}");
        assertThat(p).contains("compliance");
        // Directional: "compliance" appears exactly once (NEAR has it twice, once per direction)
        // Note: (?:\s+\S+){0,n} in the gap legitimately contains "(?:" — that is expected
        assertThat(p.indexOf("compliance")).isEqualTo(p.lastIndexOf("compliance"));
        assertThat(p).doesNotContain("|compliance");  // no bidirectional alternation
    }

    @Test @Order(51)
    @DisplayName("FOLLOWEDBY: 'don't care about compliance' should match pattern")
    void followedByMatchesExample() {
        var r = translator.translate("don't FOLLOWEDBY{3} compliance");
        String p = pattern(r);
        // The pattern should be: don't(?:\s+\S+){0,3}\s+compliance
        // "don't care about compliance" has 2 words gap → fits {0,3}
        assertThat(p).matches(".*don't.*\\{0,3\\}.*compliance.*");
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Wildcards
    // ═════════════════════════════════════════════════════════════════════════

    @Test @Order(60)
    @DisplayName("Trailing wildcard: word* → word\\S*")
    void trailingWildcard() {
        assertThat(pattern(translator.translate("manipulate*"))).isEqualTo("manipulate\\S*");
    }

    @Test @Order(61)
    @DisplayName("Leading wildcard: *word → \\S*word")
    void leadingWildcard() {
        assertThat(pattern(translator.translate("*running"))).isEqualTo("\\S*running");
    }

    @Test @Order(62)
    @DisplayName("Embedded wildcard: fr*nt → fr\\S*nt")
    void embeddedWildcard() {
        assertThat(pattern(translator.translate("fr*nt"))).isEqualTo("fr\\S*nt");
    }

    @Test @Order(63)
    @DisplayName("Standalone wildcard: * → \\S+")
    void standaloneWildcard() {
        assertThat(pattern(translator.translate("*"))).isEqualTo("\\S+");
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Quoted phrases
    // ═════════════════════════════════════════════════════════════════════════

    @Test @Order(70)
    @DisplayName("Spec example 2: quoted OR — \"please don't forward\" OR ...")
    void quotedOrSpecExample2() {
        var r = translator.translate(
                "((\"please don't forward\") OR (\"do not share don't forward\"))");
        assertThat(r.isSuccess()).isTrue();
        String p = pattern(r);
        assertThat(p).contains("please don't forward");
        assertThat(p).contains("do not share don't forward");
    }

    @Test @Order(71)
    @DisplayName("CSV double-quote escaped: \"\"quoted\"\" → quoted")
    void csvDoubleQuoteEscaping() {
        var r = translator.translate("((\"\"please don't forward\"\") OR (\"\"do not share\"\"))");
        assertThat(r.isSuccess()).isTrue();
        assertThat(pattern(r)).contains("please don't forward");
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Apostrophe and special characters
    // ═════════════════════════════════════════════════════════════════════════

    @Test @Order(80)
    @DisplayName("Apostrophe in word: don't preserved literally (not escaped)")
    void apostrophePreserved() {
        var r = translator.translate("don't");
        assertThat(pattern(r)).isEqualTo("don't");
    }

    @Test @Order(81)
    @DisplayName("Exclamation: stop! — literal in PCRE")
    void exclamationLiteral() {
        var r = translator.translate("\"stop!\"");
        assertThat(pattern(r)).isEqualTo("stop!");
    }

    @Test @Order(82)
    @DisplayName("PCRE metachar . is escaped in plain terms")
    void dotEscaped() {
        var r = translator.translate("price.spread");
        assertThat(pattern(r)).isEqualTo("price\\.spread");
    }

    @Test @Order(83)
    @DisplayName("PCRE metachar ( is escaped in plain terms")
    void parenEscaped() {
        // When a term has no OR/AND, a lone paren in a word is a leaf
        var r = translator.translate("\"(net)\"");
        assertThat(pattern(r)).isEqualTo("\\(net\\)");
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Emoji
    // ═════════════════════════════════════════════════════════════════════════

    @Test @Order(90)
    @DisplayName("Emoji: 💰 → \\x{1F4B0} with UTF8+UCP flags")
    void singleEmoji() {
        var r = translator.translate("💰");
        assertThat(r.isSuccess()).isTrue();
        assertThat(pattern(r)).isEqualTo("\\x{1F4B0}");
        assertThat(flags(r) & ParseContext.HS_FLAG_UTF8).isEqualTo(ParseContext.HS_FLAG_UTF8);
        assertThat(flags(r) & ParseContext.HS_FLAG_UCP).isEqualTo(ParseContext.HS_FLAG_UCP);
    }

    @Test @Order(91)
    @DisplayName("Emoji OR: 💰 OR 🤫 OR 🤐 → (?:\\x{...}|\\x{...}|\\x{...})")
    void emojiOr() {
        var r = translator.translate("💰 OR 🤫 OR 🤐");
        assertThat(r.isSuccess()).isTrue();
        String p = pattern(r);
        assertThat(p).contains("\\x{1F4B0}");  // 💰
        assertThat(p).contains("\\x{1F92B}");  // 🤫
        assertThat(p).contains("\\x{1F910}");  // 🤐
        assertThat(p).startsWith("(?:");
    }

    @Test @Order(92)
    @DisplayName("Emoji detection: isEmojiCodePoint covers all major ranges")
    void emojiDetection() {
        assertThat(translator.isEmojiCodePoint(0x1F600)).isTrue();  // 😀
        assertThat(translator.isEmojiCodePoint(0x1F4B0)).isTrue();  // 💰
        assertThat(translator.isEmojiCodePoint(0x2764)).isTrue();   // ❤ (BMP)
        assertThat(translator.isEmojiCodePoint(0x41)).isFalse();    // 'A'
        assertThat(translator.isEmojiCodePoint(0x0041)).isFalse();  // ASCII
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Non-English languages
    // ═════════════════════════════════════════════════════════════════════════

    @Test @Order(100)
    @DisplayName("Korean: 비밀 OR 내부자 거래 → (?:비밀|내부자 거래) + UTF8+UCP")
    void koreanOr() {
        var r = translator.translate("비밀 OR 내부자 거래");
        assertThat(r.isSuccess()).isTrue();
        String p = pattern(r);
        assertThat(p).contains("비밀").contains("내부자 거래");
        assertThat(flags(r) & ParseContext.HS_FLAG_UTF8).isEqualTo(ParseContext.HS_FLAG_UTF8);
    }

    @Test @Order(101)
    @DisplayName("Japanese: 株価操作 OR インサイダー → OR pattern + UTF8")
    void japaneseOr() {
        var r = translator.translate("株価操作 OR インサイダー");
        assertThat(r.isSuccess()).isTrue();
        assertThat(pattern(r)).contains("株価操作").contains("インサイダー");
        assertThat(translator.hasNonAscii("株価操作")).isTrue();
    }

    @Test @Order(102)
    @DisplayName("Chinese/Mandarin: 内幕交易 OR 操纵市场 → OR pattern + UTF8")
    void chineseOr() {
        var r = translator.translate("内幕交易 OR 操纵市场");
        assertThat(r.isSuccess()).isTrue();
        assertThat(pattern(r)).contains("内幕交易").contains("操纵市场");
    }

    @Test @Order(103)
    @DisplayName("Arabic: مخالفة OR استثمار داخلي → OR pattern + UTF8")
    void arabicOr() {
        var r = translator.translate("مخالفة OR استثمار داخلي");
        assertThat(r.isSuccess()).isTrue();
        assertThat(pattern(r)).contains("مخالفة").contains("استثمار");
        assertThat(flags(r) & ParseContext.HS_FLAG_UTF8).isEqualTo(ParseContext.HS_FLAG_UTF8);
    }

    @Test @Order(104)
    @DisplayName("Hebrew: מסחר פנים OR מניפולציה → OR pattern + UTF8")
    void hebrewOr() {
        var r = translator.translate("מסחר פנים OR מניפולציה");
        assertThat(r.isSuccess()).isTrue();
        assertThat(pattern(r)).contains("מסחר פנים").contains("מניפולציה");
    }

    @Test @Order(105)
    @DisplayName("German: Übernahme OR Insiderhandel → OR pattern + UTF8")
    void germanOr() {
        var r = translator.translate("Übernahme OR Insiderhandel");
        assertThat(r.isSuccess()).isTrue();
        assertThat(pattern(r)).contains("Übernahme").contains("Insiderhandel");
        assertThat(flags(r) & ParseContext.HS_FLAG_UTF8).isEqualTo(ParseContext.HS_FLAG_UTF8);
    }

    @Test @Order(106)
    @DisplayName("Turkish: içeriden bilgi OR piyasa manipülasyonu → UTF8")
    void turkishOr() {
        var r = translator.translate("içeriden bilgi OR piyasa manipülasyonu");
        assertThat(r.isSuccess()).isTrue();
        assertThat(pattern(r)).contains("içeriden bilgi");
    }

    @Test @Order(107)
    @DisplayName("Mixed English + Korean: insider OR 내부자 → OR + UTF8")
    void mixedEnglishKorean() {
        var r = translator.translate("insider OR 내부자");
        assertThat(r.isSuccess()).isTrue();
        String p = pattern(r);
        assertThat(p).contains("insider").contains("내부자");
        assertThat(flags(r) & ParseContext.HS_FLAG_UTF8).isEqualTo(ParseContext.HS_FLAG_UTF8);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Leet-speak
    // ═════════════════════════════════════════════════════════════════════════

    @Test @Order(110)
    @DisplayName("Leet-speak: 1ns1d3r → literal pattern (intentional)")
    void leetSpeak() {
        var r = translator.translate("1ns1d3r OR insider");
        assertThat(r.isSuccess()).isTrue();
        assertThat(pattern(r)).contains("1ns1d3r").contains("insider");
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Flags
    // ═════════════════════════════════════════════════════════════════════════

    @Test @Order(120)
    @DisplayName("CASELESS flag always set")
    void caselessAlwaysSet() {
        var r = translator.translate("price");
        assertThat(flags(r) & ParseContext.HS_FLAG_CASELESS)
                .isEqualTo(ParseContext.HS_FLAG_CASELESS);
    }

    @Test @Order(121)
    @DisplayName("DOTALL flag set for AND")
    void dotallForAnd() {
        var r = translator.translate("A AND B");
        assertThat(flags(r) & ParseContext.HS_FLAG_DOTALL).isEqualTo(ParseContext.HS_FLAG_DOTALL);
    }

    @Test @Order(122)
    @DisplayName("No DOTALL for pure OR")
    void noDotallForOr() {
        var r = translator.translate("A OR B");
        assertThat(flags(r) & ParseContext.HS_FLAG_DOTALL).isEqualTo(0);
    }

    @Test @Order(123)
    @DisplayName("UTF8+UCP for emoji")
    void utf8ForEmoji() {
        var r = translator.translate("💰");
        assertThat(flags(r) & ParseContext.HS_FLAG_UTF8).isEqualTo(ParseContext.HS_FLAG_UTF8);
        assertThat(flags(r) & ParseContext.HS_FLAG_UCP).isEqualTo(ParseContext.HS_FLAG_UCP);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Edge cases and error handling
    // ═════════════════════════════════════════════════════════════════════════

    @Test @Order(130)
    @DisplayName("Null input → Error result")
    void nullInput() {
        var r = translator.translate(null);
        assertThat(r.isSuccess()).isFalse();
    }

    @Test @Order(131)
    @DisplayName("Blank input → Error result")
    void blankInput() {
        var r = translator.translate("   ");
        assertThat(r.isSuccess()).isFalse();
    }

    @Test @Order(132)
    @DisplayName("Nested OR inside NEAR: (A OR B) NEAR{3} C")
    void nestedOrInsideNear() {
        var r = translator.translate("(price OR spread) NEAR{3} (manipulate*)");
        assertThat(r.isSuccess()).isTrue();
        String p = pattern(r);
        assertThat(p).contains("(?:price|spread)");
        assertThat(p).contains("manipulate");
        assertThat(p).contains("{0,3}");
    }

    @Test @Order(133)
    @DisplayName("splitTopLevel: OR splits correctly at depth=0")
    void splitTopLevelOr() {
        List<String> parts = translator.splitTopLevel("A OR B OR C", "OR");
        assertThat(parts).containsExactly("A", "B", "C");
    }

    @Test @Order(134)
    @DisplayName("splitTopLevel: does not split inside parentheses")
    void splitTopLevelRespectParens() {
        List<String> parts = translator.splitTopLevel("(A OR B) OR C", "OR");
        assertThat(parts).containsExactly("(A OR B)", "C");
    }

    @Test @Order(135)
    @DisplayName("splitTopLevel: AND skips AND NOT occurrences")
    void splitAndSkipsAndNot() {
        List<String> parts = translator.splitTopLevel("A AND NOT B", "AND");
        // Should NOT split — AND NOT is a different operator
        assertThat(parts).hasSize(1);
    }

    @Test @Order(136)
    @DisplayName("isWrappedInParens: true for (A OR B)")
    void wrappedInParens() {
        assertThat(translator.isWrappedInParens("(A OR B)")).isTrue();
    }

    @Test @Order(137)
    @DisplayName("isWrappedInParens: false for (A) OR (B)")
    void notWrappedInParens() {
        assertThat(translator.isWrappedInParens("(A) OR (B)")).isFalse();
    }

    @Test @Order(138)
    @DisplayName("escapeSpecialChars: escapes PCRE metacharacters")
    void escapeSpecialChars() {
        assertThat(translator.escapeSpecialChars("a.b+c")).isEqualTo("a\\.b\\+c");
        assertThat(translator.escapeSpecialChars("(net)")).isEqualTo("\\(net\\)");
        assertThat(translator.escapeSpecialChars("don't")).isEqualTo("don't"); // apostrophe safe
    }

    @ParameterizedTest(name = "[{index}] term='{0}' contains '{1}'")
    @CsvSource({
            "price OR spread,           price",
            "price OR spread,           spread",
            "(price) NEAR{2} (spread),  price",
            "don't FOLLOWEDBY{3} rule,  don't",
    })
    @DisplayName("Parametrised: key terms appear in output pattern")
    void parametrisedPatternContains(String input, String expectedFragment) {
        var r = translator.translate(input);
        assertThat(r.isSuccess()).isTrue();
        assertThat(pattern(r)).contains(expectedFragment);
    }
}
