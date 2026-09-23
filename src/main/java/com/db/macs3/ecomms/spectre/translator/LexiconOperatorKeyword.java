package com.db.macs3.ecomms.spectre.translator;

/**
 * The reserved operator keywords of the lexicon operator language, defined once and referenced by
 * {@link Tokenizer}, {@link ExpressionParser} and their error messages. All are reserved only in
 * this exact (upper) case.
 */
final class LexiconOperatorKeyword {

    private LexiconOperatorKeyword() {
    }

    static final String OR = "OR";
    static final String AND = "AND";
    static final String NOT = "NOT";
    static final String NEAR = "NEAR";
    static final String FOLLOWEDBY = "FOLLOWEDBY";

    /**
     * The two-word combination recognised as a single AND-NOT operator.
     */
    static final String AND_NOT_DISPLAY = AND + " " + NOT;
}
