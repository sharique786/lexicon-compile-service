package com.db.macs3.ecomms.spectre.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.ArrayList;
import java.util.List;

/**
 * Legacy per-term request shape (with {@code riskDriverName}). No endpoint uses it any more — every
 * endpoint takes {@link TypedCompileRequest}.
 */
public class CompileRequest {

    @NotBlank(message = "lexiconRuleName must not be blank")
    @JsonProperty("lexiconRuleName")
    private String lexiconRuleName;

    @NotEmpty(message = "terms list must not be empty")
    @Valid
    @JsonProperty("terms")
    private List<TermInput> terms = new ArrayList<>();

    // ── Nested record: TermInput ──────────────────────────────────────────────

    /**
     * One lexicon term in the request.
     *
     * <p>The {@code termDescription} field supports:
     * <ul>
     *   <li>OR, AND, AND NOT, NOT operators</li>
     *   <li>NEAR{n} — bidirectional proximity within n words</li>
     *   <li>FOLLOWEDBY{n} — directional proximity within n words</li>
     *   <li>Wildcards: {@code word*}, {@code *word}</li>
     *   <li>Quoted phrases: {@code "exact phrase"}</li>
     *   <li>Emojis: 💰 🤫 🤐</li>
     *   <li>Non-English: Korean, Japanese, Chinese, Arabic, Hebrew, German, Turkish</li>
     *   <li>Leet-speak: {@code 1ns1d3r}, {@code 4} for {@code a}, etc.</li>
     *   <li>Apostrophes: {@code don't}, {@code can't}</li>
     *   <li>Exclamation marks: {@code stop!}</li>
     * </ul>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TermInput(

            @JsonProperty("termId")
            @NotBlank(message = "termId must not be blank")
            String termId,

            @JsonProperty("termDescription")
            @NotBlank(message = "termDescription must not be blank")
            String termDescription,

            @JsonProperty("riskDriverName")
            String riskDriverName

    ) {
    }

    public String getLexiconRuleName() {
        return lexiconRuleName;
    }

    public void setLexiconRuleName(String lexiconRuleName) {
        this.lexiconRuleName = lexiconRuleName;
    }

    public List<TermInput> getTerms() {
        return terms;
    }

    public void setTerms(List<TermInput> terms) {
        this.terms = terms;
    }
}
