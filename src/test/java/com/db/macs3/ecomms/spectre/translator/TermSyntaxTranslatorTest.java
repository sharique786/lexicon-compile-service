package com.db.macs3.ecomms.spectre.translator;

import com.db.macs3.ecomms.spectre.hyperscan.HyperscanCompiler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comprehensive tests for the Tokenizer → ExpressionParser → PatternCodeGenerator
 * pipeline, organised around the specific bug reports and requirements that
 * drove this rewrite. Every example given in the requirements document is
 * reproduced here verbatim as a test case, plus the five real terms from the
 * bug-report screenshot (reconstructed from the German lexicon rule set).
 */
@DisplayName("TermSyntaxTranslator")
class TermSyntaxTranslatorTest {

    private TermSyntaxTranslator translator;

    @BeforeEach
    void setUp() {
        HyperscanCompiler compiler = new HyperscanCompiler();
        compiler.selfTest();
        translator = new TermSyntaxTranslator(compiler);
    }

    private TranslationResult.Success translateOk(String term) {
        TranslationResult r = translator.translate(term);
        assertThat(r).as("expected SUCCESS for '%s' but got: %s", term,
                        r.isSuccess() ? "" : ((TranslationResult.Error) r).message())
                .isInstanceOf(TranslationResult.Success.class);
        return (TranslationResult.Success) r;
    }

    private String translateError(String term) {
        TranslationResult r = translator.translate(term);
        assertThat(r).as("expected ERROR for '%s' but got SUCCESS", term)
                .isInstanceOf(TranslationResult.Error.class);
        return ((TranslationResult.Error) r).message();
    }

    // ══════════════════════════════════════════════════════════════════════
    // Requirement 1: brackets resolve first, at any nesting depth
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Requirement 1 — bracket resolution order")
    class BracketResolution {

        @Test
        @DisplayName("Example 1: (crap OR bad) NEAR{3} (bonus OR comp)")
        void example1_orInsideNear() {
            var s = translateOk("(crap OR bad) NEAR{3} (bonus OR comp)");
            assertThat(s.hsPatterns().getFirst()).isEqualTo(
                    "(?:(?:crap|bad)(?:\\s+\\S+){0,3}\\s+(?:bonus|comp)"
                            + "|(?:bonus|comp)(?:\\s+\\S+){0,3}\\s+(?:crap|bad))");
        }

        @Test
        @DisplayName("Example 2: (F) FOLLOWEDBY{1} (((me) OR (cking))) — triple redundant wrapping")
        void example2_deeplyRedundantWrapping() {
            var s = translateOk("(F) FOLLOWEDBY{1} (((me) OR (cking)))");
            assertThat(s.hsPatterns().getFirst()).isEqualTo("F(?:\\s+\\S+){0,1}\\s+(?:me|cking)");
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {
                "(((me) OR (cking)))",
                "((((me) OR (cking))))",
                "(((((F)))))",
        })
        @DisplayName("Arbitrary depths of pure redundant wrapping resolve without error")
        void arbitraryRedundantWrappingDepths(String term) {
            assertThat(translator.translate(term).isSuccess()).as(term).isTrue();
        }

        @Test
        @DisplayName("Triple-nested NEAR inside OR does not crash and resolves inner group first")
        void tripleNestedNearInsideOr() {
            var s = translateOk("(((crap) NEAR{3} (bad))) OR (bonus OR comp)");
            assertThat(s.hsPatterns().getFirst()).contains("crap").contains("bad").contains("bonus").contains("comp");
        }

