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
    @DisplayName("AND with one plain operand and one NEAR operand flattens into 3 independent, "
            + "gap-less leaves — not a single self-contained AND permutation pattern")
    void andWithNestedNear_flattensIntoThreeLeaves() {
        var s = translateOk("price AND (insider NEAR{5} trading)");
        assertThat(s.hsPatterns()).containsExactly("price", "insider", "trading");
        assertThat(s.resolvedPattern()).isEqualTo("price AND insider NEAR{5} trading");
    }

    @Test
    @DisplayName("AND with one plain operand and one FOLLOWEDBY operand flattens the same way")
    void andWithNestedFollowedBy_flattens() {
        var s = translateOk("price AND (insider FOLLOWEDBY{3} trading)");
        assertThat(s.hsPatterns()).containsExactly("price", "insider", "trading");
        assertThat(s.resolvedPattern()).isEqualTo("price AND insider FOLLOWEDBY{3} trading");
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
    @DisplayName("AND flattening recurses through nested AND, not just one level deep")
    void andFlattening_recursesThroughNestedAnd() {
        var s = translateOk("a AND (b AND (c NEAR{2} d))");
        assertThat(s.hsPatterns()).containsExactly("a", "b", "c", "d");
        assertThat(s.resolvedPattern()).isEqualTo("a AND b AND c NEAR{2} d");
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
}
