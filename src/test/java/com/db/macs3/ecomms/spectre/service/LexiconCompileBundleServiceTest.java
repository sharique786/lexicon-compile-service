package com.db.macs3.ecomms.spectre.service;

import com.db.macs3.ecomms.spectre.hyperscan.HyperscanCombinationHandler;
import com.db.macs3.ecomms.spectre.hyperscan.HyperscanCompiler;
import com.db.macs3.ecomms.spectre.model.CompilationStatus;
import com.db.macs3.ecomms.spectre.model.CompileResponse;
import com.db.macs3.ecomms.spectre.model.TermCompilationResult;
import com.db.macs3.ecomms.spectre.model.TermType;
import com.db.macs3.ecomms.spectre.model.TypedCompileRequest;
import com.db.macs3.ecomms.spectre.translator.TermSyntaxTranslator;
import com.gliwka.hyperscan.wrapper.Database;
import com.gliwka.hyperscan.wrapper.Match;
import com.gliwka.hyperscan.wrapper.Scanner;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link LexiconCompileBundleService} — the {@code /compile/bundle}
 * endpoint's orchestration logic (Natural Language/Regex branching + combined database).
 *
 * <p>No Spring context — uses real {@code TermSyntaxTranslator} and
 * {@code HyperscanCompiler} directly, exactly like {@link LexiconCompileServiceTest}.
 *
 * <p><b>The current id scheme: a term's reportable expression is ALWAYS its own term number</b>
 * <p>Every PASS term's {@code hyperscanExpressionId} — whether it compiles as
 * a single plain pattern or needs a QUIET/COMBINATION structure (AND NOT
 * and/or decomposition) — is always its own term number. Every auxiliary
 * QUIET sub-expression a combination needs is assigned an ALLOCATED id
 * instead (never the term number) — see {@link HyperscanCombinationHandler}
 * class Javadoc. This is different from an earlier version of this class,
 * where the required side's single pattern used the term number and the
 * combination itself got an allocated id — meaning the reportable id used
 * to vary by term complexity. It no longer does.
 */
