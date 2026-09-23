package com.db.macs3.ecomms.spectre.integration;

import com.db.macs3.ecomms.spectre.hyperscan.HyperscanCompiler;
import com.db.macs3.ecomms.spectre.model.CompilationStatus;
import com.db.macs3.ecomms.spectre.model.CompileResponse;
import com.db.macs3.ecomms.spectre.model.TermType;
import com.db.macs3.ecomms.spectre.model.TypedCompileRequest;
import com.db.macs3.ecomms.spectre.service.CsvCompileService;
import com.db.macs3.ecomms.spectre.service.LexiconCompileBundleService;
import com.db.macs3.ecomms.spectre.service.LexiconCompileService;
import com.db.macs3.ecomms.spectre.translator.TermSyntaxTranslator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test: reads real CSV and JSON lexicon term files
 * from the test classpath and processes EVERY term in them through the
 * actual service layer — the same {@link CsvCompileService} and
 * {@link LexiconCompileBundleService} the REST controllers delegate to —
 * rather than exercising the translator in isolation as the other test
 * classes do.
 *
 * <p>No Spring context is started; services are wired directly with real
 * {@link TermSyntaxTranslator} and {@link HyperscanCompiler} instances,
 * exactly like the other service-level tests in this project. This still
 * exercises the full, real pipeline (parse CSV/JSON → translate →
 * Hyperscan-compile → aggregate results) — the only thing not exercised is
 * the HTTP layer itself, which is covered separately by the controller
 * slice tests.
 *
 * <p><b>Fixture files</b>
 * <ul>
 *   <li>{@code fixtures/lexicon-terms.csv} — 14 terms covering every
 *       requirement in this project's bug-fix history (bracket nesting,
 *       unwrapped phrases, literal {@code ?}, wildcards, AND/AND-NOT,
 *       multi-language content, a quoted phrase) PLUS two deliberately
 *       invalid terms, to prove the pipeline reports partial failures
 *       correctly rather than aborting the whole batch.</li>
 *   <li>{@code fixtures/lexicon-terms-natural-language.json} — a
 *       {@code /compile/bundle}-shaped request with
 *       {@code requestType: "Natural Language"}.</li>
 *   <li>{@code fixtures/lexicon-terms-regex.json} — the same shape with
 *       {@code requestType: "Regex"}, including one deliberately-invalid
 *       pattern.</li>
 * </ul>
 */
@DisplayName("Lexicon Term Fixture Integration Test (CSV + JSON)")
class LexiconTermFixtureIntegrationTest {

    private CsvCompileService csvCompileService;
    private LexiconCompileBundleService bundleService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        HyperscanCompiler compiler = new HyperscanCompiler();
        TermSyntaxTranslator translator = new TermSyntaxTranslator(compiler);
        compiler.selfTest();

        LexiconCompileService compileService =
                new LexiconCompileService(translator, compiler, new SimpleMeterRegistry());

