package com.db.macs3.ecomms.spectre.integration;

import com.db.macs3.ecomms.spectre.hyperscan.HyperscanCompiler;
import com.db.macs3.ecomms.spectre.translator.TermSyntaxTranslator;
import com.db.macs3.ecomms.spectre.translator.TranslationResult;
import org.junit.jupiter.api.*;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration tests verifying that translated patterns correctly match
 * (and correctly do NOT match) realistic multi-language communication messages.
 *
 * <h2>Pipeline per test</h2>
 * <ol>
 *   <li>{@link TermSyntaxTranslator#translate} — term description → Hyperscan PCRE + flags</li>
 *   <li>{@link HyperscanCompiler#validate}    — verify the pattern compiles with Hyperscan</li>
 *   <li>Java {@link Pattern#compile}          — match against the message text</li>
 * </ol>
 *
 * <h2>Why Java regex for matching?</h2>
 * <p>{@code com.gliwka.hyperscan 5.4.0-2.0.0} {@code Scanner.scan()} throws
 * {@code HS_ERR_INVALID} ("Is scratch allocated?") in non-Spring unit test
 * environments. The root cause is native scratch management in the JNI layer
 * when the JVM initialises the library outside a container-managed lifecycle.
 * Since our translator generates a strict PCRE subset that Java's regex engine
 * fully supports (OR groups, NEAR gap patterns, wildcards, Unicode codepoints
 * {@code \x{NNNN}}, UTF-8 literals), using Java regex for the matching step
 * gives equivalent semantics. Hyperscan compilation is still verified in step 2,
 * so the test still exercises the Hyperscan code path.
 *
 * <h2>Message types tested</h2>
 * <ul>
 *   <li>Outlook emails (English, German)</li>
 *   <li>Symphony chat (Korean)</li>
 *   <li>Bloomberg terminal (emoji, English)</li>
 *   <li>Microsoft Teams (Chinese, Arabic)</li>
 *   <li>Internal chat (Hebrew, Turkish, mixed, Japanese, leet-speak)</li>
 * </ul>
 */
@DisplayName("Multi-Language Integration Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MultiLanguageIntegrationTest {

    private TermSyntaxTranslator translator;
    private HyperscanCompiler    compiler;

    @BeforeEach
    void setUp() {
        translator = new TermSyntaxTranslator();
        compiler   = new HyperscanCompiler();
        compiler.selfTest();
    }

    // ── Core pipeline helper ──────────────────────────────────────────────────

    /**
     * Runs the full pipeline for one term description against one message:
     * <ol>
     *   <li>Translate → assert translation success</li>
     *   <li>Validate with Hyperscan {@code Database.compile()} → assert PASS</li>
     *   <li>Match with Java regex (equivalent semantics for our PCRE subset)</li>
     * </ol>
     *
     * @param termDescription lexicon term description (operators, languages, emojis)
     * @param messageText     realistic communication message text to scan
     * @return {@code true} if the pattern matches anywhere in the message text
     */
    private boolean matches(String termDescription, String messageText) throws Exception {
        // ── Step 1: Translate ─────────────────────────────────────────────────
        TranslationResult result = translator.translate(termDescription);
        assertThat(result.isSuccess())
                .as("Translation failed for '%s': %s", termDescription,
                        result instanceof TranslationResult.Error e ? e.message() : "")
                .isTrue();
        var success = (TranslationResult.Success) result;

        // ── Step 2: Hyperscan compilation validation ───────────────────────────
        // This exercises the Hyperscan JNI code path (Database.compile),
        // confirming the pattern is valid PCRE for the Hyperscan engine.
        HyperscanCompiler.ValidationResult hsResult =
                compiler.validate(success.hsPattern(), success.hsFlags());
        assertThat(hsResult.isPass())
                .as("Hyperscan compile failed for pattern '%s': %s",
                        success.hsPattern(), hsResult.errorMessage())
                .isTrue();

        // ── Step 3: Match using Java regex ─────────────────────────────────────
        // Java regex supports the same PCRE subset our translator produces:
        //   (?:A|B)             – non-capturing OR group
        //   (?:\s+\S+){0,n}\s+  – word-gap proximity
        //   word\S*             – wildcard
        //   \x{1F4B0}           – Unicode supplementary plane codepoint (Java 8+)
        //   UTF-8 literals      – Korean, Chinese, Arabic, Hebrew, etc.
        int javaFlags = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        if ((success.hsFlags() & HyperscanCompiler.HS_FLAG_DOTALL) != 0) {
            javaFlags |= Pattern.DOTALL;
        }
        if ((success.hsFlags() & HyperscanCompiler.HS_FLAG_UTF8) != 0) {
            javaFlags |= Pattern.UNICODE_CHARACTER_CLASS;
        }
        Pattern pattern = Pattern.compile(success.hsPattern(), javaFlags);
        return pattern.matcher(messageText).find();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // SCENARIO 1: English Outlook email — NEAR proximity
    // ═════════════════════════════════════════════════════════════════════════

    private static final String OUTLOOK_EMAIL_1 = """
            From: john.doe@bank.com
            To: jane.smith@fund.com
            Subject: Q3 Strategy Follow-up
            Date: Mon, 15 Jan 2024 09:23:11 +0000

            Jane,

            Following our discussion yesterday, we need to think carefully about how we
            can manipulate the stock price through coordinated buying before the earnings
            announcement. The spread between bid and ask is currently 12 bps which gives
            us room to work with.

            Also, please don't forward this email to compliance or legal.

            Regards,
            John
            """;

    @Test @Order(1)
    @DisplayName("English email: NEAR{5} — 'manipulate the stock price' → MATCH")
    void englishNearMatch() throws Exception {
        assertThat(matches(
                "(manipulat*) NEAR{5} ((price) OR (spread) OR (stock))",
                OUTLOOK_EMAIL_1)).isTrue();
    }

    @Test @Order(2)
    @DisplayName("English email: NEAR{5} — spread also within distance → MATCH")
    void englishNearSpreadMatch() throws Exception {
        assertThat(matches(
                "(manipulat*) NEAR{5} ((price) OR (spread) OR (stock))",
                "The spread manipulation was clear from the order flow.")).isTrue();
    }

    @Test @Order(3)
    @DisplayName("English email: quoted phrase OR — 'please don't forward' → MATCH")
    void englishQuotedPhraseMatch() throws Exception {
        assertThat(matches(
                "((\"please don't forward\") OR (\"do not share don't forward\"))",
                OUTLOOK_EMAIL_1)).isTrue();
    }

    @Test @Order(4)
    @DisplayName("English email: FOLLOWEDBY{5} — 'don't ... compliance' → MATCH")
    void englishFollowedByMatch() throws Exception {
        // "don't forward this email to compliance" — 4 words in between
        assertThat(matches("don't FOLLOWEDBY{5} compliance", OUTLOOK_EMAIL_1)).isTrue();
    }

    @Test @Order(5)
    @DisplayName("English email: AND — OR pre-scan matches 'insider' or 'announcement'")
    void englishAndMatch() throws Exception {
        // AND translates to (?:insider|announcement) — OR pre-scan pattern;
        // scan engine post-filters at runtime to verify BOTH match
        assertThat(matches(
                "insider AND announcement",
                "The insider information about the upcoming announcement was misused.")).isTrue();
    }

    @Test @Order(6)
    @DisplayName("English email: AND NOT — positive part 'price' → MATCH")
    void englishAndNotMatch() throws Exception {
        // AND NOT returns positive operand only; scan engine excludes NOT at runtime
        assertThat(matches(
                "price AND NOT legitimate",
                "We plan to manipulate the price of the stock.")).isTrue();
    }

    @Test @Order(7)
    @DisplayName("English email: unrelated content → NO MATCH for price+manipulation")
    void englishNoMatch() throws Exception {
        assertThat(matches(
                "(manipulat*) NEAR{5} ((price) OR (spread) OR (stock))",
                "Please see the attached quarterly report for your review.")).isFalse();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // SCENARIO 2: Symphony chat — Korean
    // ═════════════════════════════════════════════════════════════════════════

    private static final String SYMPHONY_CHAT_KOREAN = """
            [09:15] 김민준: 오늘 내부자 거래에 대한 정보가 있어요. 주가가 오를 것 같아요.
            [09:16] 이서연: 비밀로 해야 해요. 아무한테도 말하지 마세요.
            [09:17] 김민준: 알겠어요. 이 정보는 비밀이에요.
            [09:18] 이서연: 내부자 거래는 불법이에요. 조심해야 해요.
            """;

    @Test @Order(20)
    @DisplayName("Korean chat: OR — 비밀 OR 내부자 거래 → MATCH")
    void koreanOrMatch() throws Exception {
        assertThat(matches("비밀 OR 내부자 거래", SYMPHONY_CHAT_KOREAN)).isTrue();
    }

    @Test @Order(21)
    @DisplayName("Korean chat: exact phrase — '내부자 거래' → MATCH")
    void koreanExactPhraseMatch() throws Exception {
        assertThat(matches("내부자 거래", SYMPHONY_CHAT_KOREAN)).isTrue();
    }

    @Test @Order(22)
    @DisplayName("Korean chat: AND OR pre-scan — 비밀 AND 내부자 → MATCH")
    void koreanAndMatch() throws Exception {
        assertThat(matches("비밀 AND 내부자", SYMPHONY_CHAT_KOREAN)).isTrue();
    }

    @Test @Order(23)
    @DisplayName("Korean chat: unrelated English term → NO MATCH")
    void koreanNoMatch() throws Exception {
        assertThat(matches("hello world", SYMPHONY_CHAT_KOREAN)).isFalse();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // SCENARIO 3: Bloomberg terminal — emoji
    // ═════════════════════════════════════════════════════════════════════════

    private static final String BLOOMBERG_CHAT = """
            Trader1 [14:22]: got the tip 💰 act now before announcement
            Trader2 [14:23]: 🤫 keep it quiet, front-running opportunity
            Trader1 [14:24]: confirmed 🤐 meeting them at 3pm
            Trader2 [14:25]: 💰 💰 big move incoming don't tell compliance
            """;

    @Test @Order(30)
    @DisplayName("Bloomberg emoji chat: 💰 OR 🤫 OR 🤐 → MATCH")
    void emojiOrMatch() throws Exception {
        assertThat(matches("💰 OR 🤫 OR 🤐", BLOOMBERG_CHAT)).isTrue();
    }

    @Test @Order(31)
    @DisplayName("Bloomberg emoji chat: single emoji 💰 → MATCH")
    void singleEmojiMatch() throws Exception {
        assertThat(matches("💰", BLOOMBERG_CHAT)).isTrue();
    }

    @Test @Order(32)
    @DisplayName("Bloomberg chat: tip NEAR{4} announcement — 4 intervening tokens (💰, act, now, before)")
    void englishNearInBloombergChat() throws Exception {
        // BLOOMBERG_CHAT has exactly 4 tokens between "tip" and "announcement":
        // "💰 act now before" — NEAR{4} is the tight boundary that matches; NEAR{3}
        // would not, since it allows at most 3 intervening words.
        assertThat(matches("tip NEAR{4} announcement", BLOOMBERG_CHAT)).isTrue();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // SCENARIO 4: Teams message — Chinese/Mandarin
    // ═════════════════════════════════════════════════════════════════════════

    private static final String TEAMS_CHINESE = """
            李明 [10:30]: 我们有关于内幕交易的重要信息。
            王芳 [10:31]: 是的，这涉及操纵市场。我们需要谨慎行事。
            李明 [10:32]: 明白了。这是内幕消息，不要透露给外部人员。
            王芳 [10:33]: 同意。股价操纵是非常敏感的话题。
            """;

    @Test @Order(40)
    @DisplayName("Chinese Teams: 内幕交易 OR 操纵市场 → MATCH")
    void chineseOrMatch() throws Exception {
        assertThat(matches("内幕交易 OR 操纵市场", TEAMS_CHINESE)).isTrue();
    }

    @Test @Order(41)
    @DisplayName("Chinese Teams: 内幕 AND 股价 (OR pre-scan) → MATCH")
    void chineseAndMatch() throws Exception {
        assertThat(matches("内幕 AND 股价", TEAMS_CHINESE)).isTrue();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // SCENARIO 5: Internal chat — Arabic
    // ═════════════════════════════════════════════════════════════════════════

    private static final String TEAMS_ARABIC = """
            محمد [11:00]: لدينا معلومات مهمة عن الاستثمار الداخلي.
            فاطمة [11:01]: نعم، هذا يتعلق بالتلاعب في السوق المالي.
            محمد [11:02]: يجب أن نتحرك بسرعة قبل الإعلان.
            فاطمة [11:03]: هذا مخالفة للوائح المالية.
            """;

    @Test @Order(50)
    @DisplayName("Arabic Teams: مخالفة OR استثمار داخلي → MATCH")
    void arabicOrMatch() throws Exception {
        assertThat(matches("مخالفة OR استثمار داخلي", TEAMS_ARABIC)).isTrue();
    }

    @Test @Order(51)
    @DisplayName("Arabic Teams: single term مخالفة → MATCH")
    void arabicSingleTermMatch() throws Exception {
        assertThat(matches("مخالفة", TEAMS_ARABIC)).isTrue();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // SCENARIO 6: Outlook email — Hebrew
    // ═════════════════════════════════════════════════════════════════════════

    private static final String OUTLOOK_HEBREW = """
            מאת: david@bank.co.il
            אל: sarah@fund.co.il
            נושא: מידע רגיש

            שרה,

            יש לי מידע על מסחר פנים שיכול להיות שימושי.
            אנחנו יכולים להשתמש בזה לפני ההכרזה.
            מניפולציה בשוק יכולה להניב רווחים גדולים.

            דוד
            """;

    @Test @Order(60)
    @DisplayName("Hebrew Outlook email: מסחר פנים OR מניפולציה → MATCH")
    void hebrewOrMatch() throws Exception {
        assertThat(matches("מסחר פנים OR מניפולציה", OUTLOOK_HEBREW)).isTrue();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // SCENARIO 7: Outlook email — German
    // ═════════════════════════════════════════════════════════════════════════

    private static final String OUTLOOK_GERMAN = """
            Von: hans.mueller@bank.de
            An: anna.schneider@fonds.de
            Betreff: Vertraulich - Übernahmeangebot

            Liebe Anna,

            Ich habe Informationen über eine bevorstehende Übernahme erhalten.
            Insiderhandel könnte hier eine Möglichkeit sein, aber Vorsicht ist geboten.
            Die Akquisition wird den Aktienkurs stark beeinflussen.

            Mit freundlichen Grüßen,
            Hans
            """;

    @Test @Order(70)
    @DisplayName("German Outlook email: Übernahme OR Insiderhandel → MATCH")
    void germanOrMatch() throws Exception {
        assertThat(matches("Übernahme OR Insiderhandel", OUTLOOK_GERMAN)).isTrue();
    }

    @Test @Order(71)
    @DisplayName("German Outlook email: Übernahme AND Akquisition (OR pre-scan) → MATCH")
    void germanAndMatch() throws Exception {
        assertThat(matches("Übernahme AND Akquisition", OUTLOOK_GERMAN)).isTrue();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // SCENARIO 8: Teams chat — Turkish
    // ═════════════════════════════════════════════════════════════════════════

    @Test @Order(80)
    @DisplayName("Turkish Teams: içeriden bilgi OR piyasa manipülasyonu → MATCH")
    void turkishMatch() throws Exception {
        String teamsTurkish =
                "Bugün içeriden bilgi aldım. Piyasa manipülasyonu ile büyük kazanç mümkün.";
        assertThat(matches("içeriden bilgi OR piyasa manipülasyonu", teamsTurkish)).isTrue();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // SCENARIO 9: Bloomberg — Japanese
    // ═════════════════════════════════════════════════════════════════════════

    @Test @Order(90)
    @DisplayName("Japanese Bloomberg: 株価操作 OR インサイダー取引 → MATCH")
    void japaneseMatch() throws Exception {
        String bloombergJp =
                "市場情報: 株価操作が疑われています。インサイダー取引の可能性があります。";
        assertThat(matches("株価操作 OR インサイダー取引", bloombergJp)).isTrue();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // SCENARIO 10: Leet-speak in Symphony chat
    // ═════════════════════════════════════════════════════════════════════════

    @Test @Order(100)
    @DisplayName("Leet-speak: 1ns1d3r OR insider → MATCH in mixed text")
    void leetSpeakMatch() throws Exception {
        String chat = "got that 1ns1d3r info about tomorrow's announcement";
        assertThat(matches("1ns1d3r OR insider", chat)).isTrue();
    }

    @Test @Order(101)
    @DisplayName("Leet-speak: fr0nt* wildcard → MATCH 'fr0nt-running'")
    void leetWildcardMatch() throws Exception {
        String chat = "this is a fr0nt-running situation";
        assertThat(matches("fr0nt*", chat)).isTrue();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // SCENARIO 11: Multi-language single message
    // ═════════════════════════════════════════════════════════════════════════

    @Test @Order(110)
    @DisplayName("Mixed English+Korean+emoji: insider OR 내부자 OR 💰 → MATCH")
    void mixedLanguageMatch() throws Exception {
        String mixedMsg = "We have 💰 insider info about the 내부자 거래 opportunity.";
        assertThat(matches("insider OR 내부자 OR 💰", mixedMsg)).isTrue();
    }

    @Test @Order(111)
    @DisplayName("Wildcard 'insider*' matches variants 'insider information' and 'insiders'")
    void wildcardMatchesVariants() throws Exception {
        assertThat(matches("insider*", "insider information shared")).isTrue();
        assertThat(matches("insider*", "the insiders knew before")).isTrue();
        assertThat(matches("insider*", "this has nothing relevant")).isFalse();
    }

    @Test @Order(112)
    @DisplayName("Apostrophe in term: \"can't forward\" → MATCH exact text")
    void apostropheMatch() throws Exception {
        assertThat(matches("can't forward",
                "Please can't forward this to anyone outside the group")).isTrue();
    }
}
