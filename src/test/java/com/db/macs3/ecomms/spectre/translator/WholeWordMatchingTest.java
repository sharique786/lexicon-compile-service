package com.db.macs3.ecomms.spectre.translator;

import com.db.macs3.ecomms.spectre.hyperscan.HyperscanCompiler;
import com.gliwka.hyperscan.wrapper.Database;
import com.gliwka.hyperscan.wrapper.Expression;
import com.gliwka.hyperscan.wrapper.Scanner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression coverage for the reported bug: term {@code "(righteous babe) OR (pd)"}
 * matched "pd" inside "There are following updates" because literals were
 * emitted as bare substrings. Literals now match as whole words; a {@code *}
 * wildcard at an edge is the explicit opt-out. Every scan here runs the
 * translator's real output through real Hyperscan.
 */
@DisplayName("Whole-word matching of literals")
class WholeWordMatchingTest {

    private HyperscanCompiler compiler;
    private TermSyntaxTranslator translator;

    @BeforeEach
    void setUp() {
        compiler = new HyperscanCompiler();
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

    /**
     * Whether the term's single required pattern matches {@code text}, using the term's own flags.
     */
    private boolean matches(String term, String text) throws Exception {
        TranslationResult.Success s = translateOk(term);
        assertThat(s.hsPatterns()).hasSize(1);
        Expression expression = new Expression(s.hsPatterns().getFirst(), compiler.toExpressionFlags(s.hsFlags()));
        try (Database db = Database.compile(expression); Scanner scanner = new Scanner()) {
            scanner.allocScratch(db);
            return !scanner.scan(db, text).isEmpty();
        }
    }

    @Test
    @DisplayName("the reported term no longer matches 'pd' inside 'updates'")
    void reportedTerm_doesNotMatchInsideWord() throws Exception {
        String term = "(righteous babe) OR (pd)";
        assertThat(translateOk(term).hsPatterns()).containsExactly("(?:\\brighteous babe\\b|\\bpd\\b)");

        assertThat(matches(term, "There are following updates")).isFalse();
        assertThat(matches(term, "please send the pd")).isTrue();
        assertThat(matches(term, "the PD team")).isTrue();          // still case-insensitive
        assertThat(matches(term, "ask pd, then reply")).isTrue();   // punctuation is a boundary
        assertThat(matches(term, "pd")).isTrue();                   // start and end of input
        assertThat(matches(term, "a righteous babe appears")).isTrue();
        assertThat(matches(term, "a righteous babes appears")).isFalse();
    }

    @Test
    @DisplayName("a quoted phrase and a multi-word phrase are whole-word too")
    void quotedAndPhrase_wholeWord() throws Exception {
        assertThat(matches("\"pd\"", "following updates")).isFalse();
        assertThat(matches("\"pd\"", "the pd is here")).isTrue();
        assertThat(matches("(bomb this place)", "they bomb this placement")).isFalse();
        assertThat(matches("(bomb this place)", "they bomb this place now")).isTrue();
    }

    @Test
    @DisplayName("a wildcard edge is the explicit opt-out: 'pd*' = prefix, '*pd*' = anywhere")
    void wildcard_isExplicitSubstringOptOut() throws Exception {
        assertThat(translateOk("(pd*)").hsPatterns()).containsExactly("\\bpd\\S*");
        assertThat(matches("(pd*)", "open the pdf")).isTrue();
        assertThat(matches("(pd*)", "following updates")).isFalse();

        assertThat(translateOk("(*pd*)").hsPatterns()).containsExactly("\\S*pd\\S*");
        assertThat(matches("(*pd*)", "following updates")).isTrue();
    }

    @Test
    @DisplayName("proximity operands are whole-word inside the merged single pattern")
    void proximity_operandsWholeWord() throws Exception {
        String term = "(bash) FOLLOWEDBY{3} (fuck)";
        assertThat(matches(term, "bash and fuck")).isTrue();
        assertThat(matches(term, "abash and fuck")).isFalse();
        assertThat(matches(term, "bash and fucking")).isFalse();
    }

    @Test
    @DisplayName("AND NOT: both the required and the excluded pattern carry both boundaries")
    void andNot_bothSidesWholeWord() {
        var s = translateOk("price AND NOT (pd)");
        assertThat(s.hsPatterns()).containsExactly("\\bprice\\b");
        assertThat(s.exclusionRegexs()).containsExactly("\\bpd\\b");
    }

    @Test
    @DisplayName("a term with non-ASCII text keeps substring matching (UCP rejects \\b) and warns")
    void nonAscii_skipsBoundariesAndWarns() {
        var s = translateOk("(café) OR (pd)");
        assertThat(s.hsPatterns()).containsExactly("(?:café|pd)");
        assertThat(s.warnings()).anyMatch(w -> w.contains("Whole-word matching was not applied"));
    }

    @Test
    @DisplayName("a term with no ASCII word characters (pure CJK) gets no warning — nothing was skipped")
    void pureCjk_noWarning() {
        var s = translateOk("(你好)");
        assertThat(s.hsPatterns()).containsExactly("你好");
        assertThat(s.warnings()).noneMatch(w -> w.contains("Whole-word matching"));
    }

    @Test
    @DisplayName("a proximity distance above the word-gap cap still compiles to ONE whole-word pattern: "
            + "the adaptive trial compile must not use UCP, which rejects \\b and would collapse the gap to {0,0}")
    void longProximityDistance_staysOneWholeWordPattern() throws Exception {
        for (String term : new String[]{"a NEAR{50} b", "a NEAR{30} b", "(alpha) FOLLOWEDBY{50} (beta)"}) {
            var s = translateOk(term);
            assertThat(s.hsPatterns()).as(term).hasSize(1);
            assertThat(s.hsPatterns().getFirst()).as(term).contains("\\b").doesNotContain("{0,0}");
        }
        assertThat(matches("alpha NEAR{40} beta", "alpha " + "x ".repeat(10) + "beta")).isTrue();
        assertThat(matches("alpha NEAR{40} beta", "alphabet " + "x ".repeat(10) + "beta")).isFalse();
    }

    @Test
    @DisplayName("CJK and Hebrew terms compile with no \\b, so they can actually hit non-Latin text")
    void nonLatinTerms_haveNoBoundaryAndHit() throws Exception {
        assertThat(translateOk("股票").hsPatterns()).containsExactly("股票");
        assertThat(translateOk("שוק").hsPatterns()).containsExactly("שוק");
        assertThat(matches("股票", "股票操纵市场")).isTrue();
        assertThat(matches("שוק", "מניפולציה בשוק יכולה")).isTrue();
    }

    @Test
    @DisplayName("a mis-encoded term (U+FFFD) is rejected instead of compiling to a PASS that can never match")
    void replacementCharacter_isRejected() {
        TranslationResult r = translator.translate("über�geh*");
        assertThat(r.isSuccess()).isFalse();
        assertThat(((TranslationResult.Error) r).message()).contains("U+FFFD").contains("UTF-8");
    }

    @Test
    @DisplayName("decomposition-fallback leaves (native COMBINATION sub-expressions) carry a leading "
            + "boundary only — Hyperscan rejects a trailing one — and the term says so")
    void decomposedLeaves_leadingBoundaryOnly() {
        String overBudget = "(((wordA word B OR wordC* wordD OR wordE* wordF OR wordG) "
                + "FOLLOWEDBY{4} (wordH* OR wordI wordJ* wordK OR wordL* wordM OR wordN)) FOLLOWEDBY{4} "
                + "(wordO* OR wordP* wordQ OR wordR* wordS OR wordT))";
        var s = translateOk(overBudget);
        assertThat(s.hsPatterns()).hasSize(3);
        assertThat(s.hsPatterns()).allSatisfy(leaf -> {
            assertThat(leaf).contains("\\b");
            assertThat(leaf).doesNotContain("\\b)").doesNotContain("\\b|");
            assertThat(leaf).doesNotEndWith("\\b");
        });
        assertThat(s.warnings()).anyMatch(w -> w.contains("start-of-word only"));
        // resolvedPatterns stays byte-identical to the leaves actually used
        assertThat(s.resolvedPattern()).contains(s.hsPatterns().getFirst());
    }
}
