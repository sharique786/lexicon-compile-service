package com.db.macs3.ecomms.spectre.hyperscan;

import com.db.macs3.ecomms.spectre.model.CompilationStatus;
import com.db.macs3.ecomms.spectre.model.TermCompilationResult;
import com.gliwka.hyperscan.wrapper.Expression;
import com.gliwka.hyperscan.wrapper.ExpressionFlag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link HyperscanCombinationHandler} in isolation.
 *
 * <p><b>AND NOT no longer uses native COMBINATION — see class Javadoc</b>
 * <p>Confirmed unreliable by Hyperscan's own documented eager, progressive
 * combination evaluation: a formula mixing a positive requirement with a
 * negation (e.g. {@code R & !E}) is not a "purely negative" combination
 * (which Hyperscan specially defers to end-of-data), so it can fire the
 * moment {@code R} matches, before {@code E} has had any chance to appear
 * later in the same text. Every AND NOT test below therefore checks the NEW
 * contract: {@link HyperscanCombinationHandler.ExpressionAssignment#hyperscanExpressionId()}
 * is null, {@code requiredExpressionIds()}/{@code excludedExpressionIds()}
 * are populated instead, and every expression built is a plain,
 * individually-reportable one (never QUIET, never COMBINATION).
 *
 * <p>Pure decomposition (no AND NOT) is unaffected and still uses native
 * COMBINATION — those tests are unchanged in substance from before.
 */
@DisplayName("HyperscanCombinationHandler")
class HyperscanCombinationHandlerTest {

    private HyperscanCombinationHandler handler;

    @BeforeEach
    void setUp() {
        HyperscanCompiler compiler = new HyperscanCompiler();
        compiler.selfTest();
        handler = new HyperscanCombinationHandler(compiler);
    }

    private TermCompilationResult passResult(List<String> translatedPattern, boolean requiresExclusionCheck,
                                             List<String> exclusionPattern) {
        return new TermCompilationResult(
                "t::1", "desc", CompilationStatus.PASS,
                translatedPattern, null, null,
                1, requiresExclusionCheck, exclusionPattern, List.of(),
                null, null, null, null, Instant.now());
    }

    @Nested
    @DisplayName("computeIdOffset")
    class ComputeIdOffset {

        @Test
        @DisplayName("Returns max term number + 1")
        void returnsMaxPlusOne() {
            assertThat(handler.computeIdOffset(List.of(1, 2, 3))).isEqualTo(4);
        }

        @Test
        @DisplayName("Works correctly with sparse, non-contiguous term numbers")
        void worksWithSparseNumbers() {
            assertThat(handler.computeIdOffset(List.of(5, 100, 7))).isEqualTo(101);
        }

        @Test
        @DisplayName("Single term number: offset is that number + 1")
        void singleTermNumber() {
            assertThat(handler.computeIdOffset(List.of(42))).isEqualTo(43);
        }

        @Test
        @DisplayName("Empty collection: offset defaults to 1 (never collides, since no term numbers exist)")
        void emptyCollectionDefaultsToOne() {
            assertThat(handler.computeIdOffset(List.of())).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("HyperscanIdAllocator")
    class IdAllocatorBehaviour {

        @Test
        @DisplayName("Hands out strictly sequential ids starting from the given offset")
        void sequentialFromOffset() {
            var allocator = new HyperscanCombinationHandler.HyperscanIdAllocator(10);
            assertThat(allocator.allocate()).isEqualTo(10);
            assertThat(allocator.allocate()).isEqualTo(11);
            assertThat(allocator.allocate()).isEqualTo(12);
        }

        @Test
        @DisplayName("Two independently-constructed allocators do not share state")
        void independentAllocators() {
            var a = new HyperscanCombinationHandler.HyperscanIdAllocator(5);
            var b = new HyperscanCombinationHandler.HyperscanIdAllocator(5);
            assertThat(a.allocate()).isEqualTo(5);
            assertThat(a.allocate()).isEqualTo(6);
            assertThat(b.allocate()).isEqualTo(5); // unaffected by a's allocations
        }
    }

    @Nested
    @DisplayName("addExpressions — plain term (no combination needed)")
    class PlainTerm {

        @Test
        @DisplayName("A single, non-decomposed, non-AND-NOT pattern produces exactly one expression at id=termNumber")
        void producesOneExpressionAtTermNumber() {
            var result = passResult(List.of("insider"), false, null);
            List<Expression> out = new ArrayList<>();
            var assignment = handler.addExpressions(result, 7, new HyperscanCombinationHandler.HyperscanIdAllocator(8), out);

            assertThat(out).hasSize(1);
            assertThat(out.getFirst().getId()).isEqualTo(7);
            assertThat(out.getFirst().getExpression()).isEqualTo("insider");
            assertThat(assignment.hyperscanExpressionId()).isEqualTo(7);
            assertThat(assignment.requiredExpressionIds()).isNull();
            assertThat(assignment.excludedExpressionIds()).isNull();
        }

        @Test
        @DisplayName("patternMapping is null — a single expression IS the whole answer, nothing to map")
        void patternMappingIsNull() {
            var result = passResult(List.of("insider"), false, null);
            List<Expression> out = new ArrayList<>();
            var assignment = handler.addExpressions(result, 7, new HyperscanCombinationHandler.HyperscanIdAllocator(8), out);

            assertThat(assignment.patternMapping()).isNull();
        }

        @Test
        @DisplayName("A plain term's expression is NOT flagged QUIET — it must report directly")
        void plainTermNotQuiet() {
            var result = passResult(List.of("insider"), false, null);
            List<Expression> out = new ArrayList<>();
            handler.addExpressions(result, 1, new HyperscanCombinationHandler.HyperscanIdAllocator(2), out);

            assertThat(out.getFirst().getFlags().contains(ExpressionFlag.QUIET)).isFalse();
        }

        @Test
        @DisplayName("A plain term's expression never carries the COMBINATION flag")
        void plainTermNeverCombination() {
            var result = passResult(List.of("insider"), false, null);
            List<Expression> out = new ArrayList<>();
            handler.addExpressions(result, 1, new HyperscanCombinationHandler.HyperscanIdAllocator(2), out);

            assertThat(out.getFirst().getFlags().contains(ExpressionFlag.COMBINATION)).isFalse();
        }
    }

    @Nested
    @DisplayName("addExpressions — decomposed term, no AND NOT (still uses native COMBINATION — safe, no negation)")
    class DecomposedNoAndNot {

        @Test
        @DisplayName("N leaves + 1 combination = N+1 expressions total; combination at id=termNumber")
        void producesLeavesPlusOneCombination() {
            var result = passResult(List.of("leafA", "leafB", "leafC"), false, null);
            List<Expression> out = new ArrayList<>();
            var assignment = handler.addExpressions(result, 9, new HyperscanCombinationHandler.HyperscanIdAllocator(10), out);

            assertThat(out).hasSize(4); // 3 leaves + 1 combination
            long combinationCount = out.stream().filter(e -> e.getId() == 9).count();
            assertThat(combinationCount).isEqualTo(1);
            assertThat(assignment.hyperscanExpressionId()).isEqualTo(9);
            assertThat(assignment.requiredExpressionIds()).isNull();
            assertThat(assignment.excludedExpressionIds()).isNull();
        }

        @Test
        @DisplayName("Every leaf is QUIET and uses an allocated id, never the term number")
        void leavesAreQuietAndAllocated() {
            var result = passResult(List.of("leafA", "leafB"), false, null);
            List<Expression> out = new ArrayList<>();
            handler.addExpressions(result, 3, new HyperscanCombinationHandler.HyperscanIdAllocator(4), out);

            List<Expression> leaves = out.stream().filter(e -> e.getId() != 3).toList();
            assertThat(leaves).hasSize(2);
            assertThat(leaves).allMatch((Expression e) -> e.getFlags().contains(ExpressionFlag.QUIET));
            assertThat(leaves).allMatch((Expression e) -> e.getId() != 3);
        }

        @Test
        @DisplayName("Every leaf carries ONLY CASELESS + QUIET when the term is ASCII-only — no " +
                "DOTALL/UTF8/UCP/SOM_LEFTMOST")
        void leavesCarryOnlyCaselessAndQuiet() {
            var result = passResult(List.of("leafA", "leafB"), false, null);
            List<Expression> out = new ArrayList<>();
            handler.addExpressions(result, 3, new HyperscanCombinationHandler.HyperscanIdAllocator(4), out);

            List<Expression> leaves = out.stream().filter(e -> e.getId() != 3).toList();
            assertThat(leaves).allMatch((Expression e) -> e.getFlags().equals(
                    java.util.EnumSet.of(ExpressionFlag.CASELESS, ExpressionFlag.QUIET)));
        }

        @Test
        @DisplayName("CONFIRMED-FIXED REGRESSION: a decomposed leaf whose content needs UTF8 (e.g. an " +
                "emoji, \\x{1F600}-encoded) gets UTF8+UCP alongside CASELESS+QUIET — previously fixed at " +
                "CASELESS+QUIET only regardless of content, which made the real combined database build " +
                "fail with \"Hexadecimal value is greater than \\xFF\" for emoji-containing decomposed terms")
        void emojiLeavesGetUtf8Ucp() {
            var result = new TermCompilationResult(
                    "t::1", "desc", CompilationStatus.PASS,
                    List.of("\\x{1F600}", "\\x{1F601}"), null, null,
                    97, false, null, List.of(), // CASELESS(1) | UTF8(32) | UCP(64) = 97
                    null, null, null, null, Instant.now());
            List<Expression> out = new ArrayList<>();
            handler.addExpressions(result, 3, new HyperscanCombinationHandler.HyperscanIdAllocator(4), out);

            List<Expression> leaves = out.stream().filter(e -> e.getId() != 3).toList();
            assertThat(leaves).allMatch((Expression e) -> e.getFlags().equals(java.util.EnumSet.of(
                    ExpressionFlag.CASELESS, ExpressionFlag.QUIET, ExpressionFlag.UTF8, ExpressionFlag.UCP)));
        }

        @Test
        @DisplayName("Combination formula ANDs every leaf id together")
        void combinationFormulaAndsLeafIds() {
            var result = passResult(List.of("leafA", "leafB", "leafC"), false, null);
            List<Expression> out = new ArrayList<>();
            handler.addExpressions(result, 1, new HyperscanCombinationHandler.HyperscanIdAllocator(2), out);

            Expression combo = out.stream().filter(e -> e.getId() == 1).findFirst().orElseThrow();
            // ids 2, 3, 4 allocated in order for the 3 leaves
            assertThat(combo.getExpression()).isEqualTo("(2&3&4)");
        }

        @Test
        @DisplayName("patternMapping mirrors the exact same formula written into the .hdb's native " +
                "COMBINATION expression")
        void patternMappingMirrorsCombinationFormula() {
            var result = passResult(List.of("leafA", "leafB", "leafC"), false, null);
            List<Expression> out = new ArrayList<>();
            var assignment = handler.addExpressions(result, 1, new HyperscanCombinationHandler.HyperscanIdAllocator(2), out);

            Expression combo = out.stream().filter(e -> e.getId() == 1).findFirst().orElseThrow();
            assertThat(assignment.patternMapping()).isEqualTo(combo.getExpression()).isEqualTo("(2&3&4)");
        }
    }

    @Nested
    @DisplayName("addExpressions — AND NOT, neither side decomposed (NO combination — see class Javadoc)")
    class SimpleAndNot {

        @Test
        @DisplayName("Exactly 2 expressions: required (plain) + excluded (plain) — NO combination expression")
        void producesTwoExpressions() {
            var result = passResult(List.of("required"), true, List.of("excluded"));
            List<Expression> out = new ArrayList<>();
            handler.addExpressions(result, 5, new HyperscanCombinationHandler.HyperscanIdAllocator(6), out);

            assertThat(out).hasSize(2);
            assertThat(out.stream().noneMatch(e -> e.getFlags().contains(ExpressionFlag.COMBINATION))).isTrue();
        }

        @Test
        @DisplayName("hyperscanExpressionId is null; requiredExpressionIds/excludedExpressionIds are " +
                "populated with one allocated id each, neither equal to the term number")
        void reportsViaRequiredExcludedIds() {
            var result = passResult(List.of("required"), true, List.of("excluded"));
            List<Expression> out = new ArrayList<>();
            var assignment = handler.addExpressions(result, 1, new HyperscanCombinationHandler.HyperscanIdAllocator(2), out);

            assertThat(assignment.hyperscanExpressionId()).isNull();
            assertThat(assignment.requiredExpressionIds()).containsExactly(2);
            assertThat(assignment.excludedExpressionIds()).containsExactly(3);
            assertThat(assignment.requiredExpressionIds().getFirst()).isNotEqualTo(1);
            assertThat(assignment.excludedExpressionIds().getFirst()).isNotEqualTo(1);
        }

        @Test
        @DisplayName("patternMapping is the AND-NOT formula \"(required&!excluded)\" — the ONLY place this " +
                "formula is recorded, since the .hdb never encodes a native combination for AND NOT")
        void patternMappingIsAndNotFormula() {
            var result = passResult(List.of("required"), true, List.of("excluded"));
            List<Expression> out = new ArrayList<>();
            var assignment = handler.addExpressions(result, 1, new HyperscanCombinationHandler.HyperscanIdAllocator(2), out);

            assertThat(assignment.patternMapping()).isEqualTo("(2&!3)");
        }

        @Test
        @DisplayName("Both expressions are plain — never QUIET — since each must report individually " +
                "for the caller to evaluate the boolean condition after the whole scan completes")
        void bothExpressionsPlainNotQuiet() {
            var result = passResult(List.of("required"), true, List.of("excluded"));
            List<Expression> out = new ArrayList<>();
            handler.addExpressions(result, 1, new HyperscanCombinationHandler.HyperscanIdAllocator(2), out);

            assertThat(out.stream().noneMatch(e -> e.getFlags().contains(ExpressionFlag.QUIET))).isTrue();
        }

        @Test
        @DisplayName("AND NOT expressions carry ONLY CASELESS when the term is ASCII-only — no " +
                "SOM_LEFTMOST/DOTALL/UTF8/UCP, even though SOM_LEFTMOST would be structurally safe " +
                "(neither side is QUIET)")
        void bothExpressionsCarryOnlyCaseless() {
            var result = passResult(List.of("required"), true, List.of("excluded"));
            List<Expression> out = new ArrayList<>();
            handler.addExpressions(result, 1, new HyperscanCombinationHandler.HyperscanIdAllocator(2), out);

            assertThat(out).allMatch((Expression e) -> e.getFlags().equals(java.util.EnumSet.of(ExpressionFlag.CASELESS)));
        }

        @Test
        @DisplayName("CONFIRMED-FIXED REGRESSION: an AND NOT term whose content needs UTF8 (e.g. an " +
                "emoji, \\x{1F6AB}-encoded) gets UTF8+UCP on BOTH required and excluded expressions — " +
                "previously fixed at CASELESS-only regardless of content, which made the real combined " +
                "database build fail with \"Hexadecimal value is greater than \\xFF\" even though " +
                "per-term translation validation had already passed the identical pattern text")
        void emojiAndNotGetsUtf8Ucp() {
            var result = new TermCompilationResult(
                    "t::1", "desc", CompilationStatus.PASS,
                    List.of("\\x{1F6AB}"), null, null,
                    97, true, List.of("\\x{1F4B0}"), List.of(), // CASELESS(1) | UTF8(32) | UCP(64) = 97
                    null, null, null, null, Instant.now());
            List<Expression> out = new ArrayList<>();
            handler.addExpressions(result, 1, new HyperscanCombinationHandler.HyperscanIdAllocator(2), out);

            assertThat(out).allMatch((Expression e) -> e.getFlags().equals(java.util.EnumSet.of(
                    ExpressionFlag.CASELESS, ExpressionFlag.UTF8, ExpressionFlag.UCP)));
        }
    }

    @Nested
    @DisplayName("addExpressions — AND NOT with decomposed sides (NO combination — every leaf reports individually)")
    class DecomposedAndNotSides {

        @Test
        @DisplayName("Decomposed excluded side: every leaf gets its own allocated id, all plain, no combination")
        void decomposedExcludedSideAllPlain() {
            var result = passResult(List.of("required"), true, List.of("exclA", "exclB", "exclC"));
            List<Expression> out = new ArrayList<>();
            var assignment = handler.addExpressions(result, 1, new HyperscanCombinationHandler.HyperscanIdAllocator(2), out);

            // 1 required + 3 excluded leaves = 4 expressions, no combination
            assertThat(out).hasSize(4);
            assertThat(out.stream().noneMatch(e -> e.getFlags().contains(ExpressionFlag.COMBINATION))).isTrue();
            assertThat(out.stream().noneMatch(e -> e.getFlags().contains(ExpressionFlag.QUIET))).isTrue();
            assertThat(assignment.requiredExpressionIds()).hasSize(1);
            assertThat(assignment.excludedExpressionIds()).hasSize(3);
        }

        @Test
        @DisplayName("Decomposed required side with simple excluded: every required leaf plus the " +
                "excluded pattern each get their own plain, individually-reportable expression")
        void decomposedRequiredSideAllPlain() {
            var result = passResult(List.of("reqA", "reqB", "reqC"), true, List.of("excluded"));
            List<Expression> out = new ArrayList<>();
            var assignment = handler.addExpressions(result, 1, new HyperscanCombinationHandler.HyperscanIdAllocator(2), out);

            assertThat(out).hasSize(4); // 3 required leaves + 1 excluded
            assertThat(out.stream().noneMatch(e -> e.getFlags().contains(ExpressionFlag.COMBINATION))).isTrue();
            assertThat(assignment.requiredExpressionIds()).hasSize(3);
            assertThat(assignment.excludedExpressionIds()).hasSize(1);
        }

        @Test
        @DisplayName("Decomposed on both sides: total expression count is the sum of both sides, all plain")
        void decomposedBothSidesAllPlain() {
            var result = passResult(List.of("reqA", "reqB", "reqC"), true, List.of("exclA", "exclB", "exclC"));
            List<Expression> out = new ArrayList<>();
            var assignment = handler.addExpressions(result, 1, new HyperscanCombinationHandler.HyperscanIdAllocator(2), out);

            assertThat(out).hasSize(6);
            assertThat(assignment.requiredExpressionIds()).hasSize(3);
            assertThat(assignment.excludedExpressionIds()).hasSize(3);
        }

        @Test
        @DisplayName("patternMapping: single required id, decomposed excluded side — bare id AND-NOT a " +
                "parenthesised AND-join of the excluded leaves")
        void patternMappingWithDecomposedExcludedSide() {
            var result = passResult(List.of("required"), true, List.of("exclA", "exclB", "exclC"));
            List<Expression> out = new ArrayList<>();
            var assignment = handler.addExpressions(result, 1, new HyperscanCombinationHandler.HyperscanIdAllocator(2), out);

            assertThat(assignment.patternMapping()).isEqualTo("(2&!(3&4&5))");
        }

        @Test
        @DisplayName("patternMapping: decomposed required side, single excluded pattern — parenthesised " +
                "AND-join of the required leaves AND-NOT a bare excluded id")
        void patternMappingWithDecomposedRequiredSide() {
            var result = passResult(List.of("reqA", "reqB", "reqC"), true, List.of("excluded"));
            List<Expression> out = new ArrayList<>();
            var assignment = handler.addExpressions(result, 1, new HyperscanCombinationHandler.HyperscanIdAllocator(2), out);

            assertThat(assignment.patternMapping()).isEqualTo("((2&3&4)&!5)");
        }

        @Test
        @DisplayName("patternMapping: decomposed on both sides — parenthesised AND-join on each side")
        void patternMappingWithBothSidesDecomposed() {
            var result = passResult(List.of("reqA", "reqB", "reqC"), true, List.of("exclA", "exclB", "exclC"));
            List<Expression> out = new ArrayList<>();
            var assignment = handler.addExpressions(result, 1, new HyperscanCombinationHandler.HyperscanIdAllocator(2), out);

            assertThat(assignment.patternMapping()).isEqualTo("((2&3&4)&!(5&6&7))");
        }

        @Test
        @DisplayName("WORKED EXAMPLE from the bug report: required id=8 (single), excluded ids 9/10/11 " +
                "(decomposed FOLLOWEDBY chain) — patternMapping is exactly \"(8&!(9&10&11))\", the value " +
                "a consumer with only the .hdb (no COMBINATION for AND NOT) cannot derive on its own")
        void patternMappingMatchesReportedWorkedExample() {
            var result = passResult(List.of("insider"), true, List.of("exclA", "exclB", "exclC"));
            List<Expression> out = new ArrayList<>();
            var assignment = handler.addExpressions(result, 4, new HyperscanCombinationHandler.HyperscanIdAllocator(8), out);

            assertThat(assignment.requiredExpressionIds()).containsExactly(8);
            assertThat(assignment.excludedExpressionIds()).containsExactly(9, 10, 11);
            assertThat(assignment.patternMapping()).isEqualTo("(8&!(9&10&11))");
        }
    }

    @Nested
    @DisplayName("Flag constraint: a combination expression's flags are exactly {COMBINATION}")
    class CombinationFlagConstraint {

        @Test
        @DisplayName("Pure decomposition (no AND NOT): never mixes CASELESS/UTF8/UCP/DOTALL/SOM_LEFTMOST " +
                "into a combination expression's own flags")
        void combinationFlagsNeverMixed() {
            var result = passResult(List.of("leafA", "leafB"), false, null);
            List<Expression> out = new ArrayList<>();
            handler.addExpressions(result, 1, new HyperscanCombinationHandler.HyperscanIdAllocator(2), out);

            Expression combo = out.stream().filter(e -> e.getId() == 1).findFirst().orElseThrow();
            assertThat(combo.getFlags()).hasSize(1);
            assertThat(combo.getFlags().contains(ExpressionFlag.COMBINATION)).isTrue();
        }

        @Test
        @DisplayName("A plain (non-combination, non-AND-NOT) term's single expression DOES carry " +
                "SOM_LEFTMOST — safe there since it is never QUIET")
        void plainTermCarriesSomLeftmost() {
            var result = passResult(List.of("insider"), false, null);
            List<Expression> out = new ArrayList<>();
            handler.addExpressions(result, 1, new HyperscanCombinationHandler.HyperscanIdAllocator(2), out);

            assertThat(out).hasSize(1);
            assertThat(out.getFirst().getFlags().contains(ExpressionFlag.SOM_LEFTMOST)).isTrue();
        }

        @Test
        @DisplayName("A plain (non-combination, non-AND-NOT) ASCII-only term's single expression carries " +
                "EXACTLY CASELESS + DOTALL + SOM_LEFTMOST, unconditionally — UTF8/UCP stay conditional " +
                "on content (forcing them on unconditionally broke \\b-based regex terms and cost ~15x " +
                "compile time, even for pure-ASCII patterns)")
        void plainAsciiTermCarriesExactlyThreeFlags() {
            var result = passResult(List.of("insider"), false, null); // hyperscanFlags=1 (CASELESS only)
            List<Expression> out = new ArrayList<>();
            handler.addExpressions(result, 1, new HyperscanCombinationHandler.HyperscanIdAllocator(2), out);

            assertThat(out.getFirst().getFlags()).isEqualTo(java.util.EnumSet.of(
                    ExpressionFlag.CASELESS, ExpressionFlag.DOTALL, ExpressionFlag.SOM_LEFTMOST));
        }

        @Test
        @DisplayName("A plain non-ASCII term's single expression additionally carries UTF8 + UCP")
        void plainNonAsciiTermAlsoCarriesUtf8Ucp() {
            var result = new TermCompilationResult(
                    "t::1", "desc", CompilationStatus.PASS,
                    List.of("내부자"), null, null,
                    97, false, null, List.of(), // CASELESS(1) | UTF8(32) | UCP(64) = 97
                    null, null, null, null, Instant.now());
            List<Expression> out = new ArrayList<>();
            handler.addExpressions(result, 1, new HyperscanCombinationHandler.HyperscanIdAllocator(2), out);

            assertThat(out.getFirst().getFlags()).isEqualTo(java.util.EnumSet.of(
                    ExpressionFlag.CASELESS, ExpressionFlag.DOTALL, ExpressionFlag.UTF8,
                    ExpressionFlag.UCP, ExpressionFlag.SOM_LEFTMOST));
        }
    }
}
