package com.db.macs3.ecomms.spectre.translator;

import com.db.macs3.ecomms.spectre.hyperscan.HyperscanCompiler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Focused unit coverage for {@link PatternDecomposer}'s specific new
 * responsibilities as of the {@code resolvedPatterns} change — the broader
 * behavior (plain NEAR/FOLLOWEDBY splitting, AND NOT combinations, chained
 * proximity, byte-identity with {@code resolvedPatterns}) is already
 * exercised end-to-end via {@link TermSyntaxTranslatorTest} and
 * {@code ResolvedPatternMatchingIntegrationTest}. This class targets the one
 * genuinely new case those don't already cover directly: {@code AND}
 * flattening when one of its operands contains nested NEAR/FOLLOWEDBY
 * structure — see {@link PatternDecomposer} class Javadoc "AND is
 * flattened, not left opaque".
 */
@DisplayName("PatternDecomposer — AND flattening with nested proximity")
class PatternDecomposerTest {

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

    @Test
    @DisplayName("AND with one plain operand and one NEAR operand: simple/safe, so "
            + "TermSyntaxTranslator#resolveSide merges it into ONE gap-embedded pattern rather than "
            + "using PatternDecomposer's flattened leaves directly — resolvedPatterns still conveys "
            + "the full AND/NEAR structure as literal keyword text either way")
    void andWithNestedNear_mergesIntoOnePattern() {
        var s = translateOk("price AND (insider NEAR{5} trading)");
        assertThat(s.hsPatterns()).hasSize(1);
        assertThat(s.hsPatterns().getFirst()).contains("price").contains("insider").contains("trading");
        assertThat(s.resolvedPattern()).isEqualTo("\\bprice\\b AND \\binsider\\b NEAR{5} \\btrading\\b");
    }

    @Test
    @DisplayName("AND with one plain operand and one FOLLOWEDBY operand merges the same way")
    void andWithNestedFollowedBy_mergesIntoOnePattern() {
        var s = translateOk("price AND (insider FOLLOWEDBY{3} trading)");
        assertThat(s.hsPatterns()).hasSize(1);
        assertThat(s.hsPatterns().getFirst()).contains("price").contains("insider").contains("trading");
        assertThat(s.resolvedPattern()).isEqualTo("\\bprice\\b AND \\binsider\\b FOLLOWEDBY{3} \\btrading\\b");
    }

    @Test
    @DisplayName("A plain AND with NO nested proximity anywhere stays exactly ONE self-contained "
            + "permutation pattern — completely unaffected by the flattening logic")
    void plainAndWithNoNestedProximity_staysOneLeaf() {
        var s = translateOk("price AND rigging AND announcement");
        assertThat(s.hsPatterns()).hasSize(1);
        assertThat(s.resolvedPattern()).isEqualTo(s.hsPatterns().getFirst());
    }

    @Test
    @DisplayName("AND flattening's resolvedPatterns recurses through nested AND, not just one level "
            + "deep — merged into ONE pattern for hsPatterns since this simple case is safe to compile")
    void andFlattening_recursesThroughNestedAnd() {
        var s = translateOk("a AND (b AND (c NEAR{2} d))");
        assertThat(s.hsPatterns()).hasSize(1);
        assertThat(s.hsPatterns().getFirst()).contains("a").contains("b").contains("c").contains("d");
        assertThat(s.resolvedPattern()).isEqualTo("\\ba\\b AND \\bb\\b AND \\bc\\b NEAR{2} \\bd\\b");
    }

    @Test
    @DisplayName("AND does NOT flatten through an OR sibling operand even when that OR itself "
            + "wraps a NEAR — OR stays opaque, matching the OR-nested-proximity exception")
    void andDoesNotFlattenThroughOrOperand() {
        // "b OR (c NEAR{2} d)" is one AND operand — the whole OR stays ONE opaque,
        // gap-embedded leaf (the OR-nested-proximity residual path), so the outer AND
        // only ever sees TWO operands to combine: "a" and that whole OR expression.
        var s = translateOk("a AND (b OR (c NEAR{2} d))");
        assertThat(s.hsPatterns()).hasSize(1); // self-contained AND permutation of 2 operands
        assertThat(s.hsPatterns().getFirst()).contains("b").contains("c").contains("d");
        assertThat(s.resolvedPattern()).isEqualTo(s.hsPatterns().getFirst());
    }

    @Test
    @DisplayName("AND flattening still falls back to independent leaves — the historical behavior — "
            + "when the nested proximity operand is genuinely too complex to merge into one pattern")
    void andWithOverBudgetNestedProximity_stillFlattensIntoLeaves() {
        // Same wide-OR, nested-FOLLOWEDBY shape confirmed over budget / rejected as a single
        // pattern elsewhere in this suite (see TermSyntaxTranslatorTest's "Pattern complexity
        // validation" nested class) — wrapping it as an AND operand alongside "price" exercises
        // decomposeAnd's flattening specifically for the case resolveSide can't merge away.
        String overBudgetNestedFollowedBy = "(((wordA word B OR wordC* wordD OR wordE* wordF OR wordG) "
                + "FOLLOWEDBY{4} (wordH* OR wordI wordJ* wordK OR wordL* wordM OR wordN)) FOLLOWEDBY{4} "
                + "(wordO* OR wordP* wordQ OR wordR* wordS OR wordT))";
        var s = translateOk("price AND " + overBudgetNestedFollowedBy);
        assertThat(s.hsPatterns()).hasSize(4); // "price" + the 3 leaves the nested chain decomposes into
        assertThat(s.hsPatterns().getFirst()).isEqualTo("\\bprice");
        assertThat(s.resolvedPattern()).startsWith("\\bprice AND ");
    }
}