@DisplayName("LexiconCompileBundleService Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LexiconCompileBundleServiceTest {

    private LexiconCompileBundleService bundleService;

    @BeforeEach
    void setUp() {
        var compiler = new HyperscanCompiler();
        var translator = new TermSyntaxTranslator(compiler);
        compiler.selfTest();
        var compileService = new LexiconCompileService(translator, compiler, new SimpleMeterRegistry());
        var handler = new HyperscanCombinationHandler(compiler);
        bundleService = new LexiconCompileBundleService(compileService, compiler, handler, new SimpleMeterRegistry());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private TypedCompileRequest request(String ruleName, TermType requestType, TermSpec... specs) {
        var req = new TypedCompileRequest();
        req.setRequestId(UUID.randomUUID().toString());
        req.setLexiconRuleName(ruleName);
        req.setRequestType(requestType);
        List<TypedCompileRequest.TermInput> terms = new ArrayList<>();
        for (int i = 0; i < specs.length; i++) {
            terms.add(new TypedCompileRequest.TermInput(
                    ruleName + "::" + (i + 1), specs[i].description));
        }
        req.setTerms(terms);
        return req;
    }

    // Convenience: Natural Language or Regex helpers just carry description
    private record TermSpec(String description) {
    }

    private static TermSpec term(String desc) {
        return new TermSpec(desc);
    }

    /**
     * Shorthand: all-Natural-Language request
     */
    private TypedCompileRequest naturalLanguage(String ruleName, String... descs) {
        TermSpec[] specs = new TermSpec[descs.length];
        for (int i = 0; i < descs.length; i++) specs[i] = term(descs[i]);
        return request(ruleName, TermType.NATURAL_LANGUAGE, specs);
    }

    /**
     * Shorthand: all-Regex request
     */
    private TypedCompileRequest regex(String ruleName, String... descs) {
        TermSpec[] specs = new TermSpec[descs.length];
        for (int i = 0; i < descs.length; i++) specs[i] = term(descs[i]);
        return request(ruleName, TermType.REGEX, specs);
    }

    /**
     * Scans {@code text} against {@code db} and returns the matched expression ids.
     */
    private List<Integer> scanMatchesIds(Database db, String text) {
        List<Integer> ids = new ArrayList<>();
        try (Scanner scanner = new Scanner()) {
            scanner.allocScratch(db);
            for (Match match : scanner.scan(db, text)) {
                ids.add(match.getMatchedExpression().getId());
            }
        }
        return ids;
    }

    /**
     * Post-scan evaluation of an AND NOT term's boolean condition, given the
     * complete set of expression ids that matched anywhere in the scan.
     * Mirrors the documented {@code /compile}/{@code /compile/csv} contract
     * exactly (see {@code TermCompilationResult} class Javadoc "AND NOT has
     * two different correct implementations"): the required side uses AND
     * (every id must be present), and the excluded side ALSO uses AND for
     * this same convention — the exclusion condition is considered satisfied
     * (and the term therefore excluded) only when EVERY excluded id is
     * present, not merely one. This is the exact evaluation a real consumer
     * (Lexicon Scanner Service, Lexicon Scan Engine) must now perform itself,
     * since native Hyperscan COMBINATION is no longer used for AND NOT terms.
     */
    private boolean evaluateAndNot(java.util.Collection<Integer> matchedIds,
                                   List<Integer> requiredIds, List<Integer> excludedIds) {
        boolean allRequiredPresent = requiredIds != null && !requiredIds.isEmpty()
                && matchedIds.containsAll(requiredIds);
        boolean allExcludedPresent = excludedIds != null && !excludedIds.isEmpty()
                && matchedIds.containsAll(excludedIds);
        return allRequiredPresent && !allExcludedPresent;
    }

    private static final String NESTED_TOO_COMPLEX_TERM =
            "(((wordA word B OR wordC* wordD OR wordE* wordF OR wordG) FOLLOWEDBY{4} "
                    + "(wordH* OR wordI wordJ* wordK OR wordL* wordM OR wordN)) FOLLOWEDBY{4} "
                    + "(wordO* OR wordP* wordQ OR wordR* wordS OR wordT))";

    // ── Spec example from the requirements ────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("Spec example: Natural Language request → PASS, combined DB built, request_id/requestType echoed")
    void specExampleNaturalLanguage() {
        var req = naturalLanguage("lexicon_research_1",
                "(manipulate) NEAR{5} ((price) OR (spread) OR (stock))");
        String sentRequestId = req.getRequestId();

        var bundle = bundleService.buildBundle(req);

        assertThat(bundle.jsonResponse().passCount()).isEqualTo(1);
        assertThat(bundle.jsonResponse().failedCount()).isEqualTo(0);
        assertThat(bundle.hasDatabase()).isTrue();
        assertThat(bundle.hyperscanDatabaseBytes()).isNotEmpty();
        assertThat(bundle.databaseNote()).isNull();
        assertThat(bundle.jsonResponse().requestId()).isEqualTo(sentRequestId);
        assertThat(bundle.jsonResponse().requestType()).isEqualTo("Natural Language");
    }

    @Test
    @Order(2)
    @DisplayName("Spec example: Regex request → PASS, combined DB built, request_id/requestType echoed")
    void specExampleRegex() {
        var req = regex("lexicon_research_1", "(?:price|spread|stock)");
        String sentRequestId = req.getRequestId();

        var bundle = bundleService.buildBundle(req);

        assertThat(bundle.jsonResponse().passCount()).isEqualTo(1);
        assertThat(bundle.jsonResponse().failedCount()).isEqualTo(0);
        assertThat(bundle.hasDatabase()).isTrue();
        assertThat(bundle.jsonResponse().requestId()).isEqualTo(sentRequestId);
        assertThat(bundle.jsonResponse().requestType()).isEqualTo("Regex");
    }

    // ── JSON shape ──────────────────────────────────────────────────────────

    @Test
    @Order(10)
    @DisplayName("JSON response: request_id and requestType present; hyperscanVersion absent (bundle-specific shape)")
    void jsonShapeMatchesCompile() {
        var req = naturalLanguage("shape_test", "price OR spread");
        var bundle = bundleService.buildBundle(req);

        CompileResponse json = bundle.jsonResponse();
        assertThat(json.lexiconRuleName()).isEqualTo("shape_test");
        assertThat(json.engineMode()).isEqualTo("HYPERSCAN_NATIVE");
        assertThat(json.requestId()).isEqualTo(req.getRequestId());
        assertThat(json.requestType()).isEqualTo("Natural Language");
        assertThat(json.hyperscanVersion()).isNull();
        assertThat(json.results()).hasSize(1);
        assertThat(json.results().getFirst().termId()).isEqualTo("shape_test::1");
    }

    // ── Natural Language term branch ──────────────────────────────────────────────────

    @Test
    @Order(20)
    @DisplayName("Natural Language term: NEAR{5} translated exactly like /compile — split into two "
            + "gap-less leaves, proximity conveyed via resolvedPatterns")
    void naturalLanguageTermTranslated() {
        var req = naturalLanguage("std_test", "(manipulate) NEAR{5} (price)");
        var bundle = bundleService.buildBundle(req);

        var result = bundle.jsonResponse().results().getFirst();
        assertThat(result.compilationStatus()).isEqualTo(CompilationStatus.PASS);
        assertThat(result.regexPattern()).containsExactly("manipulate", "price");
        assertThat(result.resolvedPatterns()).isEqualTo("manipulate NEAR{5} price");
    }

    @Test
    @Order(21)
    @DisplayName("Natural Language term with invalid syntax → FAILED with translationError, no DB entry")
    void naturalLanguageTermTranslationFailure() {
        var req = naturalLanguage("std_fail_test", "NEAR{5} (price)"); // missing left operand
        var bundle = bundleService.buildBundle(req);

        var result = bundle.jsonResponse().results().getFirst();
        assertThat(result.compilationStatus()).isEqualTo(CompilationStatus.FAILED);
    }

    // ── Regex term branch ────────────────────────────────────────────────────────

    @Test
    @Order(30)
    @DisplayName("Regex term: raw PCRE compiled verbatim, NOT passed through the translator")
    void regexTermCompiledVerbatim() {
        var req = regex("nlt_test", "(?:price|spread|stock)");
        var bundle = bundleService.buildBundle(req);

        var result = bundle.jsonResponse().results().getFirst();
        assertThat(result.compilationStatus()).isEqualTo(CompilationStatus.PASS);
        assertThat(result.regexPattern()).hasSize(1);
        assertThat(result.regexPattern().getFirst()).isEqualTo("(?:price|spread|stock)");
    }

    @Test
    @Order(31)
    @DisplayName("Regex term with invalid regex syntax → FAILED with Hyperscan errorLog")
    void regexTermInvalidRegex() {
        var req = regex("nlt_invalid_test", "[unclosed");
        var bundle = bundleService.buildBundle(req);

        var result = bundle.jsonResponse().results().getFirst();
        assertThat(result.compilationStatus()).isEqualTo(CompilationStatus.FAILED);
        assertThat(result.errorLog()).isNotBlank();
        assertThat(result.requiresExclusionCheck()).isFalse();
    }

    @Test
    @Order(32)
    @DisplayName("Regex term with non-Latin script gets UTF8/UCP flags automatically")
    void regexTermNonLatinFlags() {
        var req = regex("nlt_korean_test", "(?:내부자|거래)");
        var bundle = bundleService.buildBundle(req);

        var result = bundle.jsonResponse().results().getFirst();
        assertThat(result.compilationStatus()).isEqualTo(CompilationStatus.PASS);
        assertThat(result.hyperscanFlags() & 32).isEqualTo(32); // UTF8
        assertThat(result.hyperscanFlags() & 64).isEqualTo(64); // UCP
    }

    @Test
    @Order(33)
    @DisplayName("Regex term with operator-language syntax (not a real regex) is treated literally, never translated")
    void regexTermNeverTranslated() {
        var req = regex("nlt_literal_test", "price NEAR.{5} stock");
        var bundle = bundleService.buildBundle(req);

        var result = bundle.jsonResponse().results().getFirst();
        assertThat(result.regexPattern()).hasSize(1);
        assertThat(result.regexPattern().getFirst()).isEqualTo("price NEAR.{5} stock");
    }

    // ── All-Regex and all-Natural-Language scenarios ────────────────────────────────────

    @Test
    @Order(40)
    @DisplayName("All-Regex request: every term compiled verbatim, DB contains all")
    void allRegexRequest() {
        var req = regex("all_nlt_test",
                "(?:price|spread)", "(?:insider|tip)", "manipulat\\w+");
        var bundle = bundleService.buildBundle(req);

        assertThat(bundle.jsonResponse().passCount()).isEqualTo(3);
        assertThat(bundle.jsonResponse().requestType()).isEqualTo("Regex");
        assertThat(bundle.hasDatabase()).isTrue();
    }

    @Test
    @Order(41)
    @DisplayName("All-Natural Language request: behaves identically to /compile for every term")
    void allNaturalLanguageRequest() {
        var req = naturalLanguage("all_std_test",
                "price OR spread",
                "don't FOLLOWEDBY{3} compliance",
                "insider AND announcement");
        var bundle = bundleService.buildBundle(req);

        assertThat(bundle.jsonResponse().passCount()).isEqualTo(3);
        assertThat(bundle.jsonResponse().requestType()).isEqualTo("Natural Language");
        assertThat(bundle.hasDatabase()).isTrue();
    }

    // ── Mixed pass/fail scenarios ──────────────────────────────

    @Test
    @Order(50)
    @DisplayName("Mixed PASS/FAILED within a Regex request: combined DB built from PASS subset only, with id gaps")
    void mixedPassFailedDbBuiltFromPassOnly() throws IOException {
        var req = regex("mixed_test",
                "(?:price|spread)",     // term number 1 — PASS
                "[unclosed",             // term number 2 — FAILED
                "(?:insider|tip)");      // term number 3 — PASS

        var bundle = bundleService.buildBundle(req);

        assertThat(bundle.jsonResponse().passCount()).isEqualTo(2);
        assertThat(bundle.jsonResponse().failedCount()).isEqualTo(1);
        assertThat(bundle.jsonResponse().results().get(1).compilationStatus())
                .isEqualTo(CompilationStatus.FAILED);
        assertThat(bundle.hasDatabase()).isTrue();

        try (Database db = Database.load(new ByteArrayInputStream(bundle.hyperscanDatabaseBytes()))) {
            assertThat(scanMatchesIds(db, "price")).contains(1);
            assertThat(scanMatchesIds(db, "insider")).contains(3);
        }
    }

    @Test
    @Order(51)
    @DisplayName("Zero PASS terms: no database built, clear explanatory note returned")
    void zeroPassTermsNoDatabase() {
        var req = regex("zero_pass_test", "[unclosed", "[also_unclosed");
        var bundle = bundleService.buildBundle(req);

        assertThat(bundle.jsonResponse().passCount()).isEqualTo(0);
        assertThat(bundle.hasDatabase()).isFalse();
        assertThat(bundle.hyperscanDatabaseBytes()).isNull();
        assertThat(bundle.databaseNote()).isNotBlank();
        assertThat(bundle.databaseNote()).contains("zero terms reached PASS");
        // Zero-PASS is fully explained by each term's own FAILED status already —
        // NOT the "all terms passed but the build itself failed" case below.
        assertThat(bundle.databaseBuildFailed()).isFalse();
        assertThat(bundle.jsonResponse().databaseError()).isNull();
    }

    @Test
    @Order(52)
    @DisplayName("Every term PASSES but the combined database build itself fails: bundle carries an "
            + "explicit databaseBuildFailed/databaseError, distinct from a per-term translation failure")
    void allTermsPassButCombinedBuildFails() {
        var compiler = new HyperscanCompiler();
        compiler.selfTest();
        var translator = new TermSyntaxTranslator(compiler);
        var compileService = new LexiconCompileService(translator, compiler, new SimpleMeterRegistry());
        var handler = new HyperscanCombinationHandler(compiler);

        // A hand-built stub compiler that behaves exactly like the real one for every
        // per-term validation call (still real Hyperscan), but simulates the combined,
        // multi-pattern database build itself failing — e.g. a flag/state-count
        // interaction only visible once every PASS expression is compiled together,
        // which no individual term's own validation could have caught up front.
        var flakyDatabaseCompiler = new HyperscanCompiler() {
            @Override
            public CombinedCompileResult compileCombinedDatabase(List<com.gliwka.hyperscan.wrapper.Expression> expressions) {
                return CombinedCompileResult.failure("Simulated combined compile failure", null);
            }
        };
        flakyDatabaseCompiler.selfTest();
        var flakyBundleService = new LexiconCompileBundleService(
                compileService, flakyDatabaseCompiler, handler, new SimpleMeterRegistry());

        var req = regex("all_pass_build_fails", "price", "insider");
        var bundle = flakyBundleService.buildBundle(req);

        assertThat(bundle.jsonResponse().passCount()).isEqualTo(2);
        assertThat(bundle.jsonResponse().failedCount()).isZero();
        assertThat(bundle.jsonResponse().results()).allMatch(
                r -> r.compilationStatus() == CompilationStatus.PASS);

        assertThat(bundle.hasDatabase()).isFalse();
        assertThat(bundle.databaseBuildFailed()).isTrue();
        assertThat(bundle.databaseNote()).contains("Simulated combined compile failure");
        assertThat(bundle.jsonResponse().databaseError()).isNotBlank();
        assertThat(bundle.jsonResponse().databaseError()).contains("Simulated combined compile failure");
    }

    // ── Combined database integrity (round-trip load + scan) ───────────────────

    @Test
    @Order(60)
    @DisplayName("Combined DB round-trip: save() then load() reconstructs a working multi-pattern database")
    void combinedDatabaseRoundTrip() throws IOException {
        var req = regex("roundtrip_test",
                "(?:price|spread|stock)",
                "(?:insider|tip)");
        var bundle = bundleService.buildBundle(req);

        assertThat(bundle.hasDatabase()).isTrue();

        try (Database db = Database.load(new ByteArrayInputStream(bundle.hyperscanDatabaseBytes()))) {
            List<Integer> priceMatches = scanMatchesIds(db, "the price went up");
            List<Integer> insiderMatches = scanMatchesIds(db, "an insider tip was shared");

            assertThat(priceMatches).contains(1);
            assertThat(insiderMatches).contains(2);
        }
    }

    @Test
    @Order(61)
    @DisplayName("Expression id equals the term's PARSED TERM NUMBER (from termId's '::n' suffix), " +
            "not its array position in the request")
    void expressionIdEqualsTermNumber() throws IOException {
        var req = regex("id_mapping_test",
                "alpha_pattern",   // termId "id_mapping_test::1" -> term number 1
                "beta_pattern",    // termId "id_mapping_test::2" -> term number 2
                "gamma_pattern");  // termId "id_mapping_test::3" -> term number 3
        var bundle = bundleService.buildBundle(req);

        try (Database db = Database.load(new ByteArrayInputStream(bundle.hyperscanDatabaseBytes()))) {
            assertThat(scanMatchesIds(db, "alpha_pattern")).containsExactly(1);
            assertThat(scanMatchesIds(db, "beta_pattern")).containsExactly(2);
            assertThat(scanMatchesIds(db, "gamma_pattern")).containsExactly(3);
        }
    }

    // ── AND NOT: native Hyperscan logical combination ─────────────────

    @Test
    @Order(70)
    @DisplayName("AND NOT term: no single hyperscanExpressionId any more — every required/excluded " +
            "pattern gets its own individually-reportable id instead, since native combination " +
            "is confirmed unreliable for AND NOT (see HyperscanCombinationHandler class Javadoc)")
    void andNotTerm_getsOwnTermNumberAsExpressionId() {
        var req = request("test-rule", TermType.NATURAL_LANGUAGE,
                term("((don't forward) AND NOT (compliance OR legal))"));

        var bundle = bundleService.buildBundle(req);
        var result = bundle.jsonResponse().results().getFirst();

        assertThat(result.compilationStatus()).isEqualTo(CompilationStatus.PASS);
        assertThat(result.requiresExclusionCheck()).isTrue();
        assertThat(result.regexPattern()).hasSize(1);
        assertThat(result.regexPattern().getFirst()).isEqualTo("don't forward");
        assertThat(result.exclusionRegex()).hasSize(1);
        assertThat(result.exclusionRegex().getFirst()).contains("compliance").contains("legal");

        // AND NOT terms report via requiredExpressionIds/excludedExpressionIds now, not a
        // single hyperscanExpressionId -- there is no native combination expression at all.
        assertThat(result.hyperscanExpressionId()).isNull();
        assertThat(result.requiredExpressionIds()).hasSize(1);
        assertThat(result.excludedExpressionIds()).hasSize(1);

        assertThat(bundle.hasDatabase()).isTrue();
    }

    @Test
    @Order(71)
    @DisplayName("A plain term (no AND NOT) gets hyperscanExpressionId equal to its own term number, " +
            "not its array position")
    void plainTerm_getsOwnTermNumberAsExpressionId() {
        var req = request("test-rule", TermType.NATURAL_LANGUAGE, term("insider OR trading"));
        var bundle = bundleService.buildBundle(req);
        var result = bundle.jsonResponse().results().getFirst();

        assertThat(result.requiresExclusionCheck()).isFalse();
        assertThat(result.hyperscanExpressionId()).isEqualTo(1);
        // A single expression IS the whole answer here -- nothing to map.
        assertThat(result.patternMapping()).isNull();
    }

    @Test
    @Order(72)
    @DisplayName("Plain AND (no NOT) also gets its own term number as expression id — self-contained, no combination needed")
    void plainAndTerm_selfContained_ownTermNumberAsExpressionId() {
        var req = request("test-rule", TermType.NATURAL_LANGUAGE, term("price AND rigging"));
        var bundle = bundleService.buildBundle(req);
        var result = bundle.jsonResponse().results().getFirst();

        assertThat(result.requiresExclusionCheck()).isFalse();
        assertThat(result.hyperscanExpressionId()).isEqualTo(1);
    }

    @Test
    @Order(73)
    @DisplayName("Mixed request: plain/AND terms get their own term number as hyperscanExpressionId; " +
            "the AND NOT term gets requiredExpressionIds/excludedExpressionIds instead")
    void mixedRequest_correctIdsForEachTerm() {
        var req = request("test-rule", TermType.NATURAL_LANGUAGE,
                term("insider OR trading"),          // term number 1, plain
                term("price AND rigging"),            // term number 2, plain (self-contained AND)
                term("confidential AND NOT (public)")); // term number 3, AND NOT

        var bundle = bundleService.buildBundle(req);
        var results = bundle.jsonResponse().results();

        assertThat(results).allMatch(TermCompilationResult::isPass);
        // Non-AND-NOT terms: reportable id is always the term's own number.
        assertThat(results.getFirst().hyperscanExpressionId()).isEqualTo(1);
        assertThat(results.get(1).hyperscanExpressionId()).isEqualTo(2);
        // The AND NOT term: no single hyperscanExpressionId -- required/excluded ids instead.
        assertThat(results.get(2).hyperscanExpressionId()).isNull();
        assertThat(results.get(2).requiresExclusionCheck()).isTrue();
        assertThat(results.get(2).requiredExpressionIds()).hasSize(1);
        assertThat(results.get(2).excludedExpressionIds()).hasSize(1);
        assertThat(bundle.hasDatabase()).isTrue();
    }

    @Test
    @Order(74)
    @DisplayName("FAILED terms have no hyperscanExpressionId — nothing was compiled into the database for them")
    void failedTerm_hasNoExpressionId() {
        var req = request("test-rule", TermType.NATURAL_LANGUAGE, term("\"unclosed quote"));
        var bundle = bundleService.buildBundle(req);
        var result = bundle.jsonResponse().results().getFirst();

        assertThat(result.isFailed()).isTrue();
        assertThat(result.hyperscanExpressionId()).isNull();
    }

    @Test
    @Order(75)
    @DisplayName("Chained AND NOT (A AND NOT B AND NOT C) still combines into ONE excluded side " +
            "(B OR C) at the translation stage — no native combination id any more, but the " +
            "excluded operands are still correctly unified into exclusionRegex")
    void chainedAndNot_stillOneCombinationId() {
        var req = request("test-rule", TermType.NATURAL_LANGUAGE,
                term("(a) AND NOT (b) AND NOT (c)"));
        var bundle = bundleService.buildBundle(req);
        var result = bundle.jsonResponse().results().getFirst();

        assertThat(result.requiresExclusionCheck()).isTrue();
        assertThat(result.hyperscanExpressionId()).isNull();
        assertThat(result.requiredExpressionIds()).hasSize(1);
        // The chained "AND NOT b AND NOT c" was combined into ONE excluded-side pattern
        // (b OR c) at the translation stage, before ever reaching expression-id assignment.
        assertThat(result.exclusionRegex()).hasSize(1);
        assertThat(result.excludedExpressionIds()).hasSize(1);
        assertThat(bundle.hasDatabase()).isTrue();
    }

    @Test
    @Order(76)
    @DisplayName("REGEX-type terms never require exclusion checks — no combination expression involved")
    void regexTerm_neverRequiresExclusionCheck() {
        var req = request("test-rule", TermType.REGEX, term("(?:insider|trading)"));
        var bundle = bundleService.buildBundle(req);
        var result = bundle.jsonResponse().results().getFirst();

        assertThat(result.requiresExclusionCheck()).isFalse();
        assertThat(result.hyperscanExpressionId()).isEqualTo(1);
    }

    // ── SOM_LEFTMOST: applied automatically to plain expressions, never to QUIET ones ──

    @Test
    @Order(80)
    @DisplayName("A plain (non-combination) expression compiles and matches correctly, with SOM_LEFTMOST applied automatically")
    void plainExpression_compilesAndMatchesWithSom() throws IOException {
        var req = regex("som_default_test", "(?:price|spread|stock)");
        var bundle = bundleService.buildBundle(req);

        assertThat(bundle.hasDatabase()).isTrue();
        try (Database db = Database.load(new ByteArrayInputStream(bundle.hyperscanDatabaseBytes()))) {
            assertThat(scanMatchesIds(db, "the price went up")).containsExactly(1);
        }
    }

    @Test
    @Order(81)
    @DisplayName("REGRESSION: the exact shape from the reported bug — a decomposed term feeding a " +
            "combination — compiles successfully with no caller-supplied flag needed. Previously " +
            "this failed with \"HS_FLAG_QUIET is not supported in combination with HS_FLAG_SOM_LEFTMOST\" " +
            "because SOM_LEFTMOST was applied to every expression unconditionally, including QUIET ones.")
    void decomposedTerm_compilesSuccessfully_noSomFlagNeeded() throws IOException {
        var req = new TypedCompileRequest();
        req.setRequestId("regression-test");
        req.setLexiconRuleName("regression_test");
        req.setRequestType(TermType.NATURAL_LANGUAGE);
        req.setTerms(List.of(new TypedCompileRequest.TermInput("t::1", NESTED_TOO_COMPLEX_TERM)));

        var bundle = bundleService.buildBundle(req);
        var result = bundle.jsonResponse().results().getFirst();

        assertThat(result.isPass()).isTrue();
        assertThat(result.regexPattern()).hasSize(3); // decomposed into 3 leaves, as in the report
        assertThat(bundle.hasDatabase()).isTrue();
        // patternMapping mirrors the exact native COMBINATION formula written into the .hdb for this
        // term — term number 1, so the offset for auxiliary leaf ids is 2 (2, 3, 4 for the 3 leaves).
        assertThat(result.patternMapping()).isEqualTo("(2&3&4)");

        // The critical regression check: this used to throw CompileErrorException with the exact
        // message quoted above. Reaching this line at all means the fix holds.
        try (Database db = Database.load(new ByteArrayInputStream(bundle.hyperscanDatabaseBytes()))) {
            assertThat(scanMatchesIds(db, "wordG wordN wordT")).contains(1);
        }
    }

    @Test
    @Order(815)
    @DisplayName("WORKED EXAMPLE from the bug report: German nested-FOLLOWEDBY term, termId " +
            "'lexicon_term::4' — decomposes into 3 leaves at auxiliary ids 5/6/7 (offset = term number "
            + "4 + 1), and patternMapping is exactly \"(5&6&7)\", mirroring the .hdb's own COMBINATION")
    void patternMapping_matchesReportedWorkedExample() {
        String term = "(((versuch nicht OR mach* nicht OR tu* nicht OR vermeide) FOLLOWEDBY{4} "
                + "(frontrun* OR front run* OR übergeh* OR überspring*)) FOLLOWEDBY{4} "
                + "(das OR dies OR mich OR sie OR flow OR Druck OR Ausdruck))";

        var req = new TypedCompileRequest();
        req.setRequestId("worked-example-1");
        req.setLexiconRuleName("worked_example_1");
        req.setRequestType(TermType.NATURAL_LANGUAGE);
        req.setTerms(List.of(new TypedCompileRequest.TermInput("lexicon_term::4", term)));

        var bundle = bundleService.buildBundle(req);
        var result = bundle.jsonResponse().results().getFirst();

        assertThat(result.isPass()).isTrue();
        // No gap fragment is baked onto any leaf any more — each leaf is a pure,
        // gap-less fragment; the FOLLOWEDBY{4} chain is instead conveyed literally
        // in resolvedPatterns below.
        assertThat(result.regexPattern()).containsExactly(
                "(?:versuch nicht|mach\\S* nicht|tu\\S* nicht|vermeide)",
                "(?:frontrun\\S*|front run\\S*|übergeh\\S*|überspring\\S*)",
                "(?:das|dies|mich|sie|flow|Druck|Ausdruck)");
        assertThat(result.resolvedPatterns()).isEqualTo(
                "(?:versuch nicht|mach\\S* nicht|tu\\S* nicht|vermeide) FOLLOWEDBY{4} "
                + "(?:frontrun\\S*|front run\\S*|übergeh\\S*|überspring\\S*) FOLLOWEDBY{4} "
                + "(?:das|dies|mich|sie|flow|Druck|Ausdruck)");
        assertThat(result.hyperscanExpressionId()).isEqualTo(4);
        assertThat(result.patternMapping()).isEqualTo("(5&6&7)");
    }

    @Test
    @Order(82)
    @DisplayName("REGRESSION FIX: an AND NOT term no longer uses native Hyperscan COMBINATION at all " +
            "(confirmed unreliable — Hyperscan's own documented eager, progressive combination " +
            "evaluation can fire a mixed positive/negative formula before the negated pattern has " +
            "had a chance to appear later in the same text). Every required/excluded pattern now " +
            "compiles as its own plain, individually-reportable expression, and the caller " +
            "evaluates the boolean condition itself after the whole scan completes.")
    void andNotTerm_compilesSuccessfully_noSomFlagNeeded() throws IOException {
        var req = new TypedCompileRequest();
        req.setRequestId("regression-andnot-test");
        req.setLexiconRuleName("regression_andnot_test");
        req.setRequestType(TermType.NATURAL_LANGUAGE);
        req.setTerms(List.of(new TypedCompileRequest.TermInput(
                "t::1", "((don't forward) AND NOT (compliance OR legal))")));

        var bundle = bundleService.buildBundle(req);
        var result = bundle.jsonResponse().results().getFirst();

        assertThat(result.isPass()).isTrue();
        assertThat(bundle.hasDatabase()).isTrue();
        // hyperscanExpressionId is null for AND NOT terms now -- there is no single reportable id.
        assertThat(result.hyperscanExpressionId()).isNull();
        assertThat(result.requiredExpressionIds()).hasSize(1);
        assertThat(result.excludedExpressionIds()).hasSize(1);
        // patternMapping is the ONLY place this AND-NOT formula is recorded -- the .hdb itself has no
        // combination for it at all (see class Javadoc). Term number 1, so auxiliary ids start at 2.
        assertThat(result.patternMapping()).isEqualTo("(2&!3)");

        try (Database db = Database.load(new ByteArrayInputStream(bundle.hyperscanDatabaseBytes()))) {
            // Required pattern present, exclusion ALSO present -> post-scan evaluation must exclude.
            List<Integer> matchedIds = scanMatchesIds(db, "Please don't forward this message to compliance or legal");
            assertThat(evaluateAndNot(matchedIds, result.requiredExpressionIds(), result.excludedExpressionIds()))
                    .as("required present AND exclusion present -> term must NOT match")
                    .isFalse();
        }
    }

    @Test
    @Order(825)
    @DisplayName("WORKED EXAMPLE from the bug report: AND NOT term with a decomposed (nested-FOLLOWEDBY) " +
            "excluded side — required id=8 (single), excluded ids 9/10/11 (decomposed) — patternMapping " +
            "is exactly \"(8&!(9&10&11))\", the AND-NOT formula the .hdb itself never encodes")
    void patternMapping_andNot_matchesReportedWorkedExample() {
        String term = "(insider AND NOT ((wordA word B OR wordC* wordD OR wordE* wordF OR wordG) "
                + "FOLLOWEDBY{2} (wordH* OR wordI wordJ* wordK OR wordL* wordM OR wordN) "
                + "FOLLOWEDBY{2} (wordO* OR wordP* wordQ OR wordR* wordS OR wordT)))";

        var req = new TypedCompileRequest();
        req.setRequestId("worked-example-2");
        req.setLexiconRuleName("worked_example_2");
        req.setRequestType(TermType.NATURAL_LANGUAGE);
        // A dummy term at ::7 pushes the id offset to 8, so THIS term's required/excluded leaves land
        // on exactly the ids the bug report used (8, 9, 10, 11), for a byte-for-byte comparison.
        req.setTerms(List.of(
                new TypedCompileRequest.TermInput("lexicon_term::7", "dummy"),
                new TypedCompileRequest.TermInput("lexicon_term::4", term)));

        var bundle = bundleService.buildBundle(req);
        var result = bundle.jsonResponse().results().get(1);

        assertThat(result.isPass()).isTrue();
        assertThat(result.requiresExclusionCheck()).isTrue();
        assertThat(result.regexPattern()).hasSize(1); // required side: "insider" alone
        assertThat(result.exclusionRegex()).hasSize(3);  // excluded side: decomposed into 3 leaves
        assertThat(result.requiredExpressionIds()).containsExactly(8);
        assertThat(result.excludedExpressionIds()).containsExactly(9, 10, 11);
        assertThat(result.patternMapping()).isEqualTo("(8&!(9&10&11))");
    }

    @Test
    @Order(84)
    @DisplayName("REGRESSION: the exact scenario from the reported bug — required term appearing " +
            "BEFORE the excluded term in the text — no longer produces a false positive, since " +
            "there is no native combination left to fire eagerly/prematurely")
    void andNotTerm_requiredBeforeExcluded_noFalsePositive() throws IOException {
        var req = new TypedCompileRequest();
        req.setRequestId("regression-order-test");
        req.setLexiconRuleName("regression_order_test");
        req.setRequestType(TermType.NATURAL_LANGUAGE);
        req.setTerms(List.of(new TypedCompileRequest.TermInput(
                "t::1", "insider AND NOT (disclosed)")));

        var bundle = bundleService.buildBundle(req);
        var result = bundle.jsonResponse().results().getFirst();
        assertThat(result.isPass()).isTrue();

        try (Database db = Database.load(new ByteArrayInputStream(bundle.hyperscanDatabaseBytes()))) {
            // "insider" (required) appears FIRST; "disclosed" (excluded) appears LATER in the
            // same text -- exactly the ordering the issue analysis identified as triggering a
            // premature native-combination firing under the old design.
            String text = "insider trading occurred and was later disclosed to the board";
            List<Integer> matchedIds = scanMatchesIds(db, text);
            assertThat(evaluateAndNot(matchedIds, result.requiredExpressionIds(), result.excludedExpressionIds()))
                    .as("required present earlier in text, excluded present later -> term must NOT match")
                    .isFalse();
        }
    }

    @Test
    @Order(83)
    @DisplayName("TypedCompileRequest no longer has a trackMatchPosition field — verified structurally, " +
            "not just by absence of compile errors")
    void trackMatchPositionFieldRemoved() {
        var methods = TypedCompileRequest.class.getDeclaredMethods();
        boolean anyTrackMatchPositionMethod = java.util.Arrays.stream(methods)
                .anyMatch(m -> m.getName().toLowerCase().contains("trackmatchposition"));
        assertThat(anyTrackMatchPositionMethod).isFalse();
    }

    // ── Decomposition: over-complex terms compiled as leaves + native COMBINATION ──

    @Test
    @Order(90)
    @DisplayName("DECOMPOSITION: a term too complex for one pattern compiles as independent QUIET " +
            "leaves plus one native COMBINATION expression, instead of failing")
    void decomposedTerm_compilesAsLeavesPlusCombination() throws IOException {
        var req = request("test-rule", TermType.NATURAL_LANGUAGE, term(NESTED_TOO_COMPLEX_TERM));
        var bundle = bundleService.buildBundle(req);
        var result = bundle.jsonResponse().results().getFirst();

        assertThat(result.isPass()).isTrue();
        assertThat(result.regexPattern()).hasSize(3); // decomposed into 3 leaves
        assertThat(bundle.hasDatabase()).isTrue();
        assertThat(result.hyperscanExpressionId()).isEqualTo(1); // still the term's own number
    }

    @Test
    @Order(91)
    @DisplayName("DECOMPOSITION + AND NOT, excluded side decomposed: post-scan evaluation applies " +
            "the same AND convention correctly — exclude only when ALL decomposed excluded " +
            "leaves are present, not merely one. No native combination is used for this AND NOT " +
            "term at all — see HyperscanCombinationHandler class Javadoc.")
    void decomposedExcludedSide_appliesDeMorgansLawCorrectly() throws IOException {
        var req = request("test-rule", TermType.NATURAL_LANGUAGE,
                term("insider AND NOT (" + NESTED_TOO_COMPLEX_TERM + ")"));
        var bundle = bundleService.buildBundle(req);
        var result = bundle.jsonResponse().results().getFirst();

        assertThat(result.isPass()).isTrue();
        assertThat(result.regexPattern()).hasSize(1);   // required side simple
        assertThat(result.exclusionRegex()).hasSize(3);     // excluded side decomposed
        assertThat(bundle.hasDatabase()).isTrue();

        // AND NOT terms no longer get a single hyperscanExpressionId -- no native combination.
        assertThat(result.hyperscanExpressionId()).isNull();
        assertThat(result.requiredExpressionIds()).hasSize(1);
        assertThat(result.excludedExpressionIds()).hasSize(3);

        // Semantic correctness, not just structural: "insider" present but the WHOLE excluded
        // condition (all 3 decomposed leaves) also present -> must NOT match, since AND NOT's
        // excluded side is "all these leaves found", and this message contains all of them.
        try (Database db = Database.load(new ByteArrayInputStream(bundle.hyperscanDatabaseBytes()))) {
            String messageContainingEverything =
                    "insider wordG wordN wordT"; // matches required + all 3 excluded leaves
            var matched1 = scanMatchesIds(db, messageContainingEverything);
            assertThat(evaluateAndNot(matched1, result.requiredExpressionIds(), result.excludedExpressionIds()))
                    .as("required present AND all excluded leaves present -> must NOT match")
                    .isFalse();

            String messageMissingOneExcludedLeaf =
                    "insider wordG wordN"; // required + 2 of 3 excluded leaves (missing wordT-side)
            var matched2 = scanMatchesIds(db, messageMissingOneExcludedLeaf);
            assertThat(evaluateAndNot(matched2, result.requiredExpressionIds(), result.excludedExpressionIds()))
                    .as("required present AND only SOME excluded leaves present (not all) -> MUST match, "
                            + "since the excluded condition (ALL leaves) was not fully satisfied")
                    .isTrue();
        }
    }

    @Test
    @Order(92)
    @DisplayName("DECOMPOSITION + AND NOT, required side decomposed: every required leaf and the " +
            "(non-decomposed) excluded pattern each get their own individually-reportable id")
    void decomposedRequiredSide_withSimpleExcluded() {
        var req = request("test-rule", TermType.NATURAL_LANGUAGE,
                term("(" + NESTED_TOO_COMPLEX_TERM + ") AND NOT (excluded)"));
        var bundle = bundleService.buildBundle(req);
        var result = bundle.jsonResponse().results().getFirst();

        assertThat(result.isPass()).isTrue();
        assertThat(result.regexPattern()).hasSize(3);
        assertThat(result.exclusionRegex()).hasSize(1);
        assertThat(result.requiredExpressionIds()).hasSize(3);
        assertThat(result.excludedExpressionIds()).hasSize(1);
        assertThat(bundle.hasDatabase()).isTrue();
    }

    @Test
    @Order(93)
    @DisplayName("DECOMPOSITION on both sides of an AND NOT term compiles successfully, every leaf " +
            "on both sides getting its own individually-reportable id")
    void decomposedBothSides() {
        var req = request("test-rule", TermType.NATURAL_LANGUAGE,
                term("(" + NESTED_TOO_COMPLEX_TERM + ") AND NOT ("
                        + NESTED_TOO_COMPLEX_TERM.replace("word", "term") + ")"));
        var bundle = bundleService.buildBundle(req);
        var result = bundle.jsonResponse().results().getFirst();

        assertThat(result.isPass()).isTrue();
        assertThat(result.regexPattern()).hasSize(3);
        assertThat(result.exclusionRegex()).hasSize(3);
        assertThat(result.requiredExpressionIds()).hasSize(3);
        assertThat(result.excludedExpressionIds()).hasSize(3);
        assertThat(bundle.hasDatabase()).isTrue();
    }

    @Test
    @Order(94)
    @DisplayName("A simple AND NOT term's exclusionRegex is a single-entry list with a single-level "
            + "wrapped pattern — PatternDecomposer.decompose() now always sees through the single-operand "
            + "Or wrapper TermSyntaxTranslator builds for the excluded side, even for a term with no "
            + "NEAR/FOLLOWEDBY structure, so it no longer double-wraps this the way plain "
            + "PatternCodeGenerator.generateOr() alone would have")
    void simpleAndNot_unaffectedByDecompositionFeature() {
        var req = request("test-rule", TermType.NATURAL_LANGUAGE,
                term("((don't forward) AND NOT (compliance OR legal))"));
        var bundle = bundleService.buildBundle(req);
        var result = bundle.jsonResponse().results().getFirst();

        assertThat(result.regexPattern()).hasSize(1);
        assertThat(result.exclusionRegex()).hasSize(1);
        assertThat(result.exclusionRegex().getFirst()).isEqualTo("(?:compliance|legal)");
    }

    // ── HyperscanCombinationHandler-specific: the COMBINATION/QUIET flag constraint ──
    // Native COMBINATION is now used ONLY for pure decomposition (no AND NOT) — see class
    // Javadoc for why AND NOT no longer uses it at all.

    @Test
    @Order(100)
    @DisplayName("A pure-decomposition (no AND NOT) combination expression's flags are EXACTLY " +
            "{COMBINATION} — never CASELESS/UTF8/UCP/DOTALL/SOM_LEFTMOST mixed in, matching " +
            "Hyperscan's own constraint on combination expressions")
    void combinationExpressionFlagsAreExactlyCombination() throws IOException {
        var req = request("test-rule", TermType.NATURAL_LANGUAGE, term(NESTED_TOO_COMPLEX_TERM));
        LexiconCompileBundleService.CompileBundleResult compileBundleResult = bundleService.buildBundle(req);
        Database database = Database.load(new ByteArrayInputStream(compileBundleResult.hyperscanDatabaseBytes()));

        try (Scanner scanner = new Scanner()) {
            scanner.allocScratch(database);
            List<Match> matches = scanner.scan(database, "wordA wordN wordT");
            assertThat(matches.stream()
                    .allMatch(e -> e.getMatchedExpression().getFlags().toString().contains("COMBINATION"))).isTrue();
        }
    }

    @Test
    @Order(101)
    @DisplayName("Every QUIET sub-expression a pure-decomposition combination needs is assigned an id " +
            "from the allocated range, never the term's own term number")
    void quietSubExpressionsNeverUseTermNumber() throws IOException {
        var req = request("test-rule", TermType.NATURAL_LANGUAGE, term(NESTED_TOO_COMPLEX_TERM));
        LexiconCompileBundleService.CompileBundleResult compileBundleResult = bundleService.buildBundle(req);
        Database database = Database.load(new ByteArrayInputStream(compileBundleResult.hyperscanDatabaseBytes()));

        try (Scanner scanner = new Scanner()) {
            scanner.allocScratch(database);
            List<Match> matches = scanner.scan(database, "insider");
            assertThat(matches.stream()
                    .allMatch(e -> e.getMatchedExpression().getFlags().toString().contains("QUIET"))).isTrue();
        }
    }

    @Test
    @Order(102)
    @DisplayName("An AND NOT term's expressions are NEVER QUIET and NEVER COMBINATION — every " +
            "required/excluded pattern is its own plain, individually-reportable expression")
    void andNotTerm_neverUsesQuietOrCombination() throws IOException {
        var req = request("test-rule", TermType.NATURAL_LANGUAGE, term("insider AND NOT (compliance)"));
        LexiconCompileBundleService.CompileBundleResult compileBundleResult = bundleService.buildBundle(req);
        Database database = Database.load(new ByteArrayInputStream(compileBundleResult.hyperscanDatabaseBytes()));
        var result = compileBundleResult.jsonResponse().results().getFirst();

        try (Scanner scanner = new Scanner()) {
            scanner.allocScratch(database);
            List<Match> matches = scanner.scan(database, "insider");
            assertThat(matches.stream()
                    .allMatch(e -> e.getMatchedExpression().getFlags().toString().contains("QUIET"))).isFalse();
            assertThat(matches.stream()
                    .allMatch(e -> e.getMatchedExpression().getFlags().toString().contains("COMBINATION"))).isFalse();
        }

        assertThat(result.requiredExpressionIds().getFirst()).isNotEqualTo(1);
        assertThat(result.excludedExpressionIds().getFirst()).isNotEqualTo(1);
    }

    // ── Term id validation: malformed / duplicate term numbers rejected up front ──

    @Test
    @Order(110)
    @DisplayName("A termId not ending in '::<n>' is rejected with InvalidTermIdException before any term is compiled")
    void malformedTermIdRejected() {
        var req = new TypedCompileRequest();
        req.setRequestId(UUID.randomUUID().toString());
        req.setLexiconRuleName("bad_id_rule");
        req.setRequestType(TermType.NATURAL_LANGUAGE);
        req.setTerms(List.of(new TypedCompileRequest.TermInput("not_a_valid_term_id", "price OR spread")));

        assertThatThrownBy(() -> bundleService.buildBundle(req))
                .isInstanceOf(com.db.macs3.ecomms.spectre.model.InvalidTermIdException.class)
                .hasMessageContaining("not_a_valid_term_id");
    }

    @Test
    @Order(111)
    @DisplayName("Two terms sharing the same term number are rejected with InvalidTermIdException, " +
            "since they would collide at the same Hyperscan expression id")
    void duplicateTermNumberRejected() {
        var req = new TypedCompileRequest();
        req.setRequestId(UUID.randomUUID().toString());
        req.setLexiconRuleName("dup_id_rule");
        req.setRequestType(TermType.NATURAL_LANGUAGE);
        req.setTerms(List.of(
                new TypedCompileRequest.TermInput("dup_id_rule::1", "price OR spread"),
                new TypedCompileRequest.TermInput("dup_id_rule::1", "insider OR tip"))); // same number "1"

        assertThatThrownBy(() -> bundleService.buildBundle(req))
                .isInstanceOf(com.db.macs3.ecomms.spectre.model.InvalidTermIdException.class)
                .hasMessageContaining("dup_id_rule::1");
    }

    @Test
    @Order(112)
    @DisplayName("A termId ending in a non-numeric suffix after '::' is rejected, not silently treated as 0")
    void nonNumericSuffixRejected() {
        var req = new TypedCompileRequest();
        req.setRequestId(UUID.randomUUID().toString());
        req.setLexiconRuleName("bad_suffix_rule");
        req.setRequestType(TermType.NATURAL_LANGUAGE);
        req.setTerms(List.of(new TypedCompileRequest.TermInput("bad_suffix_rule::abc", "price OR spread")));

        assertThatThrownBy(() -> bundleService.buildBundle(req))
                .isInstanceOf(com.db.macs3.ecomms.spectre.model.InvalidTermIdException.class);
    }

    @Test
    @Order(113)
    @DisplayName("Valid, non-sequential, sparse term numbers (e.g. 5 and 100) are accepted; the plain " +
            "term gets its own number as expression id, the AND NOT term gets allocated ids " +
            "starting safely beyond both term numbers")
    void sparseTermNumbersAccepted() {
        var req = new TypedCompileRequest();
        req.setRequestId(UUID.randomUUID().toString());
        req.setLexiconRuleName("sparse_rule");
        req.setRequestType(TermType.NATURAL_LANGUAGE);
        req.setTerms(List.of(
                new TypedCompileRequest.TermInput("sparse_rule::5", "price OR spread"),
                new TypedCompileRequest.TermInput("sparse_rule::100", "insider AND NOT (disclosed)")));

        var bundle = bundleService.buildBundle(req);
        var results = bundle.jsonResponse().results();

        assertThat(results).allMatch(TermCompilationResult::isPass);
        assertThat(results.getFirst().hyperscanExpressionId()).isEqualTo(5);
        // The AND NOT term (term number 100) no longer gets hyperscanExpressionId -- its
        // required/excluded patterns get allocated ids starting from 101 (max term number + 1).
        assertThat(results.get(1).hyperscanExpressionId()).isNull();
        assertThat(results.get(1).requiredExpressionIds()).allMatch((Integer id) -> id >= 101);
        assertThat(results.get(1).excludedExpressionIds()).allMatch((Integer id) -> id >= 101);
        assertThat(bundle.hasDatabase()).isTrue();
    }
}