        this.csvCompileService = new CsvCompileService(compileService);
        var combinationHandler = new com.db.macs3.ecomms.spectre.hyperscan.HyperscanCombinationHandler(compiler);
        this.bundleService = new LexiconCompileBundleService(compileService, compiler, combinationHandler, new SimpleMeterRegistry());
        this.objectMapper = new ObjectMapper();
    }

    // ── CSV fixture ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("CSV fixture: every term is processed, pipeline never throws, pass/fail counts are consistent")
    void csvFixture_allTermsProcessed_noCrash() throws IOException {
        CompileResponse response;
        try (InputStream csvStream = openFixture("lexicon-terms.csv")) {
            response = csvCompileService.compileFromCsv(csvStream, "lexicon_it_test", UUID.randomUUID().toString());
        }

        assertThat(response.results()).hasSize(14);
        assertThat(response.totalTerms()).isEqualTo(14);
        assertThat(response.passCount() + response.failedCount()).isEqualTo(14);

        // The two deliberately-invalid rows (meaningless content, unmatched bracket)
        // must fail; every other row must pass.
        assertThat(response.passCount()).isEqualTo(12);
        assertThat(response.failedCount()).isEqualTo(2);
        assertThat(response.hasFailures()).isTrue();
    }

    @Test
    @DisplayName("CSV fixture: deeply-nested and unwrapped-phrase terms (the actual bug-report cases) all PASS")
    void csvFixture_bugReportTermsPass() throws IOException {
        CompileResponse response;
        try (InputStream csvStream = openFixture("lexicon-terms.csv")) {
            response = csvCompileService.compileFromCsv(csvStream, "lexicon_it_test", UUID.randomUUID().toString());
        }

        assertPass(response, "lexicon_it_test::1"); // (crap OR bad) NEAR{3} (bonus OR comp)
        assertPass(response, "lexicon_it_test::2"); // (F) FOLLOWEDBY{1} (((me) OR (cking)))
        assertPass(response, "lexicon_it_test::3"); // unwrapped multi-word phrase, now valid
        assertPass(response, "lexicon_it_test::4"); // literal '?'
        assertPass(response, "lexicon_it_test::5"); // wildcard
        assertPass(response, "lexicon_it_test::6"); // AND
        assertPass(response, "lexicon_it_test::7"); // AND NOT with FOLLOWEDBY on the positive side
        assertPass(response, "lexicon_it_test::8"); // prefix wildcard on non-ASCII word
        assertPass(response, "lexicon_it_test::9"); // the original crash case (large NEAR)
        assertPass(response, "lexicon_it_test::10"); // AND NOT with chained FOLLOWEDBY, no space before '('
        assertPass(response, "lexicon_it_test::11"); // Korean NEAR
        assertPass(response, "lexicon_it_test::12"); // quoted phrase with escaped internal quotes
    }

    @Test
    @DisplayName("CSV fixture: deliberately-invalid terms FAIL with a specific, actionable message")
    void csvFixture_invalidTermsFailWithClearMessages() throws IOException {
        CompileResponse response;
        try (InputStream csvStream = openFixture("lexicon-terms.csv")) {
            response = csvCompileService.compileFromCsv(csvStream, "lexicon_it_test", UUID.randomUUID().toString());
        }

        var meaninglessContent = findResult(response, "lexicon_it_test::13");
        assertThat(meaninglessContent.compilationStatus()).isEqualTo(CompilationStatus.FAILED);
        assertThat(meaninglessContent.translationError()).contains("meaningful content");

        var unmatchedBracket = findResult(response, "lexicon_it_test::14");
        assertThat(unmatchedBracket.compilationStatus()).isEqualTo(CompilationStatus.FAILED);
        assertThat(unmatchedBracket.translationError()).containsIgnoringCase("parenthesis");
    }

    // ── JSON fixture: Natural Language ──────────────────────────────────────────

    @Test
    @DisplayName("Natural Language JSON fixture: every term processed, correct requestType echoed, partial failure handled")
    void jsonFixtureNaturalLanguage_allTermsProcessed() throws IOException {
        TypedCompileRequest request = readJsonFixture("lexicon-terms-natural-language.json");
        assertThat(request.getRequestType()).isEqualTo(TermType.NATURAL_LANGUAGE);

        var bundle = bundleService.buildBundle(request);
        CompileResponse response = bundle.jsonResponse();

        assertThat(response.requestType()).isEqualTo("Natural Language");
        assertThat(response.results()).hasSize(7);
        // 6 valid terms + 1 deliberately meaningless ("#@$#%$")
        assertThat(response.passCount()).isEqualTo(6);
        assertThat(response.failedCount()).isEqualTo(1);
        // No combined database at all — one term (nl::7) FAILED, so no .hdb is built even
        // though the other 6 terms passed (see LexiconCompileBundleService#buildDatabasePortion).
        assertThat(bundle.hasDatabase()).isFalse();
        assertThat(bundle.databaseNote()).contains("did not reach PASS status");

        assertPass(response, "nl::1");
        assertPass(response, "nl::2"); // unwrapped multi-word phrase
        assertPass(response, "nl::3"); // AND
        assertPass(response, "nl::4"); // AND NOT
        assertPass(response, "nl::5"); // wildcard
        assertPass(response, "nl::6"); // Korean NEAR
        var meaningless = findResult(response, "nl::7");
        assertThat(meaningless.compilationStatus()).isEqualTo(CompilationStatus.FAILED);
    }

    // ── JSON fixture: Regex ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Regex JSON fixture: patterns compiled verbatim (no translation), correct requestType echoed")
    void jsonFixtureRegex_allTermsProcessed() throws IOException {
        TypedCompileRequest request = readJsonFixture("lexicon-terms-regex.json");
        assertThat(request.getRequestType()).isEqualTo(TermType.REGEX);
        assertThat(request.isRegexType()).isTrue();

        var bundle = bundleService.buildBundle(request);
        CompileResponse response = bundle.jsonResponse();

        assertThat(response.requestType()).isEqualTo("Regex");
        assertThat(response.results()).hasSize(4);
        // 3 valid regex patterns + 1 deliberately unclosed group
        assertThat(response.passCount()).isEqualTo(3);
        assertThat(response.failedCount()).isEqualTo(1);
        // One term FAILED (regex::4) — no combined database is built at all, even though 3
        // other terms passed.
        assertThat(bundle.hasDatabase()).isFalse();

        // Verbatim compilation — no operator-language translation should have occurred.
        var literalRegex = findResult(response, "regex::1");
        assertThat(literalRegex.regexPattern()).hasSize(1);
        assertThat(literalRegex.regexPattern().getFirst()).isEqualTo("(?:insider|trading)");

        var invalidRegex = findResult(response, "regex::4");
        assertThat(invalidRegex.compilationStatus()).isEqualTo(CompilationStatus.FAILED);
        assertThat(invalidRegex.errorLog()).isNotBlank();
    }

    // ── Fixture loading helpers ──────────────────────────────────────────────────

    private InputStream openFixture(String fileName) throws IOException {
        InputStream stream = getClass().getClassLoader().getResourceAsStream("fixtures/" + fileName);
        if (stream == null) {
            throw new IOException("Test fixture not found on classpath: fixtures/" + fileName);
        }
        return stream;
    }

    private TypedCompileRequest readJsonFixture(String fileName) throws IOException {
        try (InputStream stream = openFixture(fileName)) {
            return objectMapper.readValue(stream, TypedCompileRequest.class);
        }
    }

    private com.db.macs3.ecomms.spectre.model.TermCompilationResult findResult(CompileResponse response, String termId) {
        return response.results().stream()
                .filter(r -> r.termId().equals(termId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No result found for termId: " + termId));
    }

    private void assertPass(CompileResponse response, String termId) {
        var result = findResult(response, termId);
        assertThat(result.compilationStatus())
                .as("termId=%s should PASS but was %s (error: %s)",
                        termId, result.compilationStatus(),
                        result.translationError() != null ? result.translationError() : result.errorLog())
                .isEqualTo(CompilationStatus.PASS);
    }
}
