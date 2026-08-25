package com.db.macs3.ecomms.spectre.translator;

import java.util.ArrayList;
import java.util.List;

/**
 * Recursive-descent parser: {@code List<Token>} → {@link Ast}.
 *
 * <p><b>Grammar (highest precedence last, i.e. tightest-binding first)</b>
 * <pre>
 * orExpr        := andNotExpr ( OR andNotExpr )*
 * andNotExpr    := andExpr ( AND_NOT andExpr )*
 * andExpr       := proximityExpr ( AND proximityExpr )*
 * proximityExpr := notExpr ( (NEAR | FOLLOWEDBY) notExpr )*      [left-associative]
 * notExpr       := NOT? atom
 * atom          := '(' orExpr ')' | QUOTED_PHRASE | wordOrPhrase
 * wordOrPhrase  := WORD+                                          [greedy]
 * </pre>
 *
 * <p>Because every level of this grammar recurses into {@code atom}, and
 * {@code atom}'s parenthesised form recurses straight back into
 * {@code orExpr}, parentheses are resolved at whatever depth they actually
 * appear at — there is no separate "strip the outer parens first" pass to
 * get subtly wrong for multiply-nested or redundant wrapping. A term like
 * {@code (((me) OR (cking)))} is handled by ordinary recursion: the outer
 * two parens are pure grouping around a single atom, so each is consumed by
 * exactly one non-branching pass through {@code atom} → {@code orExpr} →
 * ... → {@code atom} before the actual {@code (me) OR (cking)} content is reached.
 *
 * <p><b>Unwrapped multi-word phrases are accepted, not rejected</b>
 * <p>{@code wordOrPhrase} greedily consumes every consecutive {@code WORD}
 * token with no operator between them into one {@link Ast.Phrase} (or a
 * single {@link Ast.Word} if there is only one). This means a lexicon term
 * author who writes {@code bomb this place OR blow this place up} without
 * wrapping the phrases in parentheses still gets a working term — "bomb
 * this place" is treated as one literal phrase automatically, exactly as if
 * it had been written {@code (bomb this place)}. Because the greedy
 * collection stops the moment it sees a token that is NOT a bare word (an
 * operator, a parenthesis, end of input), operators like {@code OR} still
 * correctly separate one phrase from the next.
 *
 * <p><b>Chained NEAR/FOLLOWEDBY: warned, not rejected</b>
 * <p>{@code A FOLLOWEDBY{5} B FOLLOWEDBY{6} C} chains two proximity operators
 * at the same level with no explicit parentheses. This is now ACCEPTED —
 * parsed as if the author had written {@code (A FOLLOWEDBY{5} B) FOLLOWEDBY{6} C}
 * (left-associative nesting) — with a warning recorded rather than an
 * exception thrown. This service has no persisted state and therefore no way
 * to distinguish a brand-new term from one that has been in the lexicon for
 * years; existing terms using this implicit chained form must keep compiling
 * successfully. The warning exists so a CALLER that DOES have that context —
 * a lexicon management UI, for instance — can apply its own stricter policy
 * (e.g. blocking the implicit form for newly-authored terms) using the
 * warning as the signal, without this service itself needing to make that
 * call. See {@link #warnChainedProximityOperator}. Because the resulting AST
 * shape is identical to explicit nesting, this can still trigger
 * {@code PatternComplexityAnalyzer}'s rejection (or now, decomposition — see
 * {@code PatternDecomposer}) exactly as explicit nesting would.
 */
final class ExpressionParser {

    private final List<Token> tokens;
    private final String originalTerm;
    private final List<String> warnings = new ArrayList<>();
    private int currentTokenIndex = 0;

    private ExpressionParser(List<Token> tokens, String originalTerm) {
        this.tokens = tokens;
        this.originalTerm = originalTerm;
    }

    /**
     * The parsed AST, plus any non-fatal warnings accumulated while parsing (never null; may be empty).
     */
    record ParseResult(Ast ast, List<String> warnings) {
    }

    /**
     * Parses a fully-tokenized lexicon term into an {@link Ast}.
     *
     * @param tokens       token stream from {@link Tokenizer#tokenize}
     * @param originalTerm the original (preprocessed) term text, for error messages
     * @return the root AST node plus any warnings accumulated while parsing
     * @throws TranslationException on any grammatical/structural validation failure
     */
    static ParseResult parse(List<Token> tokens, String originalTerm) {
        if (tokens.isEmpty()) {
            throw new TranslationException("Lexicon term is empty: '" + originalTerm + "'.");
        }
        ExpressionParser parser = new ExpressionParser(tokens, originalTerm);
        Ast result = parser.parseOr();
        if (parser.currentTokenIndex != tokens.size()) {
            throw parser.unexpectedTokenError("after '" + parser.describe(result) + "'");
        }
        return new ParseResult(result, parser.warnings);
    }

    // ── Grammar levels ───────────────────────────────────────────────────────

