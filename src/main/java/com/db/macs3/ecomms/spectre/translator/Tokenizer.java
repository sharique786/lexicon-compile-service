package com.db.macs3.ecomms.spectre.translator;

import java.util.ArrayList;
import java.util.List;

import static com.db.macs3.ecomms.spectre.translator.LexiconOperatorKeyword.AND;
import static com.db.macs3.ecomms.spectre.translator.LexiconOperatorKeyword.FOLLOWEDBY;
import static com.db.macs3.ecomms.spectre.translator.LexiconOperatorKeyword.NEAR;
import static com.db.macs3.ecomms.spectre.translator.LexiconOperatorKeyword.NOT;
import static com.db.macs3.ecomms.spectre.translator.LexiconOperatorKeyword.OR;

/**
 * Converts a raw lexicon term string into a {@link Token} stream.
 *
 * <p>This is the single place that scans the raw character sequence — every
 * downstream component ({@link ExpressionParser}) works only with the
 * resulting token list, never with raw string indices.
 *
 * <p><b>Validation performed here (lexical level)</b>
 * <ul>
 *   <li>Reserved keywords ({@code OR}, {@code AND}, {@code NOT}, {@code NEAR},
 *       {@code FOLLOWEDBY}) are recognised ONLY in exact case; any other
 *       case is ordinary literal text.</li>
 *   <li>{@code NEAR}/{@code FOLLOWEDBY} must be immediately followed by
 *       {@code {n}} with no whitespace, where {@code n} is a single digit 1-9
 *       (no zero, no negative, no letters, no multi-digit, no comma-separated values).</li>
 *   <li>Parentheses must be balanced.</li>
 *   <li>Quoted phrases must be closed.</li>
 *   <li>The term must contain at least one real letter-or-digit character
 *       somewhere (rejects pure-symbol input like {@code "#@$#%$"}).</li>
 * </ul>
 *
 * <p>Multi-word phrases (e.g. {@code bomb this place} with no wrapping
 * parentheses) are NOT rejected here — {@link ExpressionParser} accepts a
 * run of consecutive bare words as an implicit literal phrase, so an author
 * who forgets to wrap a multi-word phrase in parentheses still gets a
 * working term rather than a validation error.
 */
final class Tokenizer {

    private final String sourceText;
    private int currentIndex = 0;

    private Tokenizer(String sourceText) {
        this.sourceText = sourceText;
    }

    /**
     * Tokenizes {@code rawExpression}, fully validating lexical structure.
     *
     * @param rawExpression the preprocessed (trimmed, NFC-normalised) term text
     * @return the token stream, ready for {@link ExpressionParser}
     * @throws TranslationException on any lexical validation failure
     */
    static List<Token> tokenize(String rawExpression) {
        requireMeaningfulContent(rawExpression);
        return new Tokenizer(rawExpression).scanAllTokens();
    }

    // ── Meaningful-content check ────────────────────────────────────────────

    /**
     * Rejects input containing no letter-or-digit character anywhere — e.g.
     * {@code "#@$#%$"}. Unicode-aware ({@link Character#isLetterOrDigit(int)})
     * so German, Korean, Arabic, Chinese, etc. content is correctly accepted.
     */
    private static void requireMeaningfulContent(String text) {
        for (int charIndex = 0; charIndex < text.length(); ) {
            int codePoint = text.codePointAt(charIndex);
            if (Character.isLetterOrDigit(codePoint)) {
                return; // found at least one — valid
            }
            charIndex += Character.charCount(codePoint);
        }
        throw new TranslationException(
                "Lexicon term contains no meaningful content (only symbols/whitespace): '" + text + "'."
                        + " A lexicon term must contain at least one letter or digit.");
    }

    // ── Main scan loop ───────────────────────────────────────────────────────

    private List<Token> scanAllTokens() {
        List<Token> tokens = new ArrayList<>();
        int openParenDepth = 0;

        while (currentIndex < sourceText.length()) {
            char currentChar = sourceText.charAt(currentIndex);

            if (Character.isWhitespace(currentChar)) {
                currentIndex++;
                continue;
            }
            if (currentChar == '(') {
                tokens.add(new Token.LParen());
                openParenDepth++;
                currentIndex++;
                continue;
            }
            if (currentChar == ')') {
                openParenDepth--;
                if (openParenDepth < 0) {
                    throw new TranslationException(
                            "Unmatched closing parenthesis ')' at position " + currentIndex
                                    + " in term: '" + sourceText + "'."
                                    + " Every ')' must have a matching '(' before it.");
                }
                tokens.add(new Token.RParen());
                currentIndex++;
                continue;
            }
            if (currentChar == '"') {
                tokens.add(scanQuotedPhrase());
                continue;
            }

            tokens.add(scanWordLikeToken());
        }

        if (openParenDepth != 0) {
            throw new TranslationException(
                    "Unmatched opening parenthesis '(' in term: '" + sourceText + "'."
                            + " Every '(' must have a matching ')'. Found " + openParenDepth
                            + " unclosed parenthes" + (openParenDepth == 1 ? "is" : "es") + ".");
        }

        return tokens;
    }

