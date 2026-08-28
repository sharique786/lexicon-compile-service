package com.db.macs3.ecomms.spectre.integration;

import com.db.macs3.ecomms.spectre.ResolvedPatternMatcher;
import com.db.macs3.ecomms.spectre.hyperscan.HyperscanCompiler;
import com.db.macs3.ecomms.spectre.translator.TermSyntaxTranslator;
import com.db.macs3.ecomms.spectre.translator.TranslationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end proof that {@code resolvedPatterns} (produced by
 * {@link TermSyntaxTranslator}, which no longer compiles NEAR/FOLLOWEDBY/
 * AND NOT into Hyperscan regex) is actually SUFFICIENT for a downstream
 * Java-regex-based consumer to reconstruct correct match/no-match
 * decisions — using {@link ResolvedPatternMatcher}, the reference
 * implementation of exactly that downstream technique (see its class
 * Javadoc, and {@code src/test/java/.../TokenProximityMatcher.java} for
 * the original seed prototype).
 *
 * <p>This is the replacement for what {@code MultiLanguageIntegrationTest}'s
 * NEAR/FOLLOWEDBY {@code matches()} assertions used to verify directly
 * against a single gap-embedded Hyperscan pattern — that pattern no longer
 * exists for a term with proximity structure (see
 * {@code PatternDecomposer} class Javadoc), so proximity correctness is now
 * proven here instead, via {@code regexPattern}/{@code resolvedPatterns} +
 * this reference matcher.
 *
 * <p>Every test drives the REAL pipeline (
 * {@link TermSyntaxTranslator#translate}) — nothing here hand-builds a
 * {@code resolvedPatterns} string; every fixture is exactly what the
 * compile service itself would return for that term.
 */
@DisplayName("resolvedPatterns end-to-end: real regexPattern/resolvedPatterns + ResolvedPatternMatcher")
class ResolvedPatternMatchingIntegrationTest {

    private TermSyntaxTranslator translator;

    @BeforeEach
    void setUp() {
        HyperscanCompiler compiler = new HyperscanCompiler();
        compiler.selfTest();
        translator = new TermSyntaxTranslator(compiler);
    }

    /**
     * Runs the real pipeline for {@code term}, parses its
     * {@code resolvedPattern} via {@link ResolvedPatternMatcher#parse}, and
     * evaluates it against {@code messageText}.
     */
    private boolean matches(String term, String messageText) {
        TranslationResult result = translator.translate(term);
        assertThat(result.isSuccess())
                .as("Translation failed for '%s': %s", term,
                        result instanceof TranslationResult.Error e ? e.message() : "")
                .isTrue();
        var success = (TranslationResult.Success) result;
        ResolvedPatternMatcher.Node tree =
                ResolvedPatternMatcher.parse(success.resolvedPattern(), success.hsFlags());
        return ResolvedPatternMatcher.matches(tree, messageText);
    }

    // ═════════════════════════════════════════════════════════════════════
    // The user's three worked examples, verbatim
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Example 1: ((bash)) FOLLOWEDBY{30} ((fuck) OR (fck))")
    class Example1 {

        private static final String TERM = "((bash)) FOLLOWEDBY{30} ((fuck) OR (fck))";

        @Test
        @DisplayName("bash ... fuck, well within 30 words → MATCH")
        void withinDistance_matches() {
            assertThat(matches(TERM, "someone said bash the system before they said fuck this")).isTrue();
        }

        @Test
        @DisplayName("fck (the OR alternative) also matches")
        void orAlternative_matches() {
            assertThat(matches(TERM, "bash it, fck it, whatever")).isTrue();
        }

        @Test
        @DisplayName("fuck before bash (wrong order) → NO MATCH — FOLLOWEDBY is directional")
        void wrongOrder_noMatch() {
            assertThat(matches(TERM, "fuck this, i will bash the system")).isFalse();
        }

        @Test
        @DisplayName("neither word present → NO MATCH")
        void neitherPresent_noMatch() {
            assertThat(matches(TERM, "this message is entirely unrelated")).isFalse();
        }

        @Test
        @DisplayName("BOUNDARY: exactly 30 intervening words → MATCH; 31 → NO MATCH")
        void exactBoundary() {
            String thirtyWords = "w1 w2 w3 w4 w5 w6 w7 w8 w9 w10 w11 w12 w13 w14 w15 "
                    + "w16 w17 w18 w19 w20 w21 w22 w23 w24 w25 w26 w27 w28 w29 w30";
            assertThat(matches(TERM, "bash " + thirtyWords + " fuck")).isTrue();
            assertThat(matches(TERM, "bash " + thirtyWords + " w31 fuck")).isFalse();
        }
    }

    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Example 2: AND NOT wrapping a chained FOLLOWEDBY excluded side")
    class Example2 {

        // NOTE: the user's literal Example 2 term wraps only the FIRST OR-group after
        // "AND NOT", with the rest of the FOLLOWEDBY chain trailing outside it — that
        // predates this codebase's standalone-NOT grammar tightening (a separate,
        // earlier change), which now requires NOT to be followed by exactly ONE
        // parenthesised group. The syntactically-correct equivalent wraps the WHOLE
        // excluded chain in that one group — same semantics, same 3-leaf split, same
        // resolvedPatterns shape (confirmed against LexiconCompileBundleServiceTest's
        // "WORKED EXAMPLE" test, which already exercises this exact term).
        private static final String TERM =
                "(insider AND NOT ((wordA word B OR wordC* wordD OR wordE* wordF OR wordG) "
                        + "FOLLOWEDBY{2} (wordH* OR wordI wordJ* wordK OR wordL* wordM OR wordN) "
                        + "FOLLOWEDBY{2} (wordO* OR wordP* wordQ OR wordR* wordS OR wordT)))";

        @Test
        @DisplayName("required present, excluded chain NOT satisfied (wordG alone, far from wordH*) → MATCH")
        void requiredPresentExcludedChainBroken_matches() {
            assertThat(matches(TERM,
                    "insider trading happened. wordG was mentioned much later in an unrelated context "
                            + "with no wordH anywhere near it")).isTrue();
        }

        @Test
        @DisplayName("required present, excluded chain FULLY satisfied (wordG wordH wordO in sequence, "
                + "within distance) → NO MATCH")
        void requiredPresentExcludedChainSatisfied_noMatch() {
            assertThat(matches(TERM, "insider trading: wordG wordH wordO")).isFalse();
        }

        @Test
        @DisplayName("required absent → NO MATCH regardless of the excluded side")
        void requiredAbsent_noMatch() {
            assertThat(matches(TERM, "nothing relevant here at all")).isFalse();
        }
    }

    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Example 3: (ihr Gespräch OR Gespraech OR *reden) NEAR{30} (threema OR threema messenger OR threema IM)")
    class Example3 {

        private static final String TERM =
                "(ihr Gespräch OR Gespraech OR *reden) NEAR{30} (threema OR threema messenger OR threema IM)";

        @Test
        @DisplayName("left-then-right order → MATCH")
        void leftThenRight_matches() {
            assertThat(matches(TERM, "we discussed ihr Gespräch and then used threema to talk")).isTrue();
        }

        @Test
        @DisplayName("right-then-left order → MATCH too — NEAR is bidirectional")
        void rightThenLeft_matches() {
            assertThat(matches(TERM, "they used threema messenger before ihr Gespräch happened")).isTrue();
        }

        @Test
        @DisplayName("wildcard OR-alternative *reden matches its expansion")
        void wildcardAlternative_matches() {
            assertThat(matches(TERM, "wir reden über threema jetzt")).isTrue();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Realistic message fixtures — the replacement for MultiLanguageIntegrationTest's
    // NEAR/FOLLOWEDBY assertions, which can no longer be verified via a single
    // gap-embedded hsPattern (see PatternDecomposer class Javadoc).
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Realistic fixtures — same scenarios MultiLanguageIntegrationTest used to verify via a single gap-regex")
    class RealisticFixtures {

        private static final String OUTLOOK_EMAIL_1 = """
                From: john.doe@bank.com
                To: jane.smith@fund.com
                Subject: Q3 Strategy Follow-up

                Jane,

                Following our discussion yesterday, we need to think carefully about how we
                can manipulate the stock price through coordinated buying before the earnings
                announcement. The spread between bid and ask is currently 12 bps which gives
                us room to work with.

                Also, please don't forward this email to compliance or legal.

                Regards,
                John
                """;

        @Test
        @DisplayName("English email: NEAR{5} — 'manipulate the stock price' → MATCH")
        void englishNearMatch() {
            assertThat(matches("(manipulat*) NEAR{5} ((price) OR (spread) OR (stock))", OUTLOOK_EMAIL_1)).isTrue();
        }

        @Test
        @DisplayName("English email: FOLLOWEDBY{5} — 'don't ... compliance' (4 words between) → MATCH")
        void englishFollowedByMatch() {
            assertThat(matches("don't FOLLOWEDBY{5} compliance", OUTLOOK_EMAIL_1)).isTrue();
        }

        @Test
        @DisplayName("English email: unrelated content → NO MATCH for price+manipulation")
        void englishNoMatch() {
            assertThat(matches(
                    "(manipulat*) NEAR{5} ((price) OR (spread) OR (stock))",
                    "Please see the attached quarterly report for your review.")).isFalse();
        }

        private static final String BLOOMBERG_CHAT = """
                Trader1 [14:22]: got the tip act now really before announcement
                Trader2 [14:23]: keep it quiet, front-running opportunity
                """;

        @Test
        @DisplayName("Bloomberg chat: tip NEAR{4} announcement — exactly 4 intervening tokens (act, now, "
                + "really, before) → MATCH, but NEAR{3} does NOT — the tight boundary "
                + "MultiLanguageIntegrationTest used to verify via a single gap-regex is now verified "
                + "through the split leaves + resolvedPatterns instead")
        void tightBoundary() {
            assertThat(matches("tip NEAR{4} announcement", BLOOMBERG_CHAT)).isTrue();
            assertThat(matches("tip NEAR{3} announcement", BLOOMBERG_CHAT)).isFalse();
        }
    }
}