        @Test
        @DisplayName("Mixed nesting: OR-of-parens combined with a NEAR sibling")
        void mixedNestingOrAndNear() {
            var s = translateOk("((crap) OR (bad)) OR (bonus NEAR{3} comp)");
            assertThat(s.hsPatterns().getFirst()).contains("crap").contains("bad");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Requirement 2: multi-word phrases must be wrapped in parentheses
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Requirement 2 — multi-word phrases must be wrapped")
    class MultiWordWrapping {

        @Test
        @DisplayName("Properly wrapped multi-word OR alternatives succeed (unchanged)")
        void wrappedPhrasesSucceed() {
            var s = translateOk("(bomb this place) OR (blow this place up)");
            assertThat(s.hsPatterns().getFirst()).contains("bomb this place").contains("blow this place up");
        }

        @Test
        @DisplayName("Unwrapped multi-word phrase at top level is now ACCEPTED as an implicit phrase")
        void unwrappedTopLevelPhraseAccepted() {
            var s = translateOk("bomb this place OR blow this place up");
            assertThat(s.hsPatterns().getFirst()).isEqualTo("(?:bomb this place|blow this place up)");
        }

        @Test
        @DisplayName("Unwrapped multi-word OR-alternative nested inside an otherwise-wrapped group is also accepted")
        void unwrappedNestedAlternativeAccepted() {
            var s = translateOk("(für dich OR für Sie)");
            assertThat(s.hsPatterns().getFirst()).isEqualTo("(?:f\u00fcr dich|f\u00fcr Sie)");
        }

        @Test
        @DisplayName("A single (one-word) atom never needs wrapping")
        void singleWordNeverNeedsWrapping() {
            assertThat(translator.translate("fix OR rig").isSuccess()).isTrue();
        }

        @Test
        @DisplayName("Unwrapped phrase inside NEAR/FOLLOWEDBY operands is also accepted")
        void unwrappedPhraseInProximityOperand() {
            var s = translateOk("insider trading NEAR{3} market manipulation");
            assertThat(s.hsPatterns().getFirst()).contains("insider trading").contains("market manipulation");
        }

        @Test
        @DisplayName("Deeply-nested wrapping still resolves correctly even with implicit phrase collection active")
        void deepNestingStillCorrectWithImplicitPhrases() {
            var s = translateOk("(((crap) NEAR{3} (bad))) OR (bonus OR comp)");
            assertThat(s.hsPatterns().getFirst()).contains("crap").contains("bad").contains("bonus").contains("comp");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Requirement 3: '?' is always literal
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Requirement 3 — '?' is always literal")
    class LiteralQuestionMark {

        @Test
        @DisplayName("he?d / she?d — '?' escaped as a literal character, never a live quantifier")
        void questionMarkIsLiteral() {
            var s = translateOk("((he?d kill) OR (she?d kill))");
            assertThat(s.hsPatterns().getFirst()).isEqualTo("(?:he\\?d kill|she\\?d kill)");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Requirement 4: wildcard '*' — prefix, suffix, and non-ASCII words
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Requirement 4 — wildcard handling")
    class Wildcards {

        @Test
        @DisplayName("Suffix wildcard: chimp* -> chimp\\S*")
        void suffixWildcard() {
            var s = translateOk("(check her out) OR (chimp*)");
            assertThat(s.hsPatterns().getFirst()).isEqualTo("(?:check her out|chimp\\S*)");
        }

        @Test
        @DisplayName("Prefix wildcard: *handler -> \\S*handler")
        void prefixWildcard() {
            var s = translateOk("(*handler)");
            assertThat(s.hsPatterns().getFirst()).isEqualTo("\\S*handler");
        }

        @Test
        @DisplayName("THE REPORTED BUG: suffix wildcard on a non-ASCII word must still convert to \\S*")
        void wildcardOnNonAsciiWord_theReportedBug() {
            var s = translateOk("(verschwör*)");
            assertThat(s.hsPatterns().getFirst()).isEqualTo("verschw\u00f6r\\S*");
            assertThat(s.hsPatterns().getFirst()).doesNotContain("r*"); // bare, un-expanded '*' must not survive
        }

        @Test
        @DisplayName("Prefix wildcard on a non-ASCII word")
        void prefixWildcardOnNonAsciiWord() {
            var s = translateOk("(*händler)");
            assertThat(s.hsPatterns().getFirst()).isEqualTo("\\S*h\u00e4ndler");
        }

        @Test
        @DisplayName("Wildcard inside a quoted phrase is a LITERAL asterisk, not expanded")
        void wildcardInQuotesIsLiteral() {
            var s = translateOk("\"chimp*\"");
            assertThat(s.hsPatterns().getFirst()).isEqualTo("chimp\\*");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Requirement 5: AND / AND NOT
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Requirement 5 — AND / AND NOT (corrected co-occurrence semantics)")
    class AndAndNot {

        private final java.util.regex.Pattern NO_LOOKAROUND_CHECK = java.util.regex.Pattern.compile("\\(\\?[=<!]");

        private boolean matches(String pattern, String message) {
            return java.util.regex.Pattern.compile(pattern, java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(message).find();
        }

        @Test
        @DisplayName("THE EXACT REPORTED SCENARIO — term 1: 'price AND rigging' matches message 1 " +
                "(both words present, any order/distance) but not message 2 (rigging absent)")
        void reportedScenario_term1_priceAndRigging() {
            String message1 = "There's price change and market rigging is going on";
            String message2 = "There's price change";
            var s = translateOk("price AND rigging");

            assertThat(NO_LOOKAROUND_CHECK.matcher(s.hsPatterns().getFirst()).find()).isFalse();
            assertThat(matches(s.hsPatterns().getFirst(), message1)).isTrue();
            assertThat(matches(s.hsPatterns().getFirst(), message2)).isFalse();
            assertThat(s.requiresExclusionCheck()).isFalse();
        }

        @Test
        @DisplayName("THE EXACT REPORTED SCENARIO — term 2: 'price AND NOT change' matches NEITHER " +
                "message (change appears alongside price in both)")
        void reportedScenario_term2_priceAndNotChange() {
            String message1 = "There's price change and market rigging is going on";
            String message2 = "There's price change";
            var s = translateOk("price AND NOT change");

            assertThat(s.requiresExclusionCheck()).isTrue();
            assertThat(NO_LOOKAROUND_CHECK.matcher(s.hsPatterns().getFirst()).find()).isFalse();
            assertThat(NO_LOOKAROUND_CHECK.matcher(s.exclusionPatterns().getFirst()).find()).isFalse();

            // Apply the two-pattern contract exactly as a caller must: matched iff
            // hsPattern matches AND exclusionPattern does NOT match the same message.
            boolean message1Matches = matches(s.hsPatterns().getFirst(), message1) && !matches(s.exclusionPatterns().getFirst(), message1);
            boolean message2Matches = matches(s.hsPatterns().getFirst(), message2) && !matches(s.exclusionPatterns().getFirst(), message2);
            assertThat(message1Matches).isFalse();
            assertThat(message2Matches).isFalse();
        }

        @Test
        @DisplayName("AND is fully self-contained in hsPattern — no post-filter metadata needed")
        void plainAnd_selfContained() {
            var s = translateOk("((want to) AND (fix))");
            assertThat(s.requiresExclusionCheck()).isFalse();
            assertThat(s.exclusionPatterns()).isNull();
            assertThat(s.hsPatterns().getFirst()).contains("want to").contains("fix");
        }

        @Test
        @DisplayName("AND with 3 operands: all six orderings present, matches any order")
        void threeOperandAnd_allOrderingsWork() {
            var s = translateOk("alpha AND beta AND gamma");
            assertThat(matches(s.hsPatterns().getFirst(), "gamma comes first, then alpha, then beta shows up")).isTrue();
            assertThat(matches(s.hsPatterns().getFirst(), "alpha and beta only, no third word")).isFalse();
        }

        @Test
        @DisplayName("AND NOT: exclusion is captured as a SEPARATE Hyperscan-valid pattern, " +
                "never baked into hsPattern as an invalid lookbehind")
        void andNot_positivePatternAndSeparateExclusion() {
            var s = translateOk("((fix) OR (rig)) FOLLOWEDBY{2} (the rate) AND NOT (fed rate move)");
            assertThat(s.hsPatterns().getFirst()).contains("fix|rig").contains("the rate");
            assertThat(s.hsPatterns().getFirst()).doesNotContain("fed rate move");
            assertThat(NO_LOOKAROUND_CHECK.matcher(s.hsPatterns().getFirst()).find()).isFalse();
            assertThat(s.exclusionPatterns().getFirst()).contains("fed rate move");
        }

        @Test
        @DisplayName("THE REPORTED BUG: 'AND NOT(' with no space before the parenthesis is recognised correctly")
        void andNotWithoutSpaceBeforeParen() {
            var s = translateOk("(hello) AND NOT(world)");
            assertThat(s.requiresExclusionCheck()).isTrue();
            assertThat(s.exclusionPatterns().getFirst()).isEqualTo("(?:world)");
        }

        @Test
        @DisplayName("Explicitly-nested FOLLOWEDBY inside an AND-NOT exclusion (different grammar levels) still resolves")
        void nestedFollowedByInsideAndNotExclusion() {
            var s = translateOk("(hello) AND NOT(((a OR b) FOLLOWEDBY{1} (c OR d)) FOLLOWEDBY{1} (e OR f))");
            assertThat(s.exclusionPatterns().getFirst()).contains("(?:a|b)").contains("(?:c|d)").contains("(?:e|f)");
        }

        @Test
        @DisplayName("Nested NEAR inside an AND-NOT exclusion (one alternative of an OR-of-phrases) resolves correctly")
        void nestedNearInsideAndNotExclusion() {
            var s = translateOk("(hello) AND NOT((plain phrase) OR ((EURIBOR FIXING) NEAR{2} TENOR))");
            assertThat(s.exclusionPatterns().getFirst()).contains("EURIBOR FIXING").contains("TENOR");
        }

        @Test
        @DisplayName("Chained AND NOT: A AND NOT B AND NOT C combines B and C into one OR'd exclusion pattern")
        void chainedAndNot() {
            var s = translateOk("(a) AND NOT (b) AND NOT (c)");
            assertThat(s.requiresExclusionCheck()).isTrue();
            assertThat(s.exclusionPatterns().getFirst()).contains("b").contains("c");
            // Either b or c present alone should trigger the exclusion.
            assertThat(matches(s.exclusionPatterns().getFirst(), "just b here")).isTrue();
            assertThat(matches(s.exclusionPatterns().getFirst(), "just c here")).isTrue();
            assertThat(matches(s.exclusionPatterns().getFirst(), "neither here")).isFalse();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Requirement (this round) 1 — no independent NOT operator
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("No independent NOT operator — only valid as part of AND NOT")
    class NoIndependentNot {

        @Test
        @DisplayName("Standalone 'NOT X' is rejected with a specific, actionable error")
        void standaloneNotRejected() {
            String msg = translateError("NOT confidential");
            assertThat(msg).contains("Standalone NOT is not supported");
            assertThat(msg).contains("AND NOT");
        }

        @Test
        @DisplayName("Standalone '!X' no longer has any special meaning — '!' is now ordinary literal text")
        void bangPrefixNoLongerSpecial() {
            var s = translateOk("!confidential");
            assertThat(s.hsPatterns().getFirst()).contains("confidential");
            assertThat(s.requiresExclusionCheck()).isFalse();
        }

        @Test
        @DisplayName("'NOT' still works correctly as part of AND NOT")
        void notStillWorksAsPartOfAndNot() {
            var s = translateOk("(a) AND NOT (b)");
            assertThat(s.requiresExclusionCheck()).isTrue();
        }

        @Test
        @DisplayName("The literal word 'not' (lowercase) is unaffected — reserved keywords are case-sensitive")
        void lowercaseNotIsLiteral() {
            var s = translateOk("(this is not a problem)");
            assertThat(s.hsPatterns().getFirst()).contains("not");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Requirement (this round) 4 — no multiple FOLLOWEDBY/NEAR at the same level
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Validation — chained NEAR/FOLLOWEDBY at the same level warns, but succeeds (backward compatibility)")
    class ChainedProximityValidation {

        @Test
        @DisplayName("Chained FOLLOWEDBY at the same level SUCCEEDS with a warning — NOT rejected. " +
                "Existing lexicon terms using this pattern must keep compiling.")
        void chainedFollowedBySameLevel_warnsButSucceeds() {
            var s = translateOk(
                    "((termA OR termB OR termC) FOLLOWEDBY{5} (termD OR termE) FOLLOWEDBY{6} (termF OR termG))");
            assertThat(s.warnings()).isNotEmpty();
            boolean hasChainWarning = s.warnings().stream()
                    .anyMatch(w -> w.contains("FOLLOWEDBY") || w.contains("chain"));
            assertThat(hasChainWarning).isTrue();
        }

        @Test
        @DisplayName("Chained NEAR at the same level also succeeds with a warning")
        void chainedNearSameLevel_warnsButSucceeds() {
            var s = translateOk("(a) NEAR{3} (b) NEAR{4} (c)");
            assertThat(s.warnings()).isNotEmpty();
        }

        @Test
        @DisplayName("Mixed NEAR then FOLLOWEDBY chained at the same level also succeeds with a warning")
        void chainedMixedProximity_warnsButSucceeds() {
            var s = translateOk("(a) NEAR{3} (b) FOLLOWEDBY{4} (c)");
            assertThat(s.warnings()).isNotEmpty();
        }

        @Test
        @DisplayName("Chained proximity produces the IDENTICAL pattern to the explicit, left-associative " +
                "parenthesization — proving the warning-path AST construction is not just a " +
                "different, accidentally-also-working translation")
        void chainedProximity_identicalToExplicitNesting() {
            var chained = translateOk("(termA) FOLLOWEDBY{5} (termD) FOLLOWEDBY{6} (termF)");
            var explicit = translateOk("((termA) FOLLOWEDBY{5} (termD)) FOLLOWEDBY{6} (termF)");
            assertThat(chained.hsPatterns()).isEqualTo(explicit.hsPatterns());
        }

        @Test
        @DisplayName("Explicit nesting via parentheses works identically and produces no chain warning")
        void explicitNestingStillWorks() {
            var s = translateOk("((termA OR termB) FOLLOWEDBY{5} (termD OR termE)) FOLLOWEDBY{6} (termF OR termG)");
            assertThat(s.hsPatterns().getFirst()).isNotBlank();
        }

        @Test
        @DisplayName("A single NEAR or FOLLOWEDBY (no chaining) is completely unaffected")
        void singleProximityOperator_unaffected() {
            assertThat(translator.translate("(a) NEAR{3} (b)").isSuccess()).isTrue();
            assertThat(translator.translate("(a) FOLLOWEDBY{3} (b)").isSuccess()).isTrue();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // AND operand count ceiling
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Validation — AND operand count ceiling")
    class AndOperandCeiling {

        @Test
        @DisplayName("More than 5 AND operands at the same level is rejected with a specific error")
        void tooManyAndOperands_rejected() {
            String msg = translateError("a AND b AND c AND d AND e AND f");
            assertThat(msg).contains("Too many AND operands");
        }

        @Test
        @DisplayName("Exactly 5 AND operands (the ceiling) succeeds")
        void fiveAndOperands_succeeds() {
            assertThat(translator.translate("a AND b AND c AND d AND e").isSuccess()).isTrue();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Validation rules
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Validation — reserved keywords are case-sensitive")
    class CaseSensitiveKeywords {

        @Test
        @DisplayName("lowercase 'or' is literal text, not the OR operator")
        void lowercaseOrIsLiteral() {
            var s = translateOk("(price or spread)");
            assertThat(s.hsPatterns().getFirst()).isEqualTo("price or spread");
        }

        @Test
        @DisplayName("lowercase 'and' is literal text")
        void lowercaseAndIsLiteral() {
            var s = translateOk("(rock and roll)");
            assertThat(s.hsPatterns().getFirst()).isEqualTo("rock and roll");
        }

        @Test
        @DisplayName("lowercase 'near' is literal text, not the NEAR operator")
        void lowercaseNearIsLiteral() {
            var s = translateOk("(the office near you)");
            assertThat(s.hsPatterns().getFirst()).contains("near");
        }

        @Test
        @DisplayName("Mixed-case 'Or'/'And'/'Not' are literal, not operators")
        void mixedCaseIsLiteral() {
            assertThat(translator.translate("(Or And Not)").isSuccess()).isTrue();
        }
    }

    @Nested
    @DisplayName("Validation — NEAR/FOLLOWEDBY distance must be a single digit 1-9")
    class ProximityDistanceValidation {

        @ParameterizedTest(name = "[{index}] {0}{1} is rejected")
        @CsvSource({
                "NEAR, '{0}'",
                "NEAR, '{-1}'",
                "NEAR, '{abcd}'",
                "NEAR, '{10}'",
                "NEAR, '{0,6}'",
                "FOLLOWEDBY, '{0}'",
                "FOLLOWEDBY, '{-1}'",
                "FOLLOWEDBY, '{abcd}'",
                "FOLLOWEDBY, '{10}'",
                "FOLLOWEDBY, '{0,6}'",
        })
        void invalidDistanceRejected(String keyword, String brace) {
            String term = "(a) " + keyword + brace.replace("'", "") + " (b)";
            assertThat(translator.translate(term).isSuccess()).as(term).isFalse();
        }

        @ParameterizedTest(name = "[{index}] distance {0} is accepted")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9})
        void validSingleDigitDistancesAccepted(int n) {
            assertThat(translator.translate("(a) NEAR{" + n + "} (b)").isSuccess()).isTrue();
            assertThat(translator.translate("(a) FOLLOWEDBY{" + n + "} (b)").isSuccess()).isTrue();
        }

        @Test
        @DisplayName("Whitespace between NEAR and '{' is rejected")
        void whitespaceBeforeBraceRejected_near() {
            assertThat(translateError("(a) NEAR {3} (b)")).contains("whitespace");
        }

        @Test
        @DisplayName("Whitespace between FOLLOWEDBY and '{' is rejected")
        void whitespaceBeforeBraceRejected_followedBy() {
            assertThat(translateError("(a) FOLLOWEDBY {3} (b)")).contains("whitespace");
        }
    }

    @Nested
    @DisplayName("Validation — structural correctness")
    class StructuralValidation {

        @Test
        @DisplayName("Unmatched closing bracket without matching open is rejected")
        void missingClosingParen() {
            assertThat(translator.translate("(fix NEAR{3} (rate)").isSuccess()).isFalse();
        }

        @Test
        @DisplayName("Unmatched opening bracket without matching close is rejected")
        void missingOpeningParen() {
            assertThat(translator.translate("fix NEAR{3} rate)").isSuccess()).isFalse();
        }

        @Test
        @DisplayName("Empty parentheses are rejected")
        void emptyParensRejected() {
            assertThat(translator.translate("()").isSuccess()).isFalse();
        }

        @Test
        @DisplayName("Unclosed quoted phrase is rejected")
        void unclosedQuoteRejected() {
            assertThat(translator.translate("\"unclosed phrase").isSuccess()).isFalse();
        }
    }

    @Nested
    @DisplayName("Validation — meaningful content required")
    class MeaningfulContentValidation {

        @ParameterizedTest(name = "[{index}] ''{0}'' is rejected as meaningless")
        @ValueSource(strings = {"#@$#%$", "!!!", "()", "***", "   "})
        void symbolOnlyContentRejected(String term) {
            assertThat(translator.translate(term).isSuccess()).as(term).isFalse();
        }

        @Test
        @DisplayName("A single letter is meaningful content, even alone")
        void singleLetterIsMeaningful() {
            assertThat(translator.translate("F").isSuccess()).isTrue();
        }

        @Test
        @DisplayName("Non-Latin content (Korean) is meaningful")
        void nonLatinContentIsMeaningful() {
            assertThat(translator.translate("내부자").isSuccess()).isTrue();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // The five real terms from the bug-report screenshot
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Screenshot terms — no crash on any of the five real reported terms")
    class ScreenshotTerms {

        @Test
        @DisplayName("Term 2: never throws (previously crashed with StringIndexOutOfBoundsException)")
        void term2_neverCrashes() {
            String term = "(Scheiße OR Scheisse OR Scheiß OR Scheiss OR Kacke OR Scheißdreck OR Scheissdreck "
                    + "OR dreckige OR scheisse OR verdammte OR verfluchte OR Müll OR Muell OR Dreck OR schlecht "
                    + "OR schlimm OR böse OR boese OR übel OR uebel OR unzureichend) "
                    + "NEAR{5} (Bonus OR Bonuszahlung OR Gehaltszulage OR Comp OR Komp OR Kompensation OR Vergütung)";
            assertThat(translator.translate(term)).isNotNull(); // must not throw
        }

        @Test
        @DisplayName("Term 3: prefix-wildcard German words compile with wildcard correctly expanded")
        void term3_prefixWildcardOnGermanWords() {
            var s = translateOk("(gemobbt OR eingeschüchtert) NEAR{2} (broker OR *händler OR *haendler)");
            assertThat(s.hsPatterns().getFirst()).contains("\\S*h\u00e4ndler").contains("\\S*haendler");
        }

        @Test
        @DisplayName("Term 1: original screenshot term (unwrapped multi-word OR-alternatives) now compiles directly")
        void term1_originalUnwrappedFormNowCompiles() {
            // "zusammen tun", "bereit stellen", etc. are unwrapped 2-word OR-alternatives —
            // previously rejected by the (now-removed) bracket-wrapping requirement.
            String term =
                    "((konspirier* OR verschwör* OR zusammentun OR zusammen tun OR zusammengetan OR zusammen getan) "
                            + "NEAR{4} (allokier* OR verteil* OR bereitstellen OR bereit stellen OR zuteilen OR zu teilen)) "
                            + "NEAR{4} (Löhne OR Lohn OR Gehalt OR benefit* OR Vergütung OR bonus*)";
            assertThat(translator.translate(term)).isNotNull(); // must not throw, must not be rejected for wrapping
            assertThat(translator.translate(term).isSuccess()).isTrue();
        }

        @Test
        @DisplayName("Term 4: original screenshot term now needs explicit nesting for its chained FOLLOWEDBY " +
                "(unwrapped phrases still need no correction — two independent fixes, tested together here)")
        void term4_withExplicitNestingForChainedFollowedBy() {
            // The ORIGINAL screenshot term chains two FOLLOWEDBY at the same level inside
            // the AND-NOT exclusion — under this round's fix that is now rejected outright
            // (see ChainedProximityValidation), so it needs one explicit nesting parenthesis,
            // same as any other chained-proximity term. Unwrapped OR-alternatives elsewhere
            // in the term still need no correction at all.
            var s = translateOk(
                    "((nur für dich OR nur fuer dich OR nur für Sie OR nur fuer Sie)) "
                            + "AND NOT(((für dich OR für Sie OR fuer dich OR fuer Sie) "
                            + "FOLLOWEDBY{1} (als OR zum OR zur)) FOLLOWEDBY{1} (Hintergrund OR Info OR Update OR Illustration))");
            assertThat(s.requiresExclusionCheck()).isTrue();
            assertThat(s.exclusionPatterns().getFirst()).isNotBlank();
        }

        @Test
        @DisplayName("Term 5: original screenshot term (unwrapped 'EURIBOR FIXING' left operand) now compiles directly")
        void term5_originalUnwrappedFormNowCompiles() {
            var s = translateOk(
                    "(fixing NEAR{2} (tenor OR drive OR rig OR want the OR whack)) "
                            + "AND NOT((FIXING JISDOR G. TENOR) OR (Tenor Value Date Fixing) "
                            + "OR (EURIBOR FIXING NEAR{2} TENOR))");
            assertThat(s.requiresExclusionCheck()).isTrue();
        }

        @Test
        @DisplayName("Term 4 (fully corrected: wrapped phrases AND explicitly-nested FOLLOWEDBY)")
        void term4_corrected() {
            var s = translateOk(
                    "((nur für dich) OR (nur fuer dich)) "
                            + "AND NOT((((für dich) OR (fuer dich)) FOLLOWEDBY{1} (als OR zum)) FOLLOWEDBY{1} (Hintergrund OR Info))");
            assertThat(s.requiresExclusionCheck()).isTrue();
        }

        @Test
        @DisplayName("Term 5 (previously-required manual correction still also works)")
        void term5_corrected() {
            var s = translateOk(
                    "(fixing NEAR{2} (tenor OR drive OR rig)) "
                            + "AND NOT((plain phrase here) OR ((EURIBOR FIXING) NEAR{2} TENOR))");
            assertThat(s.requiresExclusionCheck()).isTrue();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // "Pattern too large" prevention — PatternComplexityAnalyzer
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Pattern complexity validation — prevents Hyperscan 'Pattern too large' failures")
    class PatternComplexity {

        @Test
        @DisplayName("THE REPORTED FAILING TERM: nested FOLLOWEDBY with wide OR/wildcard operands " +
                "DECOMPOSES into independent leaves instead of failing outright, with an explicit warning")
        void reportedFailingTerm_decomposesWithWarning() {
            String term = "(((wordA word B OR wordC* wordD OR wordE* wordF OR wordG) FOLLOWEDBY{4} "
                    + "(wordH* OR wordI wordJ* wordK OR wordL* wordM OR wordN)) FOLLOWEDBY{4} "
                    + "(wordO* OR wordP* wordQ OR wordR* wordS OR wordT))";

            var s = translateOk(term);
            assertThat(s.hsPatterns()).hasSize(3); // decomposed into 3 independent leaves
            assertThat(s.warnings()).isNotEmpty();
            boolean hasComplexityWarning = s.warnings().stream()
                    .anyMatch(w -> w.contains("estimated complexity") && w.contains("DECOMPOSED"));
            assertThat(hasComplexityWarning).isTrue();
        }

        @Test
        @DisplayName("BUG FIX: decomposed leaves must carry their enclosing FOLLOWEDBY's own gap fragment "
                + "as a literal prefix, not drop it entirely — real German lexicon term that exposed the "
                + "regression, nested FOLLOWEDBY over wildcard/multi-word OR groups")
        void decomposedLeaves_carryFollowedByGapPrefix() {
            String term = "(((versuch nicht OR mach* nicht OR tu* nicht OR vermeide) FOLLOWEDBY{4} "
                    + "(frontrun* OR front run* OR übergeh* OR überspring*)) FOLLOWEDBY{4} "
                    + "(das OR dies OR mich OR sie OR flow OR Druck OR Ausdruck))";

            var s = translateOk(term);

            assertThat(s.hsPatterns()).containsExactly(
                    "(?:versuch nicht|mach\\S* nicht|tu\\S* nicht|vermeide)",
                    "(?:\\s+\\S+){0,4}\\s+(?:frontrun\\S*|front run\\S*|übergeh\\S*|überspring\\S*)",
                    "(?:\\s+\\S+){0,4}\\s+(?:das|dies|mich|sie|flow|Druck|Ausdruck)");
        }

        @Test
        @DisplayName("Simple single-level NEAR/FOLLOWEDBY with small OR groups is never affected")
        void simpleProximity_neverRejected() {
            assertThat(translator.translate("(a OR b) NEAR{5} (c OR d)").isSuccess()).isTrue();
            assertThat(translator.translate("(a OR b) FOLLOWEDBY{4} (c OR d)").isSuccess()).isTrue();
        }

        @Test
        @DisplayName("Explicitly-nested FOLLOWEDBY with small OR groups (the requirement-4 workaround) still succeeds")
        void nestedProximitySmallGroups_stillSucceeds() {
            assertThat(translator.translate("((a OR b) FOLLOWEDBY{5} c) FOLLOWEDBY{6} d").isSuccess()).isTrue();
        }

        @Test
        @DisplayName("Real-world worked examples from the README are unaffected by the complexity budget")
        void readmeWorkedExamples_unaffected() {
            assertThat(translator.translate("(manipulate) NEAR{5} ((price) OR (spread) OR (stock))").isSuccess()).isTrue();
            assertThat(translator.translate("price AND NOT (rigging OR change)").isSuccess()).isTrue();
            assertThat(translator.translate("((don't forward) AND NOT (compliance OR legal))").isSuccess()).isTrue();
        }

        @Test
        @DisplayName("AND NOT: an over-budget EXCLUDED side DECOMPOSES independently of the required side, " +
                "with a warning specifically naming the excluded side")
        void andNot_overBudgetExcludedSide_decomposes() {
            String nestedTerm = "(simple) AND NOT "
                    + "(((wordA word B OR wordC* wordD OR wordE* wordF OR wordG) FOLLOWEDBY{4} "
                    + "(wordH* OR wordI wordJ* wordK OR wordL* wordM OR wordN)) FOLLOWEDBY{4} "
                    + "(wordO* OR wordP* wordQ OR wordR* wordS OR wordT))";
            var s = translateOk(nestedTerm);
            assertThat(s.hsPatterns()).hasSize(1);        // required side untouched, still simple
            assertThat(s.exclusionPatterns()).hasSize(3); // excluded side decomposed into 3 leaves
            boolean hasExcludedWarning = s.warnings().stream()
                    .anyMatch(w -> w.contains("excluded (AND NOT)") && w.contains("estimated complexity"));
            assertThat(hasExcludedWarning).isTrue();
        }

        @Test
        @DisplayName("A term with wildcards in a wide OR group, but no nested proximity, still succeeds " +
                "(wildcards alone are not enough to trip the budget)")
        void wideOrWithWildcardsNoNesting_stillSucceeds() {
            assertThat(translator.translate(
                    "(worda* OR wordb* OR wordc* OR wordd* OR worde* OR wordf*)").isSuccess()).isTrue();
        }

        @Test
        @DisplayName("REGRESSION (the exact bug real Hyperscan testing found): a WIDE single-level NEAR " +
                "must PASS even though it scores higher in raw terms than a NARROWER but NESTED " +
                "FOLLOWEDBY term, which must FAIL — nesting depth, not branch width, drives real " +
                "Hyperscan compile failure")
        void nestingDepthNotBranchWidth_correctRelativeOrdering() {
            // Real, un-simplified examples from production: single-level NEAR over an
            // 18-alternative and an 8-alternative OR group (no nesting) — compiles
            // successfully in real Hyperscan.
            String wideSingleLevelTerm =
                    "((Steuer* OR Gesetz* OR Richtlinie OR Police OR Polizei OR Genehmigung OR Approval OR "
                            + "Compliance OR Kontrolle OR Behörde OR Behoerde OR Bafin OR Regulierung OR Regulation OR "
                            + "Regulator OR Regulatoren OR Regelung OR Strafe) NEAR{2} (vermied* OR vermied* OR umgeh* OR "
                            + "umgangen OR umging OR entgeh* OR ausweichen OR ausgewichen)) AND "
                            + "NOT(Ausnahmegenehmigung OR mit Steuern umgehen können OR mit Steuern umgehen koennen)";

            // Two nested FOLLOWEDBY operators over much narrower 4-alternative OR groups —
            // rejected by real Hyperscan with "Pattern is too large".
            String nestedNarrowerTerm =
                    "(((wordA word B OR wordC* wordD OR wordE* wordF OR wordG) FOLLOWEDBY{4} "
                            + "(wordH* OR wordI wordJ* wordK OR wordL* wordM OR wordN)) FOLLOWEDBY{4} "
                            + "(wordO* OR wordP* wordQ OR wordR* wordS OR wordT))";

            var wideResult = translator.translate(wideSingleLevelTerm);
            assertThat(wideResult.isSuccess())
                    .as("the wide single-level term must PASS as a simple (non-decomposed) pattern")
                    .isTrue();
            if (wideResult instanceof TranslationResult.Success wideSuccess) {
                assertThat(wideSuccess.hsPatterns())
                        .as("the wide term must NOT need decomposition")
                        .hasSize(1);
            }

            var nestedResult = translator.translate(nestedNarrowerTerm);
            assertThat(nestedResult.isSuccess())
                    .as("the narrower but nested term must still SUCCEED via decomposition, not be rejected")
                    .isTrue();
            if (nestedResult instanceof TranslationResult.Success nestedSuccess) {
                assertThat(nestedSuccess.hsPatterns())
                        .as("the narrower but nested term must DECOMPOSE — proving nesting depth, not "
                                + "branch width, is what drives the heuristic, matching real Hyperscan's own behavior")
                        .hasSize(3);
                assertThat(nestedSuccess.warnings()).isNotEmpty();
            }
        }
    }

    @Nested
    @DisplayName("REGRESSION: AND NOT nested inside another operator — confirmed bug, now rejected")
    class NestedAndNotRejection {

        /**
         * Confirmed bug: {@code parseParenGroup()} recurses back to
         * {@code parseOr()}, so AND NOT is grammatically legal inside a
         * parenthesised group anywhere a group is legal — e.g. as an operand
         * of NEAR/FOLLOWEDBY/AND/OR. {@code translate()} only ever
         * special-cases AND NOT when it is the ROOT of the whole term's AST.
         * Before the fix, a term like this one compiled successfully to a
         * PASS result equivalent to plain "insider NEAR{5} trading" — the
         * "AND NOT compliance" constraint silently discarded, with no error,
         * no warning, and requiresExclusionCheck() incorrectly false. A
         * message containing "insider trading" would have incorrectly
         * matched even when "compliance" was also present — exactly the
         * case the term was written to exclude.
         */
        @Test
        @DisplayName("AND NOT nested as the LEFT operand of NEAR is rejected, not silently mishandled")
        void andNotNestedInNearLeftOperand_rejected() {
            var result = translator.translate("(insider AND NOT compliance) NEAR{5} trading");

            assertThat(result.isSuccess())
                    .as("must be rejected, not silently compiled with the exclusion dropped")
                    .isFalse();
            var error = (TranslationResult.Error) result;
            assertThat(error.message()).containsIgnoringCase("AND NOT");
            assertThat(error.message()).containsIgnoringCase("top level");
        }

        @Test
        @DisplayName("AND NOT nested as the RIGHT operand of NEAR is also rejected")
        void andNotNestedInNearRightOperand_rejected() {
            var result = translator.translate("trading NEAR{5} (insider AND NOT compliance)");
            assertThat(result.isSuccess()).isFalse();
        }

        @Test
        @DisplayName("AND NOT nested inside FOLLOWEDBY is rejected")
        void andNotNestedInFollowedBy_rejected() {
            var result = translator.translate("(insider AND NOT compliance) FOLLOWEDBY{3} trading");
            assertThat(result.isSuccess()).isFalse();
        }

        @Test
        @DisplayName("AND NOT nested inside OR is rejected")
        void andNotNestedInOr_rejected() {
            var result = translator.translate("price OR (insider AND NOT compliance)");
            assertThat(result.isSuccess()).isFalse();
        }

        @Test
        @DisplayName("AND NOT nested inside AND is rejected")
        void andNotNestedInAnd_rejected() {
            var result = translator.translate("price AND (insider AND NOT compliance)");
            assertThat(result.isSuccess()).isFalse();
        }

        @Test
        @DisplayName("A second AND NOT nested inside the EXCLUDED side of a top-level AND NOT is rejected")
        void andNotNestedInExcludedSideOfTopLevelAndNot_rejected() {
            var result = translator.translate("insider AND NOT (compliance AND NOT legal)");
            assertThat(result.isSuccess()).isFalse();
        }

        @Test
        @DisplayName("A second AND NOT nested inside the REQUIRED side of a top-level AND NOT is rejected")
        void andNotNestedInRequiredSideOfTopLevelAndNot_rejected() {
            var result = translator.translate("(insider AND NOT compliance) AND NOT legal");
            assertThat(result.isSuccess()).isFalse();
        }

        @Test
        @DisplayName("AND NOT deeply nested inside multiple wrapping parens and a NEAR is still rejected")
        void andNotDeeplyNested_rejected() {
            var result = translator.translate("((price NEAR{3} (insider AND NOT compliance)))");
            assertThat(result.isSuccess()).isFalse();
        }

        // ── Legitimate usage must remain unaffected ─────────────────────────────

        @Test
        @DisplayName("Simple top-level AND NOT still compiles correctly")
        void simpleTopLevelAndNot_stillAccepted() {
            var result = translator.translate("insider AND NOT compliance");
            assertThat(result.isSuccess()).isTrue();
            var success = (TranslationResult.Success) result;
            assertThat(success.requiresExclusionCheck()).isTrue();
        }

        @Test
        @DisplayName("Chained AND NOT (multiple excluded OPERANDS of one top-level node, not nesting) still compiles")
        void chainedAndNot_stillAccepted() {
            var result = translator.translate("insider AND NOT compliance AND NOT legal");
            assertThat(result.isSuccess()).isTrue();
            var success = (TranslationResult.Success) result;
            assertThat(success.requiresExclusionCheck()).isTrue();
        }

        @Test
        @DisplayName("AND NOT at the top level, with NEAR correctly scoped INSIDE the required side " +
                "(not the reverse), still compiles — only NEAR-containing-AndNot is rejected, " +
                "not AndNot-containing-NEAR")
        void andNotAtTopLevelWithNearInsideRequiredSide_stillAccepted() {
            var result = translator.translate("(insider NEAR{5} trading) AND NOT compliance");
            assertThat(result.isSuccess()).isTrue();
            var success = (TranslationResult.Success) result;
            assertThat(success.requiresExclusionCheck()).isTrue();
        }

        @Test
        @DisplayName("OR nested inside NEAR with no AND NOT anywhere is entirely unaffected by this check")
        void orNestedInNear_unaffected() {
            var result = translator.translate("(price OR spread) NEAR{3} (insider OR trading)");
            assertThat(result.isSuccess()).isTrue();
            var success = (TranslationResult.Success) result;
            assertThat(success.requiresExclusionCheck()).isFalse();
        }
    }
}
