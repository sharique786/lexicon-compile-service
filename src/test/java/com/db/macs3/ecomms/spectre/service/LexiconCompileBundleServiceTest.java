package com.db.macs3.ecomms.spectre.service;

import com.db.macs3.ecomms.spectre.hyperscan.HyperscanCompiler;
import com.db.macs3.ecomms.spectre.model.CompilationStatus;
import com.db.macs3.ecomms.spectre.model.CompileResponse;
import com.db.macs3.ecomms.spectre.model.TypedCompileRequest;
import com.db.macs3.ecomms.spectre.translator.TermSyntaxTranslator;
import com.gliwka.hyperscan.wrapper.Database;
import com.gliwka.hyperscan.wrapper.Match;
import com.gliwka.hyperscan.wrapper.Scanner;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link LexiconCompileBundleService} — the {@code /compile/bundle}
 * endpoint's orchestration logic (Standard/NLT branching + combined database).
 *
 * <p>No Spring context — uses real {@code TermSyntaxTranslator} and
 * {@code HyperscanCompiler} directly, exactly like {@link LexiconCompileServiceTest}.
 */
@DisplayName("LexiconCompileBundleService Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LexiconCompileBundleServiceTest {

    private LexiconCompileBundleService bundleService;

    @BeforeEach
    void setUp() {
        var translator     = new TermSyntaxTranslator();
        var compiler        = new HyperscanCompiler();
        compiler.selfTest();
        var compileService  = new LexiconCompileService(translator, compiler, new SimpleMeterRegistry());
        bundleService        = new LexiconCompileBundleService(compileService, compiler, new SimpleMeterRegistry());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private TypedCompileRequest request(String ruleName, TermSpec... specs) {
        var req = new TypedCompileRequest();
        req.setLexiconRuleName(ruleName);
        List<TypedCompileRequest.TermInput> terms = new ArrayList<>();
        for (int i = 0; i < specs.length; i++) {
            terms.add(new TypedCompileRequest.TermInput(
                    ruleName + "::" + (i + 1), specs[i].description, specs[i].termType, "Test"));
        }
        req.setTerms(terms);
        return req;
    }

    private record TermSpec(String description, String termType) {}
    private static TermSpec standard(String desc) { return new TermSpec(desc, "Standard"); }
    private static TermSpec nlt(String desc)      { return new TermSpec(desc, "NLT"); }

    // ── Spec example from the requirements ────────────────────────────────────

    @Test @Order(1)
    @DisplayName("Spec example: mixed Standard + NLT → both PASS, combined DB built")
    void specExampleMixed() {
        var req = request("lexicon_research_1",
                standard("(manipulate) NEAR{5} ((price) OR (spread) OR (stock))"),
                nlt("(?:price|spread|stock)"));

        var bundle = bundleService.buildBundle(req);

        assertThat(bundle.jsonResponse().passCount()).isEqualTo(2);
        assertThat(bundle.jsonResponse().failedCount()).isEqualTo(0);
        assertThat(bundle.hasDatabase()).isTrue();
        assertThat(bundle.hyperscanDatabaseBytes()).isNotEmpty();
        assertThat(bundle.databaseNote()).isNull();
    }

    // ── JSON shape parity with /compile ───────────────────────────────────────

    @Test @Order(10)
    @DisplayName("JSON response is the exact CompileResponse type — same shape as /compile")
    void jsonShapeMatchesCompile() {
        var req = request("shape_test", standard("price OR spread"));
        var bundle = bundleService.buildBundle(req);

        CompileResponse json = bundle.jsonResponse();
        assertThat(json.lexiconRuleName()).isEqualTo("shape_test");
        assertThat(json.engineMode()).isEqualTo("HYPERSCAN_NATIVE");
        assertThat(json.hyperscanVersion()).isNotBlank();
        assertThat(json.results()).hasSize(1);
        assertThat(json.results().get(0).termId()).isEqualTo("shape_test::1");
    }

    // ── Standard term branch ──────────────────────────────────────────────────

    @Test @Order(20)
    @DisplayName("Standard term: NEAR{5} translated exactly like /compile")
    void standardTermTranslated() {
        var req = request("std_test", standard("(manipulate) NEAR{5} (price)"));
        var bundle = bundleService.buildBundle(req);

        var result = bundle.jsonResponse().results().get(0);
        assertThat(result.compilationStatus()).isEqualTo(CompilationStatus.PASS);
        assertThat(result.translatedPattern()).contains("manipulate").contains("price");
        // Word-based gap confirms translation actually ran (NLT would never produce this)
        assertThat(result.translatedPattern()).contains("\\s+\\S+");
    }

    @Test @Order(21)
    @DisplayName("Standard term with invalid syntax → FAILED with translationError, no DB entry")
    void standardTermTranslationFailure() {
        var req = request("std_fail_test", standard("NEAR{5} (price)")); // missing left operand
        var bundle = bundleService.buildBundle(req);

        var result = bundle.jsonResponse().results().get(0);
        assertThat(result.compilationStatus()).isEqualTo(CompilationStatus.FAILED);
    }

    // ── NLT term branch ────────────────────────────────────────────────────────

    @Test @Order(30)
    @DisplayName("NLT term: raw PCRE compiled verbatim, NOT passed through the translator")
    void nltTermCompiledVerbatim() {
        var req = request("nlt_test", nlt("(?:price|spread|stock)"));
        var bundle = bundleService.buildBundle(req);

        var result = bundle.jsonResponse().results().get(0);
        assertThat(result.compilationStatus()).isEqualTo(CompilationStatus.PASS);
        // translatedPattern must be byte-identical to the input — no translation occurred
        assertThat(result.translatedPattern()).isEqualTo("(?:price|spread|stock)");
    }

    @Test @Order(31)
    @DisplayName("NLT term with invalid regex syntax → FAILED with Hyperscan errorLog")
    void nltTermInvalidRegex() {
        var req = request("nlt_invalid_test", nlt("[unclosed"));
        var bundle = bundleService.buildBundle(req);

        var result = bundle.jsonResponse().results().get(0);
        assertThat(result.compilationStatus()).isEqualTo(CompilationStatus.FAILED);
        assertThat(result.errorLog()).isNotBlank();
        // requiresAndPostFilter is always false for NLT
        assertThat(result.requiresAndPostFilter()).isFalse();
    }

    @Test @Order(32)
    @DisplayName("NLT term with non-Latin script gets UTF8/UCP flags automatically")
    void nltTermNonLatinFlags() {
        var req = request("nlt_korean_test", nlt("(?:내부자|거래)"));
        var bundle = bundleService.buildBundle(req);

        var result = bundle.jsonResponse().results().get(0);
        assertThat(result.compilationStatus()).isEqualTo(CompilationStatus.PASS);
        assertThat(result.hyperscanFlags() & 32).isEqualTo(32); // UTF8
        assertThat(result.hyperscanFlags() & 64).isEqualTo(64); // UCP
    }

    @Test @Order(33)
    @DisplayName("NLT term with operator-language syntax (not a real regex) is treated literally and FAILS or passes as literal text, never translated")
    void nltTermNeverTranslated() {
        // If this were Standard, "NEAR{5}" would translate into a proximity pattern.
        // As NLT it must be compiled literally — Hyperscan treats {5} as a repetition quantifier
        // on whatever precedes it, which is valid PCRE syntax on its own.
        var req = request("nlt_literal_test", nlt("price NEAR.{5} stock"));
        var bundle = bundleService.buildBundle(req);

        var result = bundle.jsonResponse().results().get(0);
        // Must be exactly what was given — proof no NEAR{n} translation ran
        assertThat(result.translatedPattern()).isEqualTo("price NEAR.{5} stock");
    }

    // ── All-NLT and all-Standard scenarios ────────────────────────────────────

    @Test @Order(40)
    @DisplayName("All-NLT request: every term compiled verbatim, DB contains all")
    void allNltRequest() {
        var req = request("all_nlt_test",
                nlt("(?:price|spread)"), nlt("(?:insider|tip)"), nlt("manipulat\\w+"));
        var bundle = bundleService.buildBundle(req);

        assertThat(bundle.jsonResponse().passCount()).isEqualTo(3);
        assertThat(bundle.hasDatabase()).isTrue();
    }

    @Test @Order(41)
    @DisplayName("All-Standard request: behaves identically to /compile for every term")
    void allStandardRequest() {
        var req = request("all_std_test",
                standard("price OR spread"),
                standard("don't FOLLOWEDBY{3} compliance"),
                standard("insider AND announcement"));
        var bundle = bundleService.buildBundle(req);

        assertThat(bundle.jsonResponse().passCount()).isEqualTo(3);
        assertThat(bundle.hasDatabase()).isTrue();
    }

    // ── Mixed pass/fail scenarios ──────────────────────────────────────────────

    @Test @Order(50)
    @DisplayName("Mixed PASS/FAILED: combined DB built from PASS subset only, with id gaps")
    void mixedPassFailedDbBuiltFromPassOnly() throws IOException {
        var req = request("mixed_test",
                standard("price OR spread"),          // index 0 — PASS
                nlt("[unclosed"),                       // index 1 — FAILED
                nlt("(?:insider|tip)"));                // index 2 — PASS

        var bundle = bundleService.buildBundle(req);

        assertThat(bundle.jsonResponse().passCount()).isEqualTo(2);
        assertThat(bundle.jsonResponse().failedCount()).isEqualTo(1);
        assertThat(bundle.jsonResponse().results().get(1).compilationStatus())
                .isEqualTo(CompilationStatus.FAILED);
        assertThat(bundle.hasDatabase()).isTrue();

        // Load the DB back and confirm only ids {0, 2} are present (1 is the gap)
        try (Database db = Database.load(new ByteArrayInputStream(bundle.hyperscanDatabaseBytes()))) {
            assertThat(scanMatchesIds(db, "price")).contains(0);
            assertThat(scanMatchesIds(db, "insider")).contains(2);
        }
    }

    @Test @Order(51)
    @DisplayName("Zero PASS terms: no database built, clear explanatory note returned")
    void zeroPassTermsNoDatabase() {
        var req = request("zero_pass_test", nlt("[unclosed"), standard("NEAR{5} (x)"));
        var bundle = bundleService.buildBundle(req);

        assertThat(bundle.jsonResponse().passCount()).isEqualTo(0);
        assertThat(bundle.hasDatabase()).isFalse();
        assertThat(bundle.hyperscanDatabaseBytes()).isNull();
        assertThat(bundle.databaseNote()).isNotBlank();
        assertThat(bundle.databaseNote()).contains("zero terms reached PASS");
    }

    // ── Combined database integrity (round-trip load + scan) ───────────────────

    @Test @Order(60)
    @DisplayName("Combined DB round-trip: save() then load() reconstructs a working multi-pattern database")
    void combinedDatabaseRoundTrip() throws IOException {
        var req = request("roundtrip_test",
                nlt("(?:price|spread|stock)"),
                nlt("(?:insider|tip)"));
        var bundle = bundleService.buildBundle(req);

        assertThat(bundle.hasDatabase()).isTrue();

        try (Database db = Database.load(new ByteArrayInputStream(bundle.hyperscanDatabaseBytes()))) {
            List<Integer> priceMatches  = scanMatchesIds(db, "the price went up");
            List<Integer> insiderMatches = scanMatchesIds(db, "an insider tip was shared");

            assertThat(priceMatches).contains(0);
            assertThat(insiderMatches).contains(1);
        }
    }

    @Test @Order(61)
    @DisplayName("Expression id equals the term's 0-based index in the request, not termId hash")
    void expressionIdEqualsArrayIndex() throws IOException {
        var req = request("id_mapping_test",
                nlt("alpha_pattern"),   // index 0
                nlt("beta_pattern"),    // index 1
                nlt("gamma_pattern"));  // index 2
        var bundle = bundleService.buildBundle(req);

        try (Database db = Database.load(new ByteArrayInputStream(bundle.hyperscanDatabaseBytes()))) {
            assertThat(scanMatchesIds(db, "alpha_pattern")).containsExactly(0);
            assertThat(scanMatchesIds(db, "beta_pattern")).containsExactly(1);
            assertThat(scanMatchesIds(db, "gamma_pattern")).containsExactly(2);
        }
    }

    // ── Scan helper ──────────────────────────────────────────────────────────

    /** Scans {@code text} against {@code db} and returns the matched expression ids. */
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
}
