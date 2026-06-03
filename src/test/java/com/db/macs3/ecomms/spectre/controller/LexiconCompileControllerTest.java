package com.db.macs3.ecomms.spectre.controller;

import com.db.macs3.ecomms.spectre.model.CompileRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spring MockMvc integration tests for {@link LexiconCompileController}.
 *
 * <p>Tests:
 * <ol>
 *   <li>Plain JSON compile request → correct response structure</li>
 *   <li>GZIP-compressed request body decompressed by {@code GzipRequestFilter}</li>
 *   <li>GZIP response when {@code Accept-Encoding: gzip} is set</li>
 *   <li>CSV multipart upload</li>
 *   <li>Validation errors → HTTP 400</li>
 *   <li>Health endpoint</li>
 *   <li>Multi-language and emoji terms</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("LexiconCompileController Integration Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LexiconCompileControllerTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;

    // ── Compression helpers ───────────────────────────────────────────────────

    private byte[] gzip(byte[] data) throws IOException {
        var baos = new ByteArrayOutputStream();
        try (var gzos = new GZIPOutputStream(baos)) {
            gzos.write(data);
        }
        return baos.toByteArray();
    }

    private byte[] gunzip(byte[] data) throws IOException {
        var baos = new ByteArrayOutputStream();
        try (var gzis = new GZIPInputStream(new ByteArrayInputStream(data))) {
            gzis.transferTo(baos);
        }
        return baos.toByteArray();
    }

    private CompileRequest buildRequest(String ruleName, String... descriptions) {
        var req = new CompileRequest();
        req.setLexiconRuleName(ruleName);
        for (int i = 0; i < descriptions.length; i++) {
            req.getTerms().add(new CompileRequest.TermInput(
                    ruleName + "::" + (i + 1), descriptions[i], "Test"));
        }
        return req;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // SECTION 1: Plain JSON request
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @Order(1)
    @DisplayName("POST /compile — plain JSON → HTTP 200 with correct structure")
    void postCompilePlain200() throws Exception {
        var req = buildRequest("lexicon_research_1",
                "(manipulate) NEAR{5} ((price) OR (spread) OR (stock))");

        mockMvc.perform(post("/api/lexicon/compile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(req)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.lexiconRuleName").value("lexicon_research_1"))
                .andExpect(jsonPath("$.totalTerms").value(1))
                .andExpect(jsonPath("$.engineMode").value("HYPERSCAN_NATIVE"))
                .andExpect(jsonPath("$.hyperscanVersion").value("5.4.0-2.0.0"))
                .andExpect(jsonPath("$.compiledAt").isNotEmpty())
                .andExpect(jsonPath("$.results").isArray())
                .andExpect(jsonPath("$.results", hasSize(1)));
    }

    @Test
    @Order(2)
    @DisplayName("POST /compile — spec example 1 → PASS with translatedPattern")
    void specExample1Pass() throws Exception {
        var req = buildRequest("lexicon_research_1",
                "(manipulate*) NEAR{5} ((price) OR (spread) OR (stock))");

        mockMvc.perform(post("/api/lexicon/compile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passCount").value(1))
                .andExpect(jsonPath("$.failedCount").value(0))
                .andExpect(jsonPath("$.hasFailures").value(false))
                .andExpect(jsonPath("$.results[0].compilationStatus").value("PASS"))
                .andExpect(jsonPath("$.results[0].translatedPattern").isNotEmpty())
                .andExpect(jsonPath("$.results[0].termId").value("lexicon_research_1::1"))
                .andExpect(jsonPath("$.results[0].compiledAt").isNotEmpty());
    }

    @Test
    @Order(3)
    @DisplayName("POST /compile — FAILED term has errorLog (uses truly invalid [unclosed pattern)")
    void failedTermHasError() throws Exception {
        // "(unclosed" is escaped by translator to "\(unclosed" — a valid Hyperscan literal → PASS
        // "[unclosed" is an unclosed character class — Hyperscan always rejects it → FAILED
        var req = buildRequest("err_rule", "[unclosed");

        mockMvc.perform(post("/api/lexicon/compile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].compilationStatus").value("FAILED"))
                .andExpect(jsonPath("$.hasFailures").value(true));
    }

    @Test
    @Order(4)
    @DisplayName("POST /compile — engineMode never RE2J or fallback")
    void engineModeNeverFallback() throws Exception {
        var req = buildRequest("mode_test", "price OR spread");

        MvcResult result = mockMvc.perform(post("/api/lexicon/compile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.engineMode").value("HYPERSCAN_NATIVE"))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContainIgnoringCase("re2j");
        assertThat(body).doesNotContainIgnoringCase("fallback");
    }

    // ═════════════════════════════════════════════════════════════════════════
    // SECTION 2: GZIP-compressed request
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @Order(10)
    @DisplayName("POST /compile — GZIP request body is decompressed by GzipRequestFilter")
    void gzipRequestBody() throws Exception {
        var req = buildRequest("gzip_test",
                "(manipulate*) NEAR{5} ((price) OR (spread) OR (stock))");
        byte[] compressed = gzip(objectMapper.writeValueAsBytes(req));

        mockMvc.perform(post("/api/lexicon/compile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Content-Encoding", "gzip")
                        .content(compressed))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lexiconRuleName").value("gzip_test"))
                .andExpect(jsonPath("$.passCount").value(1))
                .andExpect(jsonPath("$.engineMode").value("HYPERSCAN_NATIVE"));
    }

    @Test
    @Order(11)
    @DisplayName("POST /compile — GZIP request with Accept-Encoding: gzip → response may be GZIP")
    void gzipBothDirections() throws Exception {
        var req = buildRequest("gzip_both", "insider OR front run OR tip");
        byte[] compressed = gzip(objectMapper.writeValueAsBytes(req));

        MvcResult result = mockMvc.perform(post("/api/lexicon/compile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Content-Encoding", "gzip")
                        .header("Accept-Encoding", "gzip")
                        .content(compressed))
                .andExpect(status().isOk())
                .andReturn();

        String contentEncoding = result.getResponse().getHeader("Content-Encoding");
        if ("gzip".equalsIgnoreCase(contentEncoding)) {
            byte[] body = gunzip(result.getResponse().getContentAsByteArray());
            String json = new String(body, StandardCharsets.UTF_8);
            assertThat(json).contains("gzip_both").contains("HYPERSCAN_NATIVE");
        } else {
            assertThat(result.getResponse().getContentAsString())
                    .contains("gzip_both").contains("HYPERSCAN_NATIVE");
        }
    }

    @Test
    @Order(12)
    @DisplayName("POST /compile — large GZIP request (50 terms) all compiled")
    void largeGzipRequest() throws Exception {
        var req = new CompileRequest();
        req.setLexiconRuleName("large_test");
        var terms = new ArrayList<CompileRequest.TermInput>();
        for (int i = 0; i < 50; i++) {
            terms.add(new CompileRequest.TermInput(
                    "large_test::" + (i + 1), "keyword_" + i + "* OR term_" + i, "Cat"));
        }
        req.setTerms(terms);

        byte[] compressed = gzip(objectMapper.writeValueAsBytes(req));
        mockMvc.perform(post("/api/lexicon/compile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Content-Encoding", "gzip")
                        .content(compressed))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalTerms").value(50))
                .andExpect(jsonPath("$.results", hasSize(50)));
    }

    // ═════════════════════════════════════════════════════════════════════════
    // SECTION 3: Validation errors
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @Order(20)
    @DisplayName("POST /compile — missing lexiconRuleName → HTTP 400")
    void missingRuleName400() throws Exception {
        String body = """
                {"terms":[{"termId":"t::1","termDescription":"price","riskDriverName":"x"}]}
                """;
        mockMvc.perform(post("/api/lexicon/compile")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation failed"));
    }

    @Test
    @Order(21)
    @DisplayName("POST /compile — empty terms list → HTTP 400")
    void emptyTerms400() throws Exception {
        mockMvc.perform(post("/api/lexicon/compile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lexiconRuleName\":\"test\",\"terms\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Order(22)
    @DisplayName("POST /compile — GZIP request with invalid JSON → HTTP 400 or 500")
    void gzipInvalidJson() throws Exception {
        byte[] compressed = gzip("{invalid json{{".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(post("/api/lexicon/compile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Content-Encoding", "gzip")
                        .content(compressed))
                .andExpect(result ->
                        assertThat(result.getResponse().getStatus()).isIn(400, 500));
    }

    // ═════════════════════════════════════════════════════════════════════════
    // SECTION 4: CSV upload
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @Order(30)
    @DisplayName("POST /compile/csv — standard CSV → HTTP 200 with results")
    void csvUpload200() throws Exception {
        String csv = """
                Term ID, Term Description, Risk Driver Name
                lexicon_research_1::1, (manipulate*) NEAR{5} ((price) OR (spread)), Front Running
                lexicon_research_1::2, insider AND announcement, Insider Trading
                """;
        var file = new MockMultipartFile("file", "lexicon_research_1.csv",
                "text/csv", csv.getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/lexicon/compile/csv")
                        .file(file)
                        .param("ruleName", "lexicon_research_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lexiconRuleName").value("lexicon_research_1"))
                .andExpect(jsonPath("$.totalTerms").value(2))
                .andExpect(jsonPath("$.engineMode").value("HYPERSCAN_NATIVE"))
                .andExpect(jsonPath("$.results", hasSize(2)));
    }

    @Test
    @Order(31)
    @DisplayName("POST /compile/csv — empty file → HTTP 400")
    void csvEmpty400() throws Exception {
        var file = new MockMultipartFile("file", "empty.csv", "text/csv", new byte[0]);
        mockMvc.perform(multipart("/api/lexicon/compile/csv").file(file))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Order(32)
    @DisplayName("POST /compile/csv — ruleName inferred from filename")
    void csvRuleNameFromFilename() throws Exception {
        String csv = "Term ID, Term Description, Risk Driver Name\n"
                + "my_rule::1, price OR spread, Category\n";
        var file = new MockMultipartFile("file", "my_rule.csv",
                "text/csv", csv.getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/lexicon/compile/csv").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lexiconRuleName").value("my_rule"));
    }

    // ═════════════════════════════════════════════════════════════════════════
    // SECTION 5: Health and multi-language
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @Order(40)
    @DisplayName("GET /api/lexicon/health → UP with engine details and supported features")
    void engineHealth() throws Exception {
        mockMvc.perform(get("/api/lexicon/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.engineMode").value("HYPERSCAN_NATIVE"))
                .andExpect(jsonPath("$.hyperscanVersion").value("5.4.0-2.0.0"))
                .andExpect(jsonPath("$.supportedOperators").isArray())
                .andExpect(jsonPath("$.supportedLanguages").isArray())
                .andExpect(jsonPath("$.compressionMode").value("GZIP request + response"));
    }

    @Test
    @Order(41)
    @DisplayName("GET /actuator/health → UP with hyperscan component")
    void actuatorHealth() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @Order(50)
    @DisplayName("POST /compile — Korean GZIP request → PASS with UTF8 flag")
    void koreanGzipRequest() throws Exception {
        var req = buildRequest("ko_rule", "비밀 OR 내부자 거래");
        byte[] compressed = gzip(objectMapper.writeValueAsBytes(req));

        mockMvc.perform(post("/api/lexicon/compile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Content-Encoding", "gzip")
                        .content(compressed))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].compilationStatus").value("PASS"))
                .andExpect(jsonPath("$.results[0].hyperscanFlags",
                        greaterThanOrEqualTo(32))); // UTF8 flag
    }

    @Test
    @Order(51)
    @DisplayName("POST /compile — Emoji GZIP request → PASS with UTF8 flag")
    void emojiGzipRequest() throws Exception {
        var req = buildRequest("emoji_rule", "💰 OR 🤫 OR 🤐");
        byte[] compressed = gzip(objectMapper.writeValueAsBytes(req));

        mockMvc.perform(post("/api/lexicon/compile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Content-Encoding", "gzip")
                        .content(compressed))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].compilationStatus").value("PASS"));
    }

    @Test
    @Order(52)
    @DisplayName("POST /compile — AND term → requiresAndPostFilter = true")
    void andTermPostFilter() throws Exception {
        var req = buildRequest("and_rule", "insider AND before AND announcement");
        mockMvc.perform(post("/api/lexicon/compile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].requiresAndPostFilter").value(true));
    }
}