    // ── Quoted phrase ────────────────────────────────────────────────────────

    private Token.QuotedPhrase scanQuotedPhrase() {
        int quoteStartIndex = currentIndex;
        currentIndex++; // consume opening quote
        StringBuilder phraseContent = new StringBuilder();
        while (currentIndex < sourceText.length() && sourceText.charAt(currentIndex) != '"') {
            phraseContent.append(sourceText.charAt(currentIndex));
            currentIndex++;
        }
        if (currentIndex >= sourceText.length()) {
            throw new TranslationException(
                    "Unclosed quoted phrase starting at position " + quoteStartIndex
                            + " in term: '" + sourceText + "'."
                            + " Every opening '\"' must have a matching closing '\"'.");
        }
        currentIndex++; // consume closing quote
        return new Token.QuotedPhrase(phraseContent.toString());
    }

    // ── Word-like run (operator keyword or literal word) ────────────────────

    /**
     * Scans one maximal run of non-whitespace, non-paren, non-quote
     * characters and classifies it as a reserved-keyword token or an
     * ordinary {@link Token.Word}.
     */
    private Token scanWordLikeToken() {
        int wordStartIndex = currentIndex;
        while (currentIndex < sourceText.length() && !isTokenBoundary(sourceText.charAt(currentIndex))) {
            currentIndex++;
        }
        String word = sourceText.substring(wordStartIndex, currentIndex);

        // ── Exact-case reserved keywords ────────────────────────────────────
        if (word.equals(OR)) {
            return new Token.Or();
        }
        if (word.equals(NOT)) {
            // "NOT" is tokenized as its own keyword here so scanAndOrAndNot()
            // (called from the AND branch above) can pair it with a preceding
            // AND into a single Token.AndNot. A bare Token.Not that reaches
            // ExpressionParser WITHOUT having been consumed that way means
            // "NOT" appeared on its own — ExpressionParser rejects that with
            // a specific error (there is no independent NOT operator; see
            // requirement history — it must always be written "AND NOT").
            return new Token.Not();
        }
        if (word.equals(AND)) {
            return scanAndOrAndNot();
        }
        if (word.startsWith(NEAR)) {
            return scanProximityKeyword(word, NEAR);
        }
        if (word.startsWith(FOLLOWEDBY)) {
            return scanProximityKeyword(word, FOLLOWEDBY);
        }

        return new Token.Word(word);
    }

    /**
     * True for characters that end a word-like run: whitespace, parens, or the quote character.
     */
    private static boolean isTokenBoundary(char character) {
        return Character.isWhitespace(character) || character == '(' || character == ')' || character == '"';
    }

    /**
     * Having just consumed the exact word {@code "AND"}, looks ahead
     * (skipping whitespace only — no intervening punctuation) for a
     * following exact word {@code "NOT"}. If found, consumes it too and
     * returns a single {@link Token.AndNot}; otherwise returns {@link Token.And}
     * without consuming anything further.
     *
     * <p>This recognises {@code AND} and {@code NOT} as independent tokens,
     * so whatever comes after {@code NOT} (a space, a parenthesis, anything)
     * is irrelevant to recognising the operator — {@code "AND NOT("} with no
     * space before the parenthesis is recognised exactly the same as
     * {@code "AND NOT ("}.
     */
    private Token scanAndOrAndNot() {
        int rewindIndex = currentIndex;
        skipWhitespace();
        int nextWordStartIndex = currentIndex;
        while (currentIndex < sourceText.length() && !isTokenBoundary(sourceText.charAt(currentIndex))) {
            currentIndex++;
        }
        String nextWord = sourceText.substring(nextWordStartIndex, currentIndex);
        if (nextWord.equals(NOT)) {
            return new Token.AndNot();
        }
        // Not a NOT — rewind to just after "AND" and let the next scan loop
        // iteration tokenize whatever followed on its own.
        currentIndex = rewindIndex;
        return new Token.And();
    }