    private Ast parseOr() {
        List<Ast> alternatives = new ArrayList<>();
        alternatives.add(parseAndNot());
        while (peekIs(Token.Or.class)) {
            advance();
            alternatives.add(parseAndNot());
        }
        return alternatives.size() == 1 ? alternatives.getFirst() : new Ast.Or(alternatives);
    }

    private Ast parseAndNot() {
        Ast requiredOperand = parseAnd();
        List<Ast> excludedOperands = new ArrayList<>();
        while (peekIs(Token.AndNot.class)) {
            advance();
            excludedOperands.add(parseAnd());
        }
        return excludedOperands.isEmpty() ? requiredOperand : new Ast.AndNot(requiredOperand, excludedOperands);
    }

    /**
     * {@code andExpr := proximityExpr ( AND proximityExpr )*}
     *
     * <p>Caps the operand count at {@link ParseContext#MAX_AND_OPERANDS} —
     * {@link PatternCodeGenerator generateAnd} expresses "all present, any
     * order, unbounded distance" by enumerating every ordering of the
     * operands (N! permutations), so operand count directly controls
     * pattern size; beyond the ceiling the resulting pattern reliably fails
     * Hyperscan compilation with "Pattern Too Long", the same class of
     * failure checkForChainedProximityOperator prevents for
     * chained NEAR/FOLLOWEDBY.
     */
    private Ast parseAnd() {
        List<Ast> operands = new ArrayList<>();
        operands.add(parseProximity());
        while (peekIs(Token.And.class)) {
            advance();
            operands.add(parseProximity());
        }
        if (operands.size() > ParseContext.MAX_AND_OPERANDS) {
            throw new TranslationException(
                    "Too many AND operands at the same level (" + operands.size() + ") in term: '"
                            + originalTerm + "'. A maximum of " + ParseContext.MAX_AND_OPERANDS + " is supported"
                            + " — each additional operand multiplies the size of the resulting Hyperscan pattern."
                            + " Split this term into multiple simpler lexicon terms instead.");
        }
        return operands.size() == 1 ? operands.getFirst() : new Ast.And(operands);
    }

    /**
     * {@code proximityExpr := notLevel ( (NEAR | FOLLOWEDBY) notLevel )*}
     *
     * <p>Grammatically {@code *} (zero or more), so chained proximity
     * operators at the same level parse successfully — see class Javadoc's
     * "Chained NEAR/FOLLOWEDBY" section for why this is now accepted (with a
     * warning) rather than rejected. Each additional chained operator wraps
     * the ACCUMULATED result so far as its left operand — i.e.
     * {@code A FOLLOWEDBY{5} B FOLLOWEDBY{6} C} builds exactly the same AST
     * shape as the explicitly-nested {@code (A FOLLOWEDBY{5} B) FOLLOWEDBY{6} C}.
     * A term written with explicit parentheses is unaffected either way: the
     * inner parenthesised group is still parsed at its own, separate level,
     * so it never triggers the chained-operator warning in the first place.
     */
    private Ast parseProximity() {
        Ast leftOperand = parseAtom();
        if (!peekIsNearOrFollowedBy()) {
            return leftOperand;
        }
        Ast result = consumeProximityOperator(leftOperand);
        while (peekIsNearOrFollowedBy()) {
            warnChainedProximityOperator();
            result = consumeProximityOperator(result);
        }
        return result;
    }

    private Ast consumeProximityOperator(Ast leftOperand) {
        Token proximityToken = advance();
        Ast rightOperand = parseAtom();
        return (proximityToken instanceof Token.Near(int distance))
                ? new Ast.Near(leftOperand, rightOperand, distance)
                : new Ast.FollowedBy(leftOperand, rightOperand, ((Token.FollowedBy) proximityToken).distance());
    }

    /**
     * Records a non-fatal warning when a second (or subsequent) NEAR/FOLLOWEDBY
     * is found chained directly after the first, with no wrapping parentheses.
     * See class Javadoc's "Chained NEAR/FOLLOWEDBY" section for why this is a
     * warning rather than the {@link TranslationException} an earlier revision
     * threw here — existing lexicon terms already use this implicit form and
     * must keep compiling.
     */
    private void warnChainedProximityOperator() {
        Token next = tokens.get(currentTokenIndex);
        String keyword = (next instanceof Token.Near) ? LexiconOperatorKeyword.NEAR : LexiconOperatorKeyword.FOLLOWEDBY;
        warnings.add(
                "Chained " + keyword + " operators without explicit parentheses were used in term: '"
                        + originalTerm + "' (found a second " + keyword + " chained directly after the first)."
                        + " This still compiles, for backward compatibility with existing lexicon terms, and is"
                        + " treated as left-associative nesting — equivalent to wrapping the earlier operator(s)"
                        + " in parentheses explicitly, e.g. 'A " + keyword + "{n} B " + keyword + "{m} C' is treated"
                        + " as '(A " + keyword + "{n} B) " + keyword + "{m} C'. New terms should use explicit"
                        + " parentheses instead, both for clarity and because this implicit form may be rejected"
                        + " for newly-created terms in a future version.");
    }

