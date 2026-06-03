package com.db.macs3.ecomms.spectre.service;

import com.db.macs3.ecomms.spectre.model.CompileRequest;
import com.db.macs3.ecomms.spectre.model.CompileResponse;
import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.exceptions.CsvException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses a lexicon CSV upload and delegates to {@link LexiconCompileService}.
 *
 * <h2>Expected CSV format</h2>
 * <pre>
 * Term ID, Term Description, Risk Driver Name
 * lexicon_research_1::1, (manipulate*) NEAR{5} ((price) OR (spread)), Front Running
 * lexicon_research_1::2, "((""please don't forward"") OR (""do not share""))", Research
 * </pre>
 *
 * <h2>Features</h2>
 * <ul>
 *   <li>Optional header row — detected by "term id" in first column</li>
 *   <li>RFC 4180 double-quote escaping: {@code ""} → {@code "}</li>
 *   <li>UTF-8 BOM stripping (Excel CSV export)</li>
 *   <li>Blank-line and comment-line ({@code #}) skipping</li>
 *   <li>Multi-language and emoji content in Term Description</li>
 * </ul>
 */
@Service
public class CsvCompileService {

    private static final Logger log = LoggerFactory.getLogger(CsvCompileService.class);

    private final LexiconCompileService compileService;

    public CsvCompileService(LexiconCompileService compileService) {
        this.compileService = compileService;
    }

    /**
     * Parses CSV from an {@link InputStream} and compiles all terms.
     *
     * @param csvStream  raw CSV bytes (may be BOM-prefixed UTF-8)
     * @param ruleName   lexicon rule name for the response
     * @return compile response
     * @throws IOException if the CSV cannot be parsed
     */
    public CompileResponse compileFromCsv(InputStream csvStream, String ruleName)
            throws IOException {
        List<CompileRequest.TermInput> terms = parseCsv(csvStream, ruleName);
        log.info("CSV parsed: {} terms for rule '{}'", terms.size(), ruleName);

        CompileRequest request = new CompileRequest();
        request.setLexiconRuleName(ruleName);
        request.setTerms(terms);
        return compileService.compile(request);
    }

    // ── CSV parsing ───────────────────────────────────────────────────────────

    private List<CompileRequest.TermInput> parseCsv(InputStream raw, String source)
            throws IOException {
        List<CompileRequest.TermInput> terms = new ArrayList<>();

        try (CSVReader reader = new CSVReaderBuilder(
                new InputStreamReader(stripBom(raw), StandardCharsets.UTF_8))
                .withCSVParser(new CSVParserBuilder()
                        .withSeparator(',')
                        .withQuoteChar('"')
                        .withIgnoreLeadingWhiteSpace(true)
                        .build())
                .build()) {

            List<String[]> rows = reader.readAll();
            if (rows.isEmpty()) {
                return terms;
            }

            int start = isHeaderRow(rows.get(0)) ? 1 : 0;

            for (int i = start; i < rows.size(); i++) {
                String[] row     = rows.get(i);
                int      lineNum = i + 1;

                if (isBlankRow(row)) {
                    continue;
                }
                if (row.length > 0 && row[0].trim().startsWith("#")) {
                    continue;
                }
                if (row.length < 2) {
                    log.warn("Skipping CSV row {} in '{}': only {} column(s)",
                            lineNum, source, row.length);
                    continue;
                }

                terms.add(new CompileRequest.TermInput(
                        row[0].trim(),
                        row[1].trim(),
                        row.length > 2 ? row[2].trim() : null));
            }

        } catch (CsvException e) {
            throw new IOException(
                    "CSV parse error in '" + source + "': " + e.getMessage(), e);
        }

        return terms;
    }

    private boolean isHeaderRow(String[] row) {
        return row != null && row.length > 0
                && row[0].trim().toLowerCase().contains("term id");
    }

    private boolean isBlankRow(String[] row) {
        if (row == null || row.length == 0) {
            return true;
        }
        for (String cell : row) {
            if (cell != null && !cell.isBlank()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Strips UTF-8 BOM (EF BB BF) that Excel prepends to CSV exports.
     */
    private InputStream stripBom(InputStream is) throws IOException {
        PushbackInputStream pis = new PushbackInputStream(is, 3);
        byte[] bom = new byte[3];
        int n = pis.read(bom, 0, 3);
        if (n == 3
                && (bom[0] & 0xFF) == 0xEF
                && (bom[1] & 0xFF) == 0xBB
                && (bom[2] & 0xFF) == 0xBF) {
            return pis;
        }
        if (n > 0) {
            pis.unread(bom, 0, n);
        }
        return pis;
    }
}
