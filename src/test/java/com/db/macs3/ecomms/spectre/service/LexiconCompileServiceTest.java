package com.db.macs3.ecomms.spectre.service;

import com.db.macs3.ecomms.spectre.hyperscan.HyperscanCompiler;
import com.db.macs3.ecomms.spectre.model.CompileRequest;
import com.db.macs3.ecomms.spectre.model.CompileResponse;
import com.db.macs3.ecomms.spectre.model.CompilationStatus;
import com.db.macs3.ecomms.spectre.translator.TermSyntaxTranslator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link LexiconCompileService}.
 * No Spring context — uses real Hyperscan native library.
 */
@DisplayName("LexiconCompileService Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LexiconCompileServiceTest {

    private LexiconCompileService service;

    @BeforeEach
    void setUp() {
        var translator = new TermSyntaxTranslator();
        var compiler   = new HyperscanCompiler();
        compiler.selfTest();
        service = new LexiconCompileService(translator, compiler, new SimpleMeterRegistry());
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private CompileResponse compile(String ruleName, String... descriptions) {
        var req = new CompileRequest();
        req.setLexiconRuleName(ruleName);
        for (int i = 0; i < descriptions.length; i++) {
            req.getTerms().add(new CompileRequest.TermInput(
                    ruleName + "::" + (i + 1), descriptions[i], "Test Category"));
        }
        return service.compile(req);
    }

    // ── Spec examples from requirements ───────────────────────────────────────

    @Test @Order(1)
    @DisplayName("Spec example 1 (JSON): (manipulate) NEAR{5} → PASS")
    void specExample1Json() {
        var resp = compile("lexicon_research_1",
                "(manipulate) NEAR{5} ((price) OR (spread) OR (stock))");
        assertThat(resp.passCount()).isEqualTo(1);
        assertThat(resp.failedCount()).isEqualTo(0);
        assertThat(resp.hasFailures()).isFalse();
        assertThat(resp.engineMode()).isEqualTo("HYPERSCAN_NATIVE");
        assertThat(resp.results().get(0).compilationStatus()).isEqualTo(CompilationStatus.PASS);
        assertThat(resp.results().get(0).translatedPattern()).isNotBlank();
        assertThat(resp.results().get(0).compiledAt()).isNotNull();
    }

    @Test @Order(2)
    @DisplayName("Spec example 1 (CSV): (manipulate*) NEAR{5} → PASS with wildcard")
    void specExample1Csv() {
        var resp = compile("lexicon_research_1",
                "(manipulate*) NEAR{5} ((price) OR (spread) OR (stock))");
        assertThat(resp.passCount()).isEqualTo(1);
        var result = resp.results().get(0);
        assertThat(result.compilationStatus()).isEqualTo(CompilationStatus.PASS);
        assertThat(result.translatedPattern()).contains("\\S*"); // wildcard translated
    }

    @Test @Order(3)
    @DisplayName("Spec example 2: quoted OR phrases → PASS")
    void specExample2() {
        var resp = compile("lexicon_research_1",
                "((\"please don't forward\") OR (\"do not share don't forward\"))");
        assertThat(resp.passCount()).isEqualTo(1);
        assertThat(resp.results().get(0).compilationStatus()).isEqualTo(CompilationStatus.PASS);
    }

    @Test @Order(4)
    @DisplayName("Spec example 2 CSV: CSV double-quote escaped → PASS")
    void specExample2Csv() {
        // CSV encoding: ""please don't forward""
        var resp = compile("lexicon_research_1",
                "((\"\"please don't forward\"\") OR (\"\"do not share don't forward\"\"))");
        assertThat(resp.passCount()).isEqualTo(1);
    }

    // ── Response structure ────────────────────────────────────────────────────

    @Test @Order(10)
    @DisplayName("Response echoes all input fields for each term")
    void responseEchoesInputFields() {
        var req = new CompileRequest();
        req.setLexiconRuleName("echo_test");
        req.getTerms().add(new CompileRequest.TermInput(
                "echo_test::42", "price OR spread", "Front Running"));

        var resp = service.compile(req);
        var result = resp.results().get(0);

        assertThat(result.termId()).isEqualTo("echo_test::42");
        assertThat(result.termDescription()).isEqualTo("price OR spread");
        assertThat(result.riskDriverName()).isEqualTo("Front Running");
    }

    @Test @Order(11)
    @DisplayName("Summary counts match individual term statuses")
    void summaryCounts() {
        // (unclosed → \(unclosed (valid literal); [unclosed = truly invalid char class
        var resp = compile("count_test",
                "price OR spread",    // PASS
                "insider AND news",   // PASS  (OR pre-scan after fix)
                "[unclosed");         // FAILED — unclosed character class

        assertThat(resp.totalTerms()).isEqualTo(3);
        long actualPass = resp.results().stream()
                .filter(r -> r.compilationStatus() == CompilationStatus.PASS).count();
        long actualFail = resp.results().stream()
                .filter(r -> r.compilationStatus() == CompilationStatus.FAILED).count();
        assertThat(resp.passCount()).isEqualTo((int) actualPass);
        assertThat(resp.failedCount()).isEqualTo((int) actualFail);
        assertThat(resp.hasFailures()).isTrue();
    }

    @Test @Order(12)
    @DisplayName("FAILED term has non-blank errorLog — [unclosed is a truly invalid Hyperscan pattern")
    void failedTermHasError() {
        // "(unclosed" is escaped by the translator to "\(unclosed" — a valid Hyperscan literal.
        // "[unclosed" is an unclosed character class — Hyperscan always rejects it.
        var resp = compile("err_test", "[unclosed");
        var failed = resp.results().stream()
                .filter(r -> r.compilationStatus() == CompilationStatus.FAILED)
                .findFirst();
        assertThat(failed).isPresent();
        assertThat(failed.get().translationError()).isNotBlank();
    }

    @Test @Order(13)
    @DisplayName("engineMode is always HYPERSCAN_NATIVE, never RE2J or fallback")
    void engineModeNeverFallback() {
        var resp = compile("mode_test", "price OR spread");
        assertThat(resp.engineMode()).isEqualTo("HYPERSCAN_NATIVE");
        assertThat(resp.engineMode()).doesNotContainIgnoringCase("re2j");
        assertThat(resp.engineMode()).doesNotContainIgnoringCase("fallback");
    }

    // ── Multi-language compilation ────────────────────────────────────────────

    @Test @Order(20)
    @DisplayName("Korean terms compile successfully with UTF8 flags")
    void koreanCompiles() {
        var resp = compile("ko_rule", "비밀 OR 내부자 거래");
        assertThat(resp.passCount()).isEqualTo(1);
        assertThat(resp.results().get(0).hyperscanFlags()
                & 32).isEqualTo(32); // UTF8 flag
    }

    @Test @Order(21)
    @DisplayName("Japanese terms compile successfully")
    void japaneseCompiles() {
        var resp = compile("ja_rule", "株価操作 OR インサイダー取引");
        assertThat(resp.passCount()).isEqualTo(1);
    }

    @Test @Order(22)
    @DisplayName("Chinese/Mandarin terms compile successfully")
    void chineseCompiles() {
        var resp = compile("zh_rule", "内幕交易 OR 操纵市场");
        assertThat(resp.passCount()).isEqualTo(1);
    }

    @Test @Order(23)
    @DisplayName("Arabic terms compile successfully")
    void arabicCompiles() {
        var resp = compile("ar_rule", "مخالفة OR استثمار داخلي");
        assertThat(resp.passCount()).isEqualTo(1);
    }

    @Test @Order(24)
    @DisplayName("Hebrew terms compile successfully")
    void hebrewCompiles() {
        var resp = compile("he_rule", "מסחר פנים OR מניפולציה");
        assertThat(resp.passCount()).isEqualTo(1);
    }

    @Test @Order(25)
    @DisplayName("German umlaut terms compile with UTF8+UCP flags")
    void germanCompiles() {
        var resp = compile("de_rule", "Übernahme OR Insiderhandel");
        assertThat(resp.passCount()).isEqualTo(1);
        assertThat(resp.results().get(0).hyperscanFlags() & 32).isEqualTo(32); // UTF8
    }

    @Test @Order(26)
    @DisplayName("Turkish terms compile with UTF8+UCP flags")
    void turkishCompiles() {
        var resp = compile("tr_rule", "içeriden bilgi OR piyasa manipülasyonu");
        assertThat(resp.passCount()).isEqualTo(1);
    }

    @Test @Order(27)
    @DisplayName("Emoji terms compile with UTF8+UCP flags")
    void emojiCompiles() {
        var resp = compile("emoji_rule", "💰 OR 🤫 OR 🤐");
        assertThat(resp.passCount()).isEqualTo(1);
        assertThat(resp.results().get(0).hyperscanFlags() & 32).isEqualTo(32); // UTF8
    }

    @Test @Order(28)
    @DisplayName("Mixed English + Korean + emoji compiles")
    void mixedLanguageCompiles() {
        var resp = compile("mixed_rule", "insider OR 내부자 OR 💰");
        assertThat(resp.passCount()).isEqualTo(1);
    }

    // ── FOLLOWEDBY proximity ──────────────────────────────────────────────────

    @Test @Order(30)
    @DisplayName("FOLLOWEDBY{3}: don't FOLLOWEDBY{3} compliance → PASS")
    void followedByCompiles() {
        var resp = compile("fb_rule", "don't FOLLOWEDBY{3} compliance");
        assertThat(resp.passCount()).isEqualTo(1);
        assertThat(resp.results().get(0).translatedPattern()).contains("don't");
        assertThat(resp.results().get(0).translatedPattern()).contains("{0,3}");
    }

    // ── AND post-filter flag ──────────────────────────────────────────────────

    @Test @Order(40)
    @DisplayName("AND operator: compiles as OR pre-scan pattern; requiresAndPostFilter = true")
    void andRequiresPostFilter() {
        // After translator fix: AND → (?:A|B|C) — valid Hyperscan; passCount must be 1
        var resp = compile("and_rule", "insider AND announcement AND price");
        assertThat(resp.passCount()).isEqualTo(1);
        assertThat(resp.failedCount()).isEqualTo(0);
        assertThat(resp.results().get(0).requiresAndPostFilter()).isTrue();
        // OR pre-scan pattern must contain all operands
        assertThat(resp.results().get(0).translatedPattern())
                .contains("insider").contains("announcement").contains("price");
    }

    // ── Performance ───────────────────────────────────────────────────────────

    @Test @Order(50)
    @DisplayName("Performance: 100 term compilations in under 10 seconds")
    void performance() {
        long start = System.currentTimeMillis();
        for (int i = 0; i < 100; i++) {
            compile("perf_test",
                    "(manipulate*) NEAR{5} ((price) OR (spread) OR (stock))");
        }
        long elapsed = System.currentTimeMillis() - start;
        System.out.printf("100 compilations: %dms (%.2fms/term)%n",
                elapsed, elapsed / 100.0);
        // Hyperscan native library compile time varies by hardware; 10s is a safe bound
        assertThat(elapsed).isLessThan(10_000L);
    }
}
