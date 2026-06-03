package com.db.macs3.ecomms.spectre.controller;

import com.db.macs3.ecomms.spectre.model.CompileRequest;
import com.db.macs3.ecomms.spectre.model.CompileResponse;
import com.db.macs3.ecomms.spectre.service.CsvCompileService;
import com.db.macs3.ecomms.spectre.service.LexiconCompileService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller for the Lexicon Compile Service.
 *
 * <h2>Endpoints</h2>
 * <dl>
 *   <dt>POST /api/lexicon/compile</dt>
 *   <dd>JSON body (plain or GZIP-compressed) → compile results</dd>
 *   <dt>POST /api/lexicon/compile/csv</dt>
 *   <dd>Multipart CSV file → compile results</dd>
 *   <dt>GET /api/lexicon/health</dt>
 *   <dd>Engine status and supported features</dd>
 * </dl>
 *
 * <h2>Compression</h2>
 * <p>Request decompression: {@code GzipRequestFilter}.
 * Response compression: Tomcat {@code server.compression.*}.
 * HTTP 200 is returned even when individual terms fail compilation.
 */
@RestController
@RequestMapping("/api/lexicon")
public class LexiconCompileController {

    private static final Logger log = LoggerFactory.getLogger(LexiconCompileController.class);

    private final LexiconCompileService compileService;
    private final CsvCompileService     csvCompileService;

    public LexiconCompileController(LexiconCompileService compileService,
                                     CsvCompileService csvCompileService) {
        this.compileService    = compileService;
        this.csvCompileService = csvCompileService;
    }

    // ── POST /api/lexicon/compile ─────────────────────────────────────────────

    /**
     * Compiles lexicon terms from a JSON body.
     *
     * <p>Recommended client request:
     * <pre>
     * POST /api/lexicon/compile
     * Content-Type: application/json
     * Content-Encoding: gzip        ← request body is GZIP-compressed
     * Accept-Encoding:  gzip        ← client accepts GZIP response
     * </pre>
     *
     * @param request validated compile request
     * @return compile response with per-term results
     */
    @PostMapping(
            value    = "/compile",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<CompileResponse> compileJson(
            @Valid @RequestBody CompileRequest request) {

        log.info("POST /compile — rule='{}', terms={}",
                request.getLexiconRuleName(), request.getTerms().size());
        return ResponseEntity.ok(compileService.compile(request));
    }

    // ── POST /api/lexicon/compile/csv ─────────────────────────────────────────

    /**
     * Compiles lexicon terms from a CSV multipart file upload.
     *
     * <p>The CSV must have columns: {@code Term ID, Term Description, Risk Driver Name}
     *
     * @param file     CSV file (UTF-8, BOM optional)
     * @param ruleName optional rule name override (defaults to filename without extension)
     * @return compile response with per-term results
     */
    @PostMapping(
            value    = "/compile/csv",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<CompileResponse> compileCsv(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "ruleName", required = false) String ruleName) {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        String effectiveRule = (ruleName != null && !ruleName.isBlank())
                ? ruleName
                : stripExtension(file.getOriginalFilename());

        log.info("POST /compile/csv — file='{}', ruleName='{}', size={}",
                file.getOriginalFilename(), effectiveRule, file.getSize());

        try {
            return ResponseEntity.ok(
                    csvCompileService.compileFromCsv(file.getInputStream(), effectiveRule));
        } catch (IOException e) {
            log.error("Failed to parse CSV: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // ── GET /api/lexicon/health ───────────────────────────────────────────────

    /**
     * Engine health and feature listing.
     *
     * @return map with engine mode, version, supported operators and languages
     */
    @GetMapping(value = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> engineHealth() {
        var info = new LinkedHashMap<String, Object>();
        info.put("status",           "UP");
        info.put("engineMode",       compileService.getEngineMode());
        info.put("hyperscanLibrary", "com.gliwka.hyperscan");
        info.put("hyperscanVersion", "5.4.0-2.0.0");
        info.put("springBoot",       "4.0.6");
        info.put("jdk",              "21");
        info.put("compressionMode",  "GZIP request + response");
        info.put("supportedOperators",
                List.of("OR", "AND", "AND NOT", "NOT", "NEAR{n}", "FOLLOWEDBY{n}"));
        info.put("supportedLanguages",
                List.of("English", "Korean", "Japanese", "Chinese", "Mandarin",
                        "Arabic", "Hebrew", "German", "Turkish", "Emoji", "Leet-speak"));
        info.put("timestamp", Instant.now().toString());
        return ResponseEntity.ok(info);
    }

    private String stripExtension(String filename) {
        if (filename == null) {
            return "unknown_rule";
        }
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}