    /**
     * There is no independent {@code NOT} operator — {@code NOT} is only
     * ever valid immediately after {@code AND}, forming a single
     * {@link Token.AndNot} token at the LEXICAL level (see {@link Tokenizer}).
     * A bare {@link Token.Not} reaching here means the term used {@code NOT}
     * (or, previously, {@code !}) on its own — that is rejected with a
     * specific error rather than silently interpreted as a prefix negation,
     * since Hyperscan cannot express negation as a standalone operator
     * anyway (see {@link Ast.AndNot} class Javadoc for what {@code AND NOT}
     * actually compiles to and why it is different from a hypothetical
     * standalone {@code NOT}).
     */
    private void rejectStandaloneNot() {
        if (peekIs(Token.Not.class)) {
            throw new TranslationException(
                    "Standalone NOT is not supported in term: '" + originalTerm + "'."
                            + " NOT must always be paired with AND, written as 'X AND NOT Y'."
                            + " To match the literal word \"NOT\" instead, wrap it in quotes.");
        }
    }

    private Ast parseAtom() {
        if (currentTokenIndex >= tokens.size()) {
            throw unexpectedTokenError("expected a term");
        }
        Token currentToken = tokens.get(currentTokenIndex);
        if (currentToken instanceof Token.LParen) {
            return parseParenGroup();
        }
        if (currentToken instanceof Token.QuotedPhrase(String text)) {
            advance();
            return new Ast.QuotedPhrase(text);
        }
        if (currentToken instanceof Token.Word) {
            return parseWordOrPhrase();
        }
        rejectStandaloneNot(); // gives a specific error for Token.Not rather than the generic one below
        throw unexpectedTokenError("expected a term, parenthesis, or quoted phrase");
    }

    /**
     * Greedily collects one or more consecutive bare {@link Token.Word}
     * tokens (no operator between them) into a single atom — a
     * {@link Ast.Word} if there is just one, or an {@link Ast.Phrase} if
     * there are several. This is what lets a lexicon term author write
     * {@code bomb this place} without wrapping parentheses — see class Javadoc.
     */
    private Ast parseWordOrPhrase() {
        List<String> collectedWords = new ArrayList<>();
        while (currentTokenIndex < tokens.size() && tokens.get(currentTokenIndex) instanceof Token.Word(String text)) {
            collectedWords.add(text);
            currentTokenIndex++;
        }
        return collectedWords.size() == 1 ? new Ast.Word(collectedWords.getFirst()) : new Ast.Phrase(collectedWords);
    }

    /**
     * Handles {@code '(' ... ')'} as a plain grammar rule — {@code atom := '(' orExpr ')'}.
     * No look-ahead or special-casing is needed here: whatever is inside the
     * parentheses, whether it is an operator expression or a bare multi-word
     * phrase, is resolved by the normal recursive descent through
     * {@link #parseOr} down to {@link #parseWordOrPhrase}.
     */
    private Ast parseParenGroup() {
        advance(); // consume '('
        if (peekIs(Token.RParen.class)) {
            throw new TranslationException("Empty parentheses '()' are not allowed in term: '" + originalTerm + "'.");
        }
        Ast innerExpression = parseOr();
        expectClosingParen();
        return innerExpression;
    }

    // ── Look-ahead helpers ────────────────────────────────────────────────────

    private boolean peekIsNearOrFollowedBy() {
        if (currentTokenIndex >= tokens.size()) return false;
        Token token = tokens.get(currentTokenIndex);
        return token instanceof Token.Near || token instanceof Token.FollowedBy;
    }

    private boolean peekIs(Class<? extends Token> tokenType) {
        return currentTokenIndex < tokens.size() && tokenType.isInstance(tokens.get(currentTokenIndex));
    }

    private Token advance() {
        return tokens.get(currentTokenIndex++);
    }

    private void expectClosingParen() {
        if (currentTokenIndex >= tokens.size() || !(tokens.get(currentTokenIndex) instanceof Token.RParen)) {
            throw new TranslationException(
                    "Expected closing ')' in term: '" + originalTerm + "'.");
        }
        currentTokenIndex++;
    }

    private TranslationException unexpectedTokenError(String context) {
        return new TranslationException(
                "Could not parse term: '" + originalTerm + "' (" + context + ")."
                        + " Check for unbalanced parentheses or a malformed operator.");
    }

    /**
     * Best-effort human-readable rendering of an AST node for error messages.
     */
    private String describe(Ast astNode) {
        return switch (astNode) {
            case Ast.Word word -> word.text();
            case Ast.Phrase phrase -> String.join(" ", phrase.words());
            case Ast.QuotedPhrase quotedPhrase -> "\"" + quotedPhrase.text() + "\"";
            default -> "(...)";
        };
    }
}
