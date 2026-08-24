package com.db.macs3.ecomms.spectre.translator;

/**
 * Single source of truth for every reserved operator keyword in the lexicon
 * operator language. Previously these appeared as raw string literals
 * ("OR", "AND", "NEAR", "FOLLOWEDBY", "NOT") scattered across
 * {@link Tokenizer}, {@link ExpressionParser}, and their error messages —
 * changing a keyword, or checking every place case-sensitivity matters,
 * meant hunting down every occurrence individually. Referencing these
 * constants everywhere means there is exactly one place that defines what
 * the reserved keywords are.
 *
 * <p>All keywords are reserved ONLY in this exact case — see the
 * case-sensitivity requirement in {@link Tokenizer} class Javadoc.
 */
final class LexiconOperatorKeyword {

    private LexiconOperatorKeyword() {}

    static final String OR          = "OR";
    static final String AND         = "AND";
    static final String NOT         = "NOT";
    static final String NEAR        = "NEAR";
    static final String FOLLOWEDBY  = "FOLLOWEDBY";

    /** The two-word combination recognised as a single AND-NOT operator. */
    static final String AND_NOT_DISPLAY = AND + " " + NOT;
}
