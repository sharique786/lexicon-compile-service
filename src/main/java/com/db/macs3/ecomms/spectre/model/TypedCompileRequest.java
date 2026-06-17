package com.db.macs3.ecomms.spectre.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;

import java.util.ArrayList;
import java.util.List;

/**
 * Request body for {@code POST /api/lexicon/compile/bundle}.
 *
 * <p>Deliberately a separate type from {@link CompileRequest} rather than an
 * extension of it. {@code CompileRequest.TermInput} is a record with a fixed
 * 3-arg positional constructor already used throughout the existing test
 * suite (e.g. {@code new CompileRequest.TermInput(termId, termDescription,
 * riskDriverName)}); adding a 4th component to that record would break every
 * existing call site. Keeping this as an independent type means the
 * {@code /compile} endpoint and its tests are completely unaffected by this
 * feature.
 *
 * <p>JSON example:
 * <pre>
 * {
 *   "lexiconRuleName": "lexicon_research_1",
 *   "terms": [
 *     {
 *       "termId": "lexicon_research_1::1",
 *       "termDescription": "(manipulate) NEAR{5} ((price) OR (spread) OR (stock))",
 *       "termType": "Standard"
 *     },
 *     {
 *       "termId": "lexicon_research_1::2",
 *       "termDescription": "(?:price|spread|stock)",
 *       "termType": "NLT"
 *     }
 *   ]
 * }
 * </pre>
 *
 * <h2>termType semantics</h2>
 * <dl>
 *   <dt>{@code "Standard"}</dt>
 *   <dd>{@code termDescription} is the existing custom operator language
 *       (OR / AND / NEAR{n} / FOLLOWEDBY{n} / wildcards / quoted phrases /
 *       etc.) and is translated into a Hyperscan PCRE pattern by
 *       {@code TermSyntaxTranslator} — identical to {@code /compile} today.</dd>
 *   <dt>{@code "NLT"}</dt>
 *   <dd>"Non-Lexicon Term" — {@code termDescription} is <em>already</em> a
 *       valid PCRE pattern supplied directly by the caller. No translation
 *       is performed; the string is compiled by Hyperscan exactly as given.</dd>
 * </dl>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TypedCompileRequest {

    @NotBlank(message = "lexiconRuleName must not be blank")
    @JsonProperty("lexiconRuleName")
    private String lexiconRuleName;

    @NotEmpty(message = "terms list must not be empty")
    @Valid
    @JsonProperty("terms")
    private List<TermInput> terms = new ArrayList<>();

    // ── Nested record: TermInput ──────────────────────────────────────────────

    /**
     * One lexicon term in the request, with an explicit {@code termType}.
     *
     * @param termId          unique identifier, echoed back in the per-term result
     * @param termDescription either custom operator-language syntax (Standard)
     *                        or a raw PCRE pattern supplied by the caller (NLT)
     * @param termType        exactly {@code "Standard"} or {@code "NLT"} (case-sensitive)
     * @param riskDriverName  optional, echoed back in the per-term result
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TermInput(

            @JsonProperty("termId")
            @NotBlank(message = "termId must not be blank")
            String termId,

            @JsonProperty("termDescription")
            @NotBlank(message = "termDescription must not be blank")
            String termDescription,

            @JsonProperty("termType")
            @NotBlank(message = "termType must not be blank")
            @Pattern(regexp = "Standard|NLT", message = "termType must be exactly 'Standard' or 'NLT'")
            String termType,

            @JsonProperty("riskDriverName")
            String riskDriverName

    ) {
        /** @return true when this term's {@code termType} is {@code "NLT"}. */
        public boolean isNlt() {
            return "NLT".equals(termType);
        }
    }

    public String getLexiconRuleName()          { return lexiconRuleName; }
    public void setLexiconRuleName(String v)    { this.lexiconRuleName = v; }
    public List<TermInput> getTerms()           { return terms; }
    public void setTerms(List<TermInput> v)     { this.terms = v; }
}
