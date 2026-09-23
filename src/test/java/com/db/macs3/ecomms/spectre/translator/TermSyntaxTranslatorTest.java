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
        @DisplayName("Example 1: (crap OR bad) NEAR{3} (bonus OR comp) — simple/safe, so it merges "
                + "into ONE gap-embedded pattern; proximity is ALSO still conveyed via resolvedPatterns")
        void example1_orInsideNear() {
            var s = translateOk("(crap OR bad) NEAR{3} (bonus OR comp)");
            assertThat(s.hsPatterns()).hasSize(1);
            assertThat(s.hsPatterns().getFirst()).contains("(?:\\bcrap\\b|\\bbad\\b)").contains("(?:\\bbonus\\b|\\bcomp\\b)");
            assertThat(s.resolvedPattern()).isEqualTo("(?:\\bcrap\\b|\\bbad\\b) NEAR{3} (?:\\bbonus\\b|\\bcomp\\b)");
        }

        @Test
        @DisplayName("Example 2: (F) FOLLOWEDBY{1} (((me) OR (cking))) — triple redundant wrapping, "
                + "merges into ONE gap-embedded pattern")
        void example2_deeplyRedundantWrapping() {
            var s = translateOk("(F) FOLLOWEDBY{1} (((me) OR (cking)))");
            assertThat(s.hsPatterns()).hasSize(1);
            assertThat(s.hsPatterns().getFirst()).contains("F").contains("(?:\\bme\\b|\\bcking\\b)");
            assertThat(s.resolvedPattern()).isEqualTo("\\bF\\b FOLLOWEDBY{1} (?:\\bme\\b|\\bcking\\b)");
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
            assertThat(s.hsPatterns().getFirst()).contains("\\bbomb this place\\b").contains("\\bblow this place up\\b");
        }

        @Test
        @DisplayName("Unwrapped multi-word phrase at top level is now ACCEPTED as an implicit phrase")
        void unwrappedTopLevelPhraseAccepted() {
            var s = translateOk("bomb this place OR blow this place up");
            assertThat(s.hsPatterns().getFirst()).isEqualTo("(?:\\bbomb this place\\b|\\bblow this place up\\b)");
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
            assertThat(s.hsPatterns()).hasSize(1);
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
    // Requirement 3: '?' is a single-character wildcard
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Requirement 3 — '?' is a single-character wildcard")
    class QuestionMarkWildcard {

        @Test
        @DisplayName("he?d / she?d — '?' becomes \\S (exactly one non-whitespace character), never a "
                + "live PCRE quantifier and never a literal question mark")
        void questionMarkIsSingleCharWildcard() {
            var s = translateOk("((he?d kill) OR (she?d kill))");
            assertThat(s.hsPatterns().getFirst()).isEqualTo("(?:\\bhe\\Sd kill\\b|\\bshe\\Sd kill\\b)");
        }

        @Test
        @DisplayName("REPORTED: 'I?ll kill you' matches text where '?' stands in for an apostrophe")
        void questionMarkWildcard_matchesRealText() {
            var s = translateOk("I?ll kill you");
            assertThat(s.hsPatterns()).containsExactly("\\bI\\Sll kill you\\b");
            boolean matched = java.util.regex.Pattern
                    .compile(s.hsPatterns().getFirst(), java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher("I'll kill you before the term sheet went out, so please review it before the pricing call.")
                    .find();
            assertThat(matched).isTrue();
        }

        @Test
        @DisplayName("A literal question mark is still available via a quoted phrase — quotes mean "
                + "'match this exactly', unaffected by the bare-word wildcard change")
        void questionMarkInQuotesStaysLiteral() {
            var s = translateOk("\"he?d\"");
            assertThat(s.hsPatterns().getFirst()).isEqualTo("\\bhe\\?d\\b");
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
            assertThat(s.hsPatterns().getFirst()).isEqualTo("(?:\\bcheck her out\\b|\\bchimp\\S*)");
        }

        @Test
        @DisplayName("Prefix wildcard: *handler -> \\S*handler")
        void prefixWildcard() {
            var s = translateOk("(*handler)");
            assertThat(s.hsPatterns().getFirst()).isEqualTo("\\S*handler\\b");
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
            assertThat(s.hsPatterns().getFirst()).isEqualTo("\\bchimp\\*");
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
            var s = translateOk("price AND NOT (change)");

            assertThat(s.requiresExclusionCheck()).isTrue();
            assertThat(NO_LOOKAROUND_CHECK.matcher(s.hsPatterns().getFirst()).find()).isFalse();
            assertThat(NO_LOOKAROUND_CHECK.matcher(s.exclusionRegexs().getFirst()).find()).isFalse();

            // Apply the two-pattern contract exactly as a caller must: matched iff
            // hsPattern matches AND exclusionRegex does NOT match the same message.
            boolean message1Matches = matches(s.hsPatterns().getFirst(), message1) && !matches(s.exclusionRegexs().getFirst(), message1);
            boolean message2Matches = matches(s.hsPatterns().getFirst(), message2) && !matches(s.exclusionRegexs().getFirst(), message2);
            assertThat(message1Matches).isFalse();
            assertThat(message2Matches).isFalse();
        }

        @Test
        @DisplayName("AND is fully self-contained in hsPattern — no post-filter metadata needed")
        void plainAnd_selfContained() {
            var s = translateOk("((want to) AND (fix))");
            assertThat(s.requiresExclusionCheck()).isFalse();
            assertThat(s.exclusionRegexs()).isNull();
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
            // Required side's FOLLOWEDBY is simple/safe, so it merges into ONE gap-embedded pattern.
            assertThat(s.hsPatterns()).hasSize(1);
            assertThat(s.hsPatterns().getFirst()).contains("(?:\\bfix\\b|\\brig\\b)").contains("the rate");
            assertThat(s.hsPatterns()).noneMatch(p -> p.contains("fed rate move"));
            assertThat(s.hsPatterns()).noneMatch(p -> NO_LOOKAROUND_CHECK.matcher(p).find());
            assertThat(s.exclusionRegexs().getFirst()).contains("fed rate move");
            assertThat(s.resolvedPattern()).isEqualTo(
                    "(?:\\bfix\\b|\\brig\\b) FOLLOWEDBY{2} \\bthe rate\\b AND NOT (\\bfed rate move\\b)");
        }

        @Test
        @DisplayName("THE REPORTED BUG: 'AND NOT(' with no space before the parenthesis is recognised correctly")
        void andNotWithoutSpaceBeforeParen() {
            var s = translateOk("(hello) AND NOT(world)");
            assertThat(s.requiresExclusionCheck()).isTrue();
            assertThat(s.exclusionRegexs().getFirst()).isEqualTo("\\bworld\\b");
        }

        @Test
        @DisplayName("Explicitly-nested FOLLOWEDBY inside an AND-NOT exclusion (different grammar levels) still "
                + "resolves — simple/safe, so it merges into ONE gap-embedded pattern")
        void nestedFollowedByInsideAndNotExclusion() {
            var s = translateOk("(hello) AND NOT(((a OR b) FOLLOWEDBY{1} (c OR d)) FOLLOWEDBY{1} (e OR f))");
            assertThat(s.exclusionRegexs()).hasSize(1);
            assertThat(s.exclusionRegexs().getFirst()).contains("(?:\\ba\\b|\\bb\\b)").contains("(?:\\bc\\b|\\bd\\b)").contains("(?:\\be\\b|\\bf\\b)");
            assertThat(s.resolvedPattern()).isEqualTo(
                    "\\bhello\\b AND NOT ((?:\\ba\\b|\\bb\\b) FOLLOWEDBY{1} (?:\\bc\\b|\\bd\\b) FOLLOWEDBY{1} (?:\\be\\b|\\bf\\b))");
        }

        @Test
        @DisplayName("Nested NEAR inside an AND-NOT exclusion (one alternative of an OR-of-phrases) resolves correctly")
        void nestedNearInsideAndNotExclusion() {
            var s = translateOk("(hello) AND NOT((plain phrase) OR ((EURIBOR FIXING) NEAR{2} TENOR))");
            assertThat(s.exclusionRegexs().getFirst()).contains("EURIBOR FIXING").contains("TENOR");
        }

        @Test
        @DisplayName("Chained AND NOT: A AND NOT B AND NOT C combines B and C into one OR'd exclusion pattern")
        void chainedAndNot() {
            var s = translateOk("(a) AND NOT (b) AND NOT (c)");
            assertThat(s.requiresExclusionCheck()).isTrue();
            assertThat(s.exclusionRegexs().getFirst()).contains("b").contains("c");
            // Either b or c present alone should trigger the exclusion.
            assertThat(matches(s.exclusionRegexs().getFirst(), "just b here")).isTrue();
            assertThat(matches(s.exclusionRegexs().getFirst(), "just c here")).isTrue();
            assertThat(matches(s.exclusionRegexs().getFirst(), "neither here")).isFalse();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Requirement (this round) 1 — no independent NOT operator
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("No independent NOT OPERATOR — but NOT as a literal word is allowed")
    class NoIndependentNot {

        @Test
        @DisplayName("REPORTED: NOT as the first word of a phrase is literal text, not an operator")
        void notStartingAPhraseIsLiteral() {
            var s = translateOk("(NOT LAUNCHING)");
            assertThat(s.hsPatterns().getFirst()).isEqualTo("\\bNOT LAUNCHING\\b");
        }

        @Test
        @DisplayName("REPORTED: the exact bug-report term — NOT starting each OR-branch's phrase compiles, "
                + "'NOT' used as literal text in every branch that starts with it")
        void reportedOrGroupWithLeadingNotWords() {
            var s = translateOk("((disintermediate*) OR (NOT LAUNCHING) OR (NOT TO LAUNCH THE PRODUCT))");
            assertThat(s.hsPatterns().getFirst())
                    .isEqualTo("(?:\\bdisintermediate\\S*|\\bNOT LAUNCHING\\b|\\bNOT TO LAUNCH THE PRODUCT\\b)");
        }

        @Test
        @DisplayName("REPORTED: NOT between two already-parsed (parenthesised) expressions is still "
                + "rejected as an unsupported standalone operator")
        void notBetweenTwoParenthesisedExpressionsRejected() {
            String msg = translateError(
                    "((disintermediate*) NOT ((LAUNCHING) OR (TO LAUNCH THE PRODUCT)))");
            assertThat(msg).contains("Standalone NOT is not supported");
            assertThat(msg).contains("AND NOT");
        }

        @Test
        @DisplayName("NOT immediately after a closed parenthesised group is rejected even when the "
                + "right-hand side has no parentheses of its own")
        void notAfterClosedGroupRejectedEvenWithoutRightParens() {
            String msg = translateError("(disintermediate*) NOT LAUNCHING");
            assertThat(msg).contains("Standalone NOT is not supported");
        }

        @Test
        @DisplayName("A bare leading NOT at the very start of a whole term is literal text too — "
                + "there is no left-hand expression for it to negate")
        void leadingNotAtTermStartIsLiteral() {
            var s = translateOk("NOT confidential");
            assertThat(s.hsPatterns().getFirst()).isEqualTo("\\bNOT confidential\\b");
        }

        @Test
        @DisplayName("NOT is literal after OR too, when nothing else looks like an operator position")
        void notAfterOrIsLiteral() {
            var s = translateOk("A OR NOT B");
            assertThat(s.hsPatterns().getFirst()).isEqualTo("(?:\\bA\\b|\\bNOT B\\b)");
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
    // BUG REPORT 1: NEAR nested as the RIGHT operand of another NEAR loses
    // its authored tree shape in resolvedPatterns/patternMapping
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Nested proximity retains its authored tree shape — right-side nesting is wrapped")
    class NestedProximityTreeShape {

        @Test
        @DisplayName("REPORTED BUG: '(manipulate OR front run) NEAR{5} ((price OR spread) NEAR{5} stock)' "
                + "— resolvedPatterns wraps the nested right-hand NEAR in parentheses instead of "
                + "flattening it indistinguishably from a left-associative chain")
        void nearNestedAsRightOperand_resolvedPatternsRetainsGrouping() {
            var s = translateOk("(manipulate OR front run) NEAR{5} ((price OR spread) NEAR{5} stock)");
            assertThat(s.hsPatterns()).containsExactly("(?:\\bmanipulate|\\bfront run)", "(?:\\bprice|\\bspread)", "\\bstock");
            assertThat(s.resolvedPattern()).isEqualTo(
                    "(?:\\bmanipulate|\\bfront run) NEAR{5} ((?:\\bprice|\\bspread) NEAR{5} \\bstock)");
        }

        @Test
        @DisplayName("Same shape with FOLLOWEDBY on both levels")
        void followedByNestedAsRightOperand_resolvedPatternsRetainsGrouping() {
            var s = translateOk("(a OR b) FOLLOWEDBY{5} ((c OR d) FOLLOWEDBY{3} e)");
            assertThat(s.resolvedPattern()).isEqualTo("(?:\\ba\\b|\\bb\\b) FOLLOWEDBY{5} ((?:\\bc\\b|\\bd\\b) FOLLOWEDBY{3} \\be\\b)");
        }

        @Test
        @DisplayName("Left-nested chaining (implicit or explicit) is UNCHANGED — still flat, no extra parens")
        void leftNestedChain_staysFlat() {
            var chained = translateOk("(a) NEAR{5} (b) NEAR{5} (c)");
            assertThat(chained.resolvedPattern()).isEqualTo("\\ba\\b NEAR{5} \\bb\\b NEAR{5} \\bc\\b");

            var explicit = translateOk("((a) NEAR{5} (b)) NEAR{5} (c)");
            assertThat(explicit.resolvedPattern()).isEqualTo("\\ba\\b NEAR{5} \\bb\\b NEAR{5} \\bc\\b");
        }

        @Test
        @DisplayName("Right-nesting three levels deep wraps each nested level in its own parentheses")
        void deeplyRightNested_wrapsEachLevel() {
            var s = translateOk("(a) NEAR{5} ((b) NEAR{4} ((c) NEAR{3} (d)))");
            assertThat(s.resolvedPattern()).isEqualTo("\\ba NEAR{5} (\\bb NEAR{4} (\\bc NEAR{3} \\bd))");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // BUG REPORT 2: proximity operators sandwiched between OR alternatives
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Validation — NEAR/FOLLOWEDBY sandwiched between OR alternatives is rejected")
    class SandwichedProximityValidation {

        @Test
        @DisplayName("REPORTED BUG (exact term): a FOLLOWEDBY buried in the middle of a long OR list "
                + "compiles today but silently relates only its own two immediate operands to each "
                + "other, ignoring every other OR alternative — now rejected")
        void reportedSandwichedFollowedBy_rejected() {
            String msg = translateError(
                    "(I) OR (you) OR (they) OR (we) OR (DB) OR (he) OR (she) OR (client) OR (us) OR (him) "
                            + "OR (her) OR (them) FOLLOWEDBY{3} (sin bin) OR (penalty box) OR sinbin");
            assertThat(msg).containsIgnoringCase("NEAR/FOLLOWEDBY");
            assertThat(msg).containsIgnoringCase("ambiguous");
        }

        @Test
        @DisplayName("Sample fix 1 from the bug report: grouping the whole term into one top-level "
                + "FOLLOWEDBY (no OR left sandwiching anything) compiles")
        void sampleFix1_topLevelFollowedByOfTwoOrGroups_compiles() {
            var result = translator.translate(
                    "((I) OR (you) OR (they) OR (we) OR (DB) OR (he) OR (she) OR (client) OR (us) OR (him) "
                            + "OR (her) OR (them)) FOLLOWEDBY{3} ((sin bin) OR (penalty box) OR sinbin)");
            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        @DisplayName("Sample fix 2 from the bug report: wrapping the FOLLOWEDBY clause as its own "
                + "OR alternative (an EDGE position, nothing after it) compiles")
        void sampleFix2_followedByAsItsOwnOrAlternative_compiles() {
            var result = translator.translate(
                    "((I) OR (you) OR (they) OR (we) OR (DB) OR (he) OR (she) OR (client) OR (us) OR (him) "
                            + "OR (her)) OR ((them) FOLLOWEDBY{3} (sin bin) OR (penalty box) OR sinbin)");
            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        @DisplayName("A minimal 3-alternative sandwiched NEAR is also rejected")
        void minimalSandwichedNear_rejected() {
            String msg = translateError("(a) OR (b) NEAR{3} (c) OR (d)");
            assertThat(msg).containsIgnoringCase("ambiguous");
        }

        @Test
        @DisplayName("LEGITIMATE, currently-documented usage is unaffected: a NEAR/FOLLOWEDBY alternative "
                + "at either EDGE of the OR list (nothing before it, or nothing after it) still compiles")
        void edgePositionProximityAlternative_stillAccepted() {
            assertThat(translator.translate("(plain phrase) OR ((EURIBOR FIXING) NEAR{2} TENOR)").isSuccess())
                    .as("proximity as the LAST alternative").isTrue();
            assertThat(translator.translate("((EURIBOR FIXING) NEAR{2} TENOR) OR (plain phrase)").isSuccess())
                    .as("proximity as the FIRST alternative").isTrue();
        }

        @Test
        @DisplayName("A proximity operator with only ONE OR sibling (either side) is unaffected — " +
                "sandwiching requires OTHER alternatives on BOTH sides")
        void onlyTwoAlternatives_neverSandwiched() {
            assertThat(translator.translate("(a) OR (b) NEAR{3} (c)").isSuccess()).isTrue();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // BUG REPORT 3 (Lexicon Scan Engine): NEAR{1}/FOLLOWEDBY{1} with a
    // single-character operand can never match an intra-word split
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Validation — NEAR{1}/FOLLOWEDBY{1} with a single-character operand warns")
    class IntraWordSplitWarning {

        @Test
        @DisplayName("REPORTED: '(F) FOLLOWEDBY{1} (cking/me/d/ing/up)' — structurally can never match "
                + "\"Fucking\" (word-distance operators can't bridge within one word) — warned, still compiles")
        void reportedIntraWordSplitTerm_warnsButCompiles() {
            var s = translateOk("(F) FOLLOWEDBY{1} (((me) OR (cking) OR (d) OR (ing) OR (up)))");
            boolean hasIntraWordWarning = s.warnings().stream()
                    .anyMatch(w -> w.contains("FOLLOWEDBY{1}") && w.contains("single-character"));
            assertThat(hasIntraWordWarning).isTrue();
        }

        @Test
        @DisplayName("Same warning for NEAR{1}")
        void nearVariant_alsoWarns() {
            var s = translateOk("(F) NEAR{1} (cking)");
            boolean hasIntraWordWarning = s.warnings().stream()
                    .anyMatch(w -> w.contains("NEAR{1}") && w.contains("single-character"));
            assertThat(hasIntraWordWarning).isTrue();
        }

        @Test
        @DisplayName("Distance greater than 1 does not warn — only the tightest distance plausibly "
                + "means \"no gap at all\"")
        void distanceGreaterThanOne_noWarning() {
            var s = translateOk("(F) FOLLOWEDBY{2} (cking)");
            assertThat(s.warnings()).noneMatch(w -> w.contains("single-character"));
        }

        @Test
        @DisplayName("No single-character operand on either side does not warn")
        void noSingleCharacterOperand_noWarning() {
            var s = translateOk("(fix) FOLLOWEDBY{1} (rate)");
            assertThat(s.warnings()).noneMatch(w -> w.contains("single-character"));
        }

        @Test
        @DisplayName("A genuine single-letter word used deliberately still compiles (a known, accepted "
                + "false-positive risk of this heuristic — warned, never rejected)")
        void genuineSingleLetterWord_stillCompiles() {
            var s = translateOk("(a) FOLLOWEDBY{1} (boy)");
            assertThat(s).isNotNull(); // must not throw / must not be rejected
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
            assertThat(s.hsPatterns().getFirst()).isEqualTo("\\bprice or spread\\b");
        }

        @Test
        @DisplayName("lowercase 'and' is literal text")
        void lowercaseAndIsLiteral() {
            var s = translateOk("(rock and roll)");
            assertThat(s.hsPatterns().getFirst()).isEqualTo("\\brock and roll\\b");
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
    @DisplayName("Validation — NEAR/FOLLOWEDBY distance must be a whole number 1-50")
    class ProximityDistanceValidation {

        @ParameterizedTest(name = "[{index}] {0}{1} is rejected")
        @CsvSource({
                "NEAR, '{0}'",
                "NEAR, '{-1}'",
                "NEAR, '{abcd}'",
                "NEAR, '{51}'",
                "NEAR, '{100}'",
                "NEAR, '{05}'",
                "NEAR, '{0,6}'",
                "FOLLOWEDBY, '{0}'",
                "FOLLOWEDBY, '{-1}'",
                "FOLLOWEDBY, '{abcd}'",
                "FOLLOWEDBY, '{51}'",
                "FOLLOWEDBY, '{100}'",
                "FOLLOWEDBY, '{05}'",
                "FOLLOWEDBY, '{0,6}'",
        })
        void invalidDistanceRejected(String keyword, String brace) {
            String term = "(a) " + keyword + brace.replace("'", "") + " (b)";
            assertThat(translator.translate(term).isSuccess()).as(term).isFalse();
        }

        @ParameterizedTest(name = "[{index}] distance {0} is accepted")
        @ValueSource(ints = {1, 2, 3, 9, 10, 25, 49, 50})
        void validDistancesAccepted(int n) {
            assertThat(translator.translate("(a) NEAR{" + n + "} (b)").isSuccess()).as("NEAR{%d}", n).isTrue();
            assertThat(translator.translate("(a) FOLLOWEDBY{" + n + "} (b)").isSuccess())
                    .as("FOLLOWEDBY{%d}", n).isTrue();
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
            assertThat(s.exclusionRegexs().getFirst()).isNotBlank();
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
        @DisplayName("THE REPORTED FAILING TERM: nested FOLLOWEDBY with wide OR/wildcard operands "
                + "SPLITS into independent leaves — over the complexity budget (nesting penalty), so "
                + "the single gap-embedded pattern isn't even attempted — with an explicit warning")
        void reportedFailingTerm_decomposesWithWarning() {
            String term = "(((wordA word B OR wordC* wordD OR wordE* wordF OR wordG) FOLLOWEDBY{4} "
                    + "(wordH* OR wordI wordJ* wordK OR wordL* wordM OR wordN)) FOLLOWEDBY{4} "
                    + "(wordO* OR wordP* wordQ OR wordR* wordS OR wordT))";

            var s = translateOk(term);
            assertThat(s.hsPatterns()).hasSize(3); // split into 3 independent leaves
            assertThat(s.warnings()).isNotEmpty();
            boolean hasSplitWarning = s.warnings().stream()
                    .anyMatch(w -> w.contains("NEAR/FOLLOWEDBY structure") && w.contains("split into"));
            assertThat(hasSplitWarning).isTrue();
        }

        @Test
        @DisplayName("NO gap fragment is baked into any leaf any more — the FOLLOWEDBY chain's distance "
                + "is conveyed entirely via resolvedPatterns instead — real German lexicon term that "
                + "exposed the earlier gap-baking regression, nested FOLLOWEDBY over wildcard/multi-word "
                + "OR groups")
        void decomposedLeaves_carryNoGapPrefix() {
            String term = "(((versuch nicht OR mach* nicht OR tu* nicht OR vermeide) FOLLOWEDBY{4} "
                    + "(frontrun* OR front run* OR übergeh* OR überspring*)) FOLLOWEDBY{4} "
                    + "(das OR dies OR mich OR sie OR flow OR Druck OR Ausdruck))";

            var s = translateOk(term);

            assertThat(s.hsPatterns()).containsExactly(
                    "(?:versuch nicht|mach\\S* nicht|tu\\S* nicht|vermeide)",
                    "(?:frontrun\\S*|front run\\S*|übergeh\\S*|überspring\\S*)",
                    "(?:das|dies|mich|sie|flow|Druck|Ausdruck)");
            assertThat(s.resolvedPattern()).isEqualTo(
                    "(?:versuch nicht|mach\\S* nicht|tu\\S* nicht|vermeide) FOLLOWEDBY{4} "
                    + "(?:frontrun\\S*|front run\\S*|übergeh\\S*|überspring\\S*) FOLLOWEDBY{4} "
                    + "(?:das|dies|mich|sie|flow|Druck|Ausdruck)");
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
            assertThat(s.exclusionRegexs()).hasSize(3); // excluded side split into 3 leaves
            boolean hasExcludedWarning = s.warnings().stream()
                    .anyMatch(w -> w.contains("excluded (AND NOT)") && w.contains("NEAR/FOLLOWEDBY structure"));
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
        @DisplayName("Nesting depth, not OR-branch width, is what actually drives whether a proximity term "
                + "must decompose — a wide single-level NEAR merges into ONE pattern (comfortably under "
                + "budget, and real Hyperscan compiles it fine); a narrower but NESTED FOLLOWEDBY chain "
                + "still decomposes, exactly matching PatternComplexityAnalyzer's own calibration")
        void wideSingleLevelMerges_narrowerNestedStillDecomposes() {
            // Real, un-simplified example from production: single-level NEAR over an
            // 18-alternative and an 8-alternative OR group (no nesting) — comfortably under
            // the complexity budget (no nesting penalty), so it merges into ONE pattern.
            String wideSingleLevelTerm =
                    "(Steuer* OR Gesetz* OR Richtlinie OR Police OR Polizei OR Genehmigung OR Approval OR "
                            + "Compliance OR Kontrolle OR Behörde OR Behoerde OR Bafin OR Regulierung OR Regulation OR "
                            + "Regulator OR Regulatoren OR Regelung OR Strafe) NEAR{2} (vermied* OR vermied* OR umgeh* OR "
                            + "umgangen OR umging OR entgeh* OR ausweichen OR ausgewichen)";

            // Two nested FOLLOWEDBY operators over much narrower 4-alternative OR groups — the
            // nesting penalty pushes this over budget despite the narrower branches, so it still
            // decomposes into 3 independent leaves, with a warning.
            String nestedNarrowerTerm =
                    "(((wordA word B OR wordC* wordD OR wordE* wordF OR wordG) FOLLOWEDBY{4} "
                            + "(wordH* OR wordI wordJ* wordK OR wordL* wordM OR wordN)) FOLLOWEDBY{4} "
                            + "(wordO* OR wordP* wordQ OR wordR* wordS OR wordT))";

            var wideSuccess = translateOk(wideSingleLevelTerm);
            assertThat(wideSuccess.hsPatterns()).hasSize(1);

            var nestedSuccess = translateOk(nestedNarrowerTerm);
            assertThat(nestedSuccess.hsPatterns()).hasSize(3);
            assertThat(nestedSuccess.warnings()).isNotEmpty();
        }

        @Test
        @DisplayName("REPORTED BUG: 内幕 NEAR{10} 交易 (\"insider trading\") now compiles end-to-end via "
                + "real Hyperscan, using the literal distance from the bug report — previously failed even "
                + "after the decomposition fallback, since both paths reused the same unclamped char-based "
                + "gap width; separately, the grammar itself used to cap NEAR/FOLLOWEDBY at a single digit "
                + "(1-9), which would have rejected {10} outright before this term ever reached gap-width "
                + "logic — Tokenizer.validateProximityDistance now allows 1-50.")
        void reportedCjkNearBug_nowCompiles() {
            var s = translateOk("内幕 NEAR{10} 交易");
            assertThat(s.hsPatterns()).isNotEmpty();
        }

        @Test
        @DisplayName("REPORTED BUG (exact form): OR-group NEAR/FOLLOWEDBY{10} OR-group, as literally "
                + "reported, now compiles end-to-end — the wider grammar cap (1-50) lets {10} through, and "
                + "the adaptive char-gap reduction (see MultiLanguagePatternBuilder.charBasedGap) keeps the "
                + "generated pattern within what real Hyperscan will actually compile")
        void reportedOrGroupProximityBug_nowCompiles() {
            var near = translateOk("((内幕) OR (正常) OR (的) OR (商业)) NEAR{10} ((活动) OR (记录))");
            assertThat(near.hsPatterns()).isNotEmpty();

            var followedBy = translateOk("((内幕) OR (正常) OR (的) OR (商业)) FOLLOWEDBY{10} ((活动) OR (记录))");
            assertThat(followedBy.hsPatterns()).isNotEmpty();
        }

        @Test
        @DisplayName("Forced decomposition of a CJK NEAR (via outer nesting) — every leaf, including the "
                + "clamped-gap leaf, still passes real Hyperscan")
        void cjkNearForcedDecomposition_everyLeafCompiles() {
            String term = "(内幕 NEAR{9} 交易) FOLLOWEDBY{6} 案件";
            var s = translateOk(term);
            assertThat(s.hsPatterns().size()).isGreaterThan(1);
            assertThat(s.warnings()).isNotEmpty();
        }

        @Test
        @DisplayName("Thai and Hangul NEAR at a wide distance also compile end-to-end (cross-script "
                + "confidence for the char-based gap clamp)")
        void thaiAndHangulNear_alsoCompile() {
            assertThat(translateOk("ราคา NEAR{9} การซื้อขาย").hsPatterns()).isNotEmpty();
            assertThat(translateOk("내부자 NEAR{9} 거래").hsPatterns()).isNotEmpty();
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
            var result = translator.translate("(insider AND NOT (compliance)) NEAR{5} trading");

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
            var result = translator.translate("trading NEAR{5} (insider AND NOT (compliance))");
            assertThat(result.isSuccess()).isFalse();
        }

        @Test
        @DisplayName("AND NOT nested inside FOLLOWEDBY is rejected")
        void andNotNestedInFollowedBy_rejected() {
            var result = translator.translate("(insider AND NOT (compliance)) FOLLOWEDBY{3} trading");
            assertThat(result.isSuccess()).isFalse();
        }

        @Test
        @DisplayName("AND NOT nested inside OR is rejected")
        void andNotNestedInOr_rejected() {
            var result = translator.translate("price OR (insider AND NOT (compliance))");
            assertThat(result.isSuccess()).isFalse();
        }

        @Test
        @DisplayName("AND NOT nested inside AND is rejected")
        void andNotNestedInAnd_rejected() {
            var result = translator.translate("price AND (insider AND NOT (compliance))");
            assertThat(result.isSuccess()).isFalse();
        }

        @Test
        @DisplayName("A second AND NOT nested inside the EXCLUDED side of a top-level AND NOT is rejected")
        void andNotNestedInExcludedSideOfTopLevelAndNot_rejected() {
            var result = translator.translate("insider AND NOT (compliance AND NOT (legal))");
            assertThat(result.isSuccess()).isFalse();
        }

        @Test
        @DisplayName("A second AND NOT nested inside the REQUIRED side of a top-level AND NOT is rejected")
        void andNotNestedInRequiredSideOfTopLevelAndNot_rejected() {
            var result = translator.translate("(insider AND NOT (compliance)) AND NOT (legal)");
            assertThat(result.isSuccess()).isFalse();
        }

        @Test
        @DisplayName("AND NOT deeply nested inside multiple wrapping parens and a NEAR is still rejected")
        void andNotDeeplyNested_rejected() {
            var result = translator.translate("((price NEAR{3} (insider AND NOT (compliance))))");
            assertThat(result.isSuccess()).isFalse();
        }

        // ── Legitimate usage must remain unaffected ─────────────────────────────

        @Test
        @DisplayName("Simple top-level AND NOT still compiles correctly")
        void simpleTopLevelAndNot_stillAccepted() {
            var result = translator.translate("insider AND NOT (compliance)");
            assertThat(result.isSuccess()).isTrue();
            var success = (TranslationResult.Success) result;
            assertThat(success.requiresExclusionCheck()).isTrue();
        }

        @Test
        @DisplayName("Chained AND NOT (multiple excluded OPERANDS of one top-level node, not nesting) still compiles")
        void chainedAndNot_stillAccepted() {
            var result = translator.translate("insider AND NOT (compliance) AND NOT (legal)");
            assertThat(result.isSuccess()).isTrue();
            var success = (TranslationResult.Success) result;
            assertThat(success.requiresExclusionCheck()).isTrue();
        }

        @Test
        @DisplayName("AND NOT at the top level, with NEAR correctly scoped INSIDE the required side " +
                "(not the reverse), still compiles — only NEAR-containing-AndNot is rejected, " +
                "not AndNot-containing-NEAR")
        void andNotAtTopLevelWithNearInsideRequiredSide_stillAccepted() {
            var result = translator.translate("(insider NEAR{5} trading) AND NOT (compliance)");
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

    @Nested
    @DisplayName("Standalone NOT — always a group, always paired with AND")
    class StandaloneNot {

        // ── The five examples from the requirement, verbatim ────────────────────

        @Test
        @DisplayName("VALID: bond AND (NOT (james bond))")
        void bondAndNotJamesBond_valid() {
            var s = translateOk("bond AND (NOT (james bond))");
            assertThat(s.requiresExclusionCheck()).isTrue();
            assertThat(s.hsPatterns()).containsExactly("\\bbond\\b");
            assertThat(s.exclusionRegexs().getFirst()).contains("james bond");
        }

        @Test
        @DisplayName("INVALID: NOT (james bond) — no preceding required expression")
        void bareNotAtRoot_invalid() {
            String msg = translateError("NOT (james bond)");
            assertThat(msg).containsIgnoringCase("NOT");
            assertThat(msg).contains("preceding required expression");
        }

        @Test
        @DisplayName("INVALID: apple NOT NEAR{10} banana — NOT directly before a proximity operator")
        void notDirectlyBeforeNear_invalid() {
            String msg = translateError("apple NOT NEAR{10} banana");
            assertThat(msg).containsIgnoringCase("NOT");
            assertThat(msg).containsIgnoringCase("NEAR");
        }

        @Test
        @DisplayName("INVALID: apple AND NOT NEAR{10} banana — NOT not immediately followed by '('")
        void andNotDirectlyBeforeNear_invalid() {
            String msg = translateError("apple AND NOT NEAR{10} banana");
            assertThat(msg).containsIgnoringCase("NOT");
            assertThat(msg).contains("parenthesised group");
        }

        @Test
        @DisplayName("VALID: apple AND (NOT (apple NEAR{10} banana)) — NOT wraps an arbitrary sub-expression")
        void appleAndNotNearBanana_valid() {
            var s = translateOk("apple AND (NOT (apple NEAR{10} banana))");
            assertThat(s.requiresExclusionCheck()).isTrue();
            assertThat(s.hsPatterns()).containsExactly("\\bapple\\b");
            // Excluded side's NEAR is simple/safe, so it merges into ONE gap-embedded pattern.
            assertThat(s.exclusionRegexs()).hasSize(1);
            assertThat(s.exclusionRegexs().getFirst()).contains("\\bapple\\b").contains("banana");
            assertThat(s.resolvedPattern()).isEqualTo("\\bapple\\b AND NOT (\\bapple\\b NEAR{10} \\bbanana\\b)");
        }

        // ── Both spellings are equivalent ────────────────────────────────────────

        @Test
        @DisplayName("The glued 'AND NOT (...)' spelling produces the identical shape")
        void gluedSpelling_sameShapeAsNotGroup() {
            var viaNotGroup = translateOk("bond AND (NOT (james bond))");
            var viaGlued = translateOk("bond AND NOT (james bond)");
            assertThat(viaGlued.hsPatterns()).isEqualTo(viaNotGroup.hsPatterns());
            assertThat(viaGlued.exclusionRegexs()).isEqualTo(viaNotGroup.exclusionRegexs());
        }

        @Test
        @DisplayName("Multiple NOT-groups at the same AND level chain into one excluded side, " +
                "same as chained 'AND NOT b AND NOT c'")
        void multipleNotGroups_chainIntoOneExcludedSide() {
            var s = translateOk("apple AND (NOT (banana)) AND (NOT (cherry))");
            assertThat(s.requiresExclusionCheck()).isTrue();
            assertThat(s.exclusionRegexs().getFirst()).contains("banana").contains("cherry");
        }

        // ── Other illegal placements ──────────────────────────────────────────────

        @Test
        @DisplayName("INVALID: NOT-group as an OR alternative")
        void notGroupAsOrAlternative_invalid() {
            String msg = translateError("apple OR (NOT (banana))");
            assertThat(msg).containsIgnoringCase("NOT");
            assertThat(msg).contains("OR alternative");
        }

        @Test
        @DisplayName("INVALID: NOT-group as a NEAR operand")
        void notGroupAsNearOperand_invalid() {
            String msg = translateError("apple NEAR{5} (NOT (banana))");
            assertThat(msg).containsIgnoringCase("NOT");
            assertThat(msg).containsIgnoringCase("NEAR/FOLLOWEDBY operand");
        }

        @Test
        @DisplayName("INVALID: NOT-group as the FIRST (and only) operand of AND, nothing else present")
        void notGroupAloneInParens_invalid() {
            String msg = translateError("(NOT (banana))");
            assertThat(msg).containsIgnoringCase("NOT");
            assertThat(msg).contains("preceding required expression");
        }

        @Test
        @DisplayName("(NOT LAUNCHING) with no immediate '(' after NOT is still literal text, unaffected")
        void notAsLiteralWordUnaffected() {
            var s = translateOk("((disintermediate*) OR (NOT LAUNCHING) OR (NOT TO LAUNCH THE PRODUCT))");
            assertThat(s.hsPatterns().getFirst()).contains("\\bNOT LAUNCHING\\b").contains("\\bNOT TO LAUNCH THE PRODUCT\\b");
        }
    }
}