    private void skipWhitespace() {
        while (currentIndex < sourceText.length() && Character.isWhitespace(sourceText.charAt(currentIndex))) {
            currentIndex++;
        }
    }

    /**
     * Validates and tokenizes a {@code NEAR{n}} / {@code FOLLOWEDBY{n}}
     * candidate. {@code word} is the word-like text already scanned (which
     * may be just the bare keyword with no brace at all, or the keyword plus
     * a malformed brace expression) — every failure mode gets a specific,
     * actionable error message rather than silently falling back to literal
     * text, since a near-miss on this syntax is essentially always a typo,
     * not intentional literal content.
     *
     * @param word    the word-like run already scanned (starts with {@code keyword})
     * @param keyword {@link LexiconOperatorKeyword#NEAR} or {@link LexiconOperatorKeyword#FOLLOWEDBY}
     */
    private Token scanProximityKeyword(String word, String keyword) {
        if (word.equals(keyword)) {
            // Bare "NEAR" / "FOLLOWEDBY" with no attached brace at all.
            // Check whether a brace expression follows separated by whitespace —
            // that's the specific "whitespace before brace" validation failure.
            int rewindIndex = currentIndex;
            skipWhitespace();
            if (currentIndex < sourceText.length() && sourceText.charAt(currentIndex) == '{') {
                throw new TranslationException(
                        keyword + " must be immediately followed by '{n}' with no whitespace"
                                + " (found a space before '{' at position " + currentIndex
                                + " in term: '" + sourceText + "')."
                                + " Write it as " + keyword + "{n}, e.g. " + keyword + "{3}.");
            }
            currentIndex = rewindIndex;
            throw new TranslationException(
                    keyword + " is missing its '{n}' distance value in term: '" + sourceText + "'."
                            + " Expected format: " + keyword + "{n}, e.g. " + keyword + "{3}."
                            + " To use \"" + keyword + "\" as literal text instead, wrap it in quotes.");
        }

        // word is longer than the bare keyword — must be keyword immediately
        // followed by a brace expression within the SAME whitespace-free run.
        String braceExpression = word.substring(keyword.length());
        if (braceExpression.charAt(0) != '{') {
            // e.g. "NEARby" — not our keyword at all, just an ordinary word
            // that happens to start with the same letters.
            return new Token.Word(word);
        }

        if (!braceExpression.endsWith("}")) {
            throw new TranslationException(
                    keyword + "{...} is missing its closing '}' in term: '" + sourceText + "'.");
        }

        String distanceText = braceExpression.substring(1, braceExpression.length() - 1);
        validateProximityDistance(keyword, distanceText);
        int distance = Integer.parseInt(distanceText);
        return keyword.equals(NEAR) ? new Token.Near(distance) : new Token.FollowedBy(distance);
    }

    /**
     * Validates the {@code n} inside {@code NEAR{n}}/{@code FOLLOWEDBY{n}}:
     * must be exactly one digit, 1-9 (no zero, no negative sign, no letters,
     * no multi-digit values, no comma-separated values).
     */
    private void validateProximityDistance(String keyword, String distanceText) {
        if (distanceText.isEmpty()) {
            throw new TranslationException(
                    keyword + "{} has no distance value in term: '" + sourceText + "'. Expected "
                            + keyword + "{n}, e.g. " + keyword + "{3}.");
        }
        if (distanceText.contains(",")) {
            throw new TranslationException(
                    keyword + "{" + distanceText + "} is invalid in term: '" + sourceText + "'."
                            + " Only a single value is allowed (no comma-separated range). Expected "
                            + keyword + "{n}, e.g. " + keyword + "{3}.");
        }
        boolean isValidSingleDigit = distanceText.length() == 1
                && distanceText.charAt(0) >= '1' && distanceText.charAt(0) <= '9';
        if (!isValidSingleDigit) {
            String reason;
            if (distanceText.chars().allMatch(Character::isDigit)) {
                reason = distanceText.equals("0") ? "zero is not allowed"
                        : distanceText.length() > 1 ? "only a single digit (1-9) is allowed, not a multi-digit value"
                        : "value out of range";
            } else if (distanceText.startsWith("-")) {
                reason = "negative values are not allowed";
            } else {
                reason = "the distance must be numeric, not '" + distanceText + "'";
            }
            throw new TranslationException(
                    keyword + "{" + distanceText + "} is invalid in term: '" + sourceText + "' — " + reason + "."
                            + " Expected " + keyword + "{n} where n is a single digit from 1 to 9.");
        }
    }
}
