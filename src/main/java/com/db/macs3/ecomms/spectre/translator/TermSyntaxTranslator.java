package com.db.macs3.ecomms.spectre.translator;

import com.ibm.icu.text.Normalizer2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translates lexicon term descriptions into Hyperscan-compatible PCRE patterns.
 *
 * <h2>Supported operators (in precedence order — highest to lowest)</h2>
 * <table>
 * <tr><td>Atom</td><td>word, "quoted phrase", emoji, non-english, wildcard, parens</td></tr>
 * <tr><td>NEAR{n}</td><td>bidirectional proximity — A within n words of B</td></tr>
 * <tr><td>FOLLOWEDBY{n}</td><td>directional proximity — A then B within n words</td></tr>
 * <tr><td>NOT</td><td>negation — text must NOT contain A</td></tr>
 * <tr><td>AND</td><td>conjunction — text must contain both A and B</td></tr>
 * <tr><td>AND NOT</td><td>exclusion — text must contain A but not B</td></tr>
 * <tr><td>OR</td><td>alternation — text contains A or B (lowest precedence)</td></tr>
 * </table>
 *
 * <h2>Pattern examples</h2>
 * <pre>
 * (manipulate*) NEAR{5} ((price) OR (spread) OR (stock))
 *   → (?:manipulate\S*(?:\s+\S+){0,5}\s+(?:price|spread|stock)
 *       |(?:price|spread|stock)(?:\s+\S+){0,5}\s+manipulate\S*)
 *
 * don't FOLLOWEDBY{3} compliance
 *   → don't(?:\s+\S+){0,3}\s+compliance
 *
 * price AND spread AND NOT noise
 *   → (?=.*price)(?=.*spread)(?!.*noise).*  (with DOTALL flag)
 *
 * 비밀 OR 내부자 거래
 *   → (?:비밀|내부자 거래)  (with UTF8+UCP flags)
 *
 * 💰 OR 🤫
 *   → (?:\x{1F4B0}|\x{1F92B})  (with UTF8+UCP flags)
 * </pre>
 *
 * <h2>Special character handling</h2>
 * <ul>
 *   <li>Apostrophes ({@code '}) — literal, no escaping needed in PCRE</li>
 *   <li>Exclamation marks ({@code !}) — literal in PCRE; also accepted as NOT prefix</li>
 *   <li>Wildcards ({@code *}) — converted to {@code \S*} (any non-whitespace sequence)</li>
 *   <li>Emojis — converted to {@code \x{NNNN}} Unicode codepoint notation</li>
 *   <li>Non-ASCII — preserved literal with HS_FLAG_UTF8|UCP</li>
 *   <li>Leet-speak ({@code 1ns1d3r}) — treated as literal pattern (intentional)</li>
 * </ul>
 */
@Component
public final class TermSyntaxTranslator {

    private static final Logger log = LoggerFactory.getLogger(TermSyntaxTranslator.class);

    /** Detects NEAR{n} operator anywhere in a substring. */
    private static final Pattern NEAR_OP = Pattern.compile(
            "NEAR\\{(\\d+)\\}", Pattern.CASE_INSENSITIVE);

    /** Detects FOLLOWEDBY{n} operator anywhere in a substring. */
    private static final Pattern FOLLOWEDBY_OP = Pattern.compile(
            "FOLLOWEDBY\\{(\\d+)\\}", Pattern.CASE_INSENSITIVE);

    /** PCRE metacharacters (minus * and ? which get wildcard treatment). */
    private static final String PCRE_META = "\\.^$|+()[]{}<>";

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Translates one lexicon term description into a Hyperscan PCRE pattern.
     *
     * @param rawExpression the "Term Description" from the CSV/JSON input
     * @return {@link TranslationResult.Success} with pattern and flags, or
     *         {@link TranslationResult.Error} with error message
     */
    public TranslationResult translate(String rawExpression) {
        if (rawExpression == null || rawExpression.isBlank()) {
            return TranslationResult.error("Term description is null or blank");
        }
        try {
            String preprocessed = preprocess(rawExpression);
            log.debug("Translating: '{}'", preprocessed);

            ParseContext ctx    = new ParseContext();
            String       pattern = parseExpression(preprocessed, ctx);
            int          flags   = ctx.computeFlags();

            log.debug("Translated: '{}' → pattern='{}' flags={}", rawExpression, pattern, flags);
            return TranslationResult.success(pattern, flags,
                    ctx.requiresAndPostFilter(), ctx.getAndOperands());

        } catch (TranslationException te) {
            log.warn("Translation failed for '{}': {}", rawExpression, te.getMessage());
            return TranslationResult.error(te.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error translating '{}': {}", rawExpression, e.getMessage(), e);
            return TranslationResult.error("Unexpected error: " + e.getMessage());
        }
    }

    // ── Pre-processing ────────────────────────────────────────────────────────

    /**
     * Normalises the raw expression before parsing:
     * <ol>
     *   <li>Trim whitespace</li>
     *   <li>Unescape CSV double-quote encoding: {@code ""} → {@code "}</li>
     *   <li>Unicode NFC normalisation (ICU4J) for consistent multi-language handling</li>
     * </ol>
     *
     * <p>NOTE: Outer {@code "..."} wrapping is intentionally NOT stripped here.
     * {@link #translateLeaf} detects a quoted phrase (starts and ends with {@code "})
     * and runs {@link #escapeSpecialChars} on the inner content, correctly producing
     * e.g. {@code \(net\)} for the input {@code "(net)"}.
     * Stripping here would expose bare {@code (net)} to {@link #isWrappedInParens},
     * which would then silently remove the parentheses and return {@code net}.
     */
    private String preprocess(String raw) {
        String s = raw.trim();
        // CSV double-quote escaping: "" → temporary marker → "
        // This handles the RFC 4180 CSV format where "" represents a single "
        s = s.replace("\"\"", "\u0000DQ\u0000");
        s = s.replace("\u0000DQ\u0000", "\"");
        // Unicode NFC normalisation for Arabic, Hebrew, Korean, CJK consistency
        try {
            s = Normalizer2.getNFCInstance().normalize(s);
        } catch (Exception e) {
            log.debug("ICU4J normalisation skipped: {}", e.getMessage());
        }
        return s;
    }

    // ── Core recursive-descent parser ─────────────────────────────────────────

    /**
     * Parses a lexicon expression into a PCRE pattern fragment.
     *
     * <p>Operator precedence (parsed lowest first, becomes outermost structure):
     * <ol>
     *   <li>OR (lowest)</li>
     *   <li>AND NOT</li>
     *   <li>AND</li>
     *   <li>NEAR{n} / FOLLOWEDBY{n}</li>
     *   <li>NOT / ! (prefix)</li>
     *   <li>Atom: parentheses, quoted phrase, word (highest)</li>
     * </ol>
     */
    private String parseExpression(String expr, ParseContext ctx) {
        expr = expr.trim();
        if (expr.isEmpty()) {
            return "";
        }

        // ── Strip outer parentheses if they wrap the ENTIRE expression ─────────
        if (isWrappedInParens(expr)) {
            return parseExpression(expr.substring(1, expr.length() - 1).trim(), ctx);
        }

        // ── Level 1: OR ───────────────────────────────────────────────────────
        List<String> orParts = splitTopLevel(expr, "OR");
        if (orParts.size() > 1) {
            return translateOr(orParts, ctx);
        }

        // ── Level 2: AND NOT (check before AND to avoid partial match) ────────
        Optional<String[]> andNotSplit = splitOnAndNot(expr);
        if (andNotSplit.isPresent()) {
            String[] parts = andNotSplit.get();
            return translateAndNot(parts[0], parts[1], ctx);
        }

        // ── Level 3: AND ──────────────────────────────────────────────────────
        List<String> andParts = splitTopLevel(expr, "AND");
        if (andParts.size() > 1) {
            return translateAnd(andParts, ctx);
        }

        // ── Level 4: NEAR{n} / FOLLOWEDBY{n} ─────────────────────────────────
        Optional<ProximityMatch> prox = findTopLevelProximity(expr);
        if (prox.isPresent()) {
            return translateProximity(prox.get(), ctx);
        }

        // ── Level 5: NOT / ! prefix ───────────────────────────────────────────
        String upperTrimmed = expr.toUpperCase();
        if (upperTrimmed.startsWith("NOT ")) {
            return translateNot(expr.substring(4).trim(), ctx);
        }
        if (expr.startsWith("!")) {
            return translateNot(expr.substring(1).trim(), ctx);
        }

        // ── Level 6: Atom ─────────────────────────────────────────────────────
        return translateLeaf(expr, ctx);
    }

    // ── Operator translators ──────────────────────────────────────────────────

    /**
     * OR → {@code (?:A|B|C)}
     */
    private String translateOr(List<String> parts, ParseContext ctx) {
        List<String> patterns = new ArrayList<>(parts.size());
        for (String part : parts) {
            patterns.add(parseExpression(part.trim(), ctx));
        }
        return "(?:" + String.join("|", patterns) + ")";
    }

    /**
     * AND → {@code (?:A|B|C)} (OR pre-scan pattern) with DOTALL flag.
     *
     * <p>Hyperscan does NOT support variable-length positive lookaheads {@code (?=...)}.
     * The OR pattern is a valid Hyperscan pre-scan filter; {@code requiresAndPostFilter=true}
     * signals the scan engine to verify ALL individual operands match the document.
     * Each operand is stored in {@code andOperands} for the scan-time post-filter step.
     */
    private String translateAnd(List<String> parts, ParseContext ctx) {
        ctx.setHasAndOp();
        List<String> patterns = new ArrayList<>(parts.size());
        for (String part : parts) {
            String operandPat = parseExpression(part.trim(), ctx);
            patterns.add(operandPat);
            ctx.addAndOperand(operandPat);
        }
        // (?:A|B|C) — valid Hyperscan; scan engine post-filters to verify ALL match
        return "(?:" + String.join("|", patterns) + ")";
    }

    /**
     * AND NOT → returns the positive operand pattern with DOTALL flag.
     *
     * <p>Hyperscan does NOT support negative lookaheads {@code (?!...)}.
     * The positive (AND) pattern is returned as the Hyperscan scan expression;
     * {@code requiresAndPostFilter=true} signals the scan engine to additionally
     * verify the NOT operand does NOT match (applied as a Java-side post-filter).
     */
    private String translateAndNot(String andPart, String notPart, ParseContext ctx) {
        ctx.setHasAndOp();
        String andPat = parseExpression(andPart.trim(), ctx);
        ctx.addAndOperand(andPat);
        // Parse (but do not include) the NOT operand — validates syntax and accumulates flags
        parseExpression(notPart.trim(), ctx);
        return andPat;
    }

    /**
     * NOT / ! → returns the operand pattern with DOTALL flag.
     *
     * <p>Hyperscan does NOT support negative lookaheads {@code (?!...)}.
     * The operand pattern is returned as the Hyperscan scan expression;
     * {@code requiresAndPostFilter=true} signals the scan engine to invert
     * the match result (a Hyperscan hit means the document should be EXCLUDED).
     */
    private String translateNot(String expr, ParseContext ctx) {
        ctx.setHasAndOp();
        // Return the operand pattern; scan engine inverts match (hit → NOT a compliance event)
        return parseExpression(expr, ctx);
    }

    /**
     * NEAR{n}  → {@code (?:LEFT(?:\s+\S+){0,n}\s+RIGHT|RIGHT(?:\s+\S+){0,n}\s+LEFT)}
     * FOLLOWEDBY{n} → {@code LEFT(?:\s+\S+){0,n}\s+RIGHT}
     *
     * <p>The gap pattern {@code (?:\s+\S+){0,n}\s+} matches 0 to n intervening words
     * separated by whitespace. Works for space-delimited languages.
     * For character-based languages (Chinese, Japanese) NEAR is approximate.
     */
    private String translateProximity(ProximityMatch prox, ParseContext ctx) {
        String leftPat  = parseExpression(prox.left(),  ctx);
        String rightPat = parseExpression(prox.right(), ctx);
        int    n        = prox.distance();
        // Gap between the two terms: 0 to n intervening words
        String gap = "(?:\\s+\\S+){0," + n + "}\\s+";

        if (prox.bidirectional()) {
            // NEAR: A then B, or B then A
            return "(?:" + leftPat + gap + rightPat
                    + "|" + rightPat + gap + leftPat + ")";
        } else {
            // FOLLOWEDBY: A then B only (directional)
            return leftPat + gap + rightPat;
        }
    }

    // ── Leaf / atom translators ───────────────────────────────────────────────

    /**
     * Translates an atomic term (word, quoted phrase, emoji, non-English text).
     *
     * <p>Processing order:
     * <ol>
     *   <li>Quoted phrase: {@code "text"} → escape and preserve literal</li>
     *   <li>Emoji characters → {@code \x{NNNN}} notation</li>
     *   <li>Non-ASCII (non-emoji) → literal with UTF8 flag</li>
     *   <li>Wildcard ({@code *}) → {@code \S*}</li>
     *   <li>Plain word → PCRE-escape special characters</li>
     * </ol>
     */
    private String translateLeaf(String term, ParseContext ctx) {
        term = term.trim();

        // ── Quoted phrase (checked FIRST — quotes protect any inner metacharacters) ──
        // e.g. "(net)" → literal \(net\)    "[unclosed" → literal \[unclosed
        if (term.startsWith("\"") && term.endsWith("\"") && term.length() > 1) {
            String phrase = term.substring(1, term.length() - 1);
            if (hasNonAscii(phrase)) {
                ctx.setNeedsUtf8();
            }
            return escapeSpecialChars(phrase);
        }

        // ── User error: unclosed character class '[' without matching ']' ────────
        // escapeSpecialChars would silently convert '[' → '\[' (a valid Hyperscan
        // literal), masking the likely mistake. Surface it as a clear diagnostic.
        // Note: this check runs AFTER the quoted-phrase branch so that
        // "[unclosed" (with outer quotes) is correctly treated as a literal.
        if (hasUnclosedCharClass(term)) {
            throw new TranslationException(
                    "Unclosed character class '[' in term: '" + term + "'."
                    + " Either close it with ']' or wrap the term in quotes to"
                    + " match it literally, e.g. \"[" + term.substring(1) + "\".");
        }

        // ── Non-ASCII detection (emoji + multilingual) ─────────────────────────
        if (hasNonAscii(term)) {
            ctx.setNeedsUtf8();
        }

        // ── Mixed emoji + text ────────────────────────────────────────────────
        if (hasEmoji(term)) {
            return convertMixedContent(term);
        }

        // ── Non-ASCII (non-emoji): Korean, Japanese, Chinese, Arabic, etc. ────
        // Preserved as literal characters; UTF8+UCP flags enable matching.
        if (hasNonAscii(term)) {
            return escapeSpecialChars(term);
        }

        // ── Wildcard ──────────────────────────────────────────────────────────
        if (term.contains("*")) {
            return translateWildcard(term);
        }

        // ── Plain ASCII word / phrase ─────────────────────────────────────────
        return escapeSpecialChars(term);
    }

    /**
     * Translates a wildcard term.
     *
     * <ul>
     *   <li>{@code word*}  → {@code word\S*}  (trailing wildcard)</li>
     *   <li>{@code *word}  → {@code \S*word}  (leading wildcard)</li>
     *   <li>{@code wo*d}   → {@code wo\S*d}   (embedded wildcard)</li>
     *   <li>{@code *}      → {@code \S+}      (standalone wildcard)</li>
     * </ul>
     */
    private String translateWildcard(String term) {
        if (term.equals("*")) {
            return "\\S+";
        }
        // Split on * and join parts with \S*
        String[] parts = term.split("\\*", -1);
        List<String> escaped = new ArrayList<>(parts.length);
        for (String part : parts) {
            escaped.add(escapeSpecialChars(part));
        }
        return String.join("\\S*", escaped);
    }

    /**
     * Converts a term that may contain emojis mixed with other characters.
     *
     * <p>Emojis (supplementary Unicode planes ≥ U+1F300) are converted to
     * {@code \x{NNNN}} Hyperscan codepoint notation. Other characters are
     * handled as either non-ASCII literals or escaped ASCII.
     */
    private String convertMixedContent(String term) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < term.length()) {
            int cp = term.codePointAt(i);
            int charCount = Character.charCount(cp);

            if (isEmojiCodePoint(cp)) {
                // Hyperscan \x{NNNN} notation for supplementary plane characters
                sb.append(String.format("\\x{%X}", cp));
            } else if (cp > 0x7F) {
                // Non-ASCII, non-emoji: append as UTF-8 literal
                sb.appendCodePoint(cp);
            } else {
                // ASCII: escape PCRE metacharacters
                char c = (char) cp;
                if (PCRE_META.indexOf(c) >= 0) {
                    sb.append('\\');
                }
                sb.append(c);
            }
            i += charCount;
        }
        return sb.toString();
    }

    // ── Splitting utilities ───────────────────────────────────────────────────

    /**
     * Splits an expression on a top-level operator (depth=0, not inside quotes).
     *
     * <p>Case-insensitive. Operators must be surrounded by spaces: {@code A OR B}.
     * When searching for {@code AND}, skips occurrences of {@code AND NOT}.
     *
     * @param expr     the expression to split
     * @param operator the operator keyword (e.g. "OR", "AND")
     * @return list of parts; single-element list if operator not found
     */
    List<String> splitTopLevel(String expr, String operator) {
        String upperOp    = " " + operator.toUpperCase() + " ";
        String upperAndNot = " AND NOT ";
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth   = 0;
        boolean inQ = false;
        int i       = 0;

        while (i < expr.length()) {
            char c = expr.charAt(i);
            if (c == '"') {
                inQ = !inQ;
            }
            if (!inQ) {
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                }
            }

            if (!inQ && depth == 0) {
                String remaining = expr.substring(i).toUpperCase();

                // When looking for AND, skip AND NOT occurrences
                if ("AND".equalsIgnoreCase(operator) && remaining.startsWith(upperAndNot)) {
                    current.append(expr, i, i + upperAndNot.length());
                    i += upperAndNot.length();
                    continue;
                }

                if (remaining.startsWith(upperOp)) {
                    String part = current.toString().trim();
                    if (!part.isEmpty()) {
                        parts.add(part);
                    }
                    current = new StringBuilder();
                    i += upperOp.length();
                    continue;
                }
            }

            current.append(c);
            i++;
        }

        String last = current.toString().trim();
        if (!last.isEmpty()) {
            parts.add(last);
        }

        return parts.size() > 1 ? parts : List.of(expr);
    }

    /**
     * Finds the first top-level {@code AND NOT} split.
     *
     * @return Optional containing [andPart, notPart], or empty if not found
     */
    Optional<String[]> splitOnAndNot(String expr) {
        int depth   = 0;
        boolean inQ = false;
        String upper = expr.toUpperCase();

        for (int i = 0; i < upper.length(); i++) {
            char c = expr.charAt(i);
            if (c == '"') {
                inQ = !inQ;
            }
            if (!inQ) {
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                }
            }
            if (!inQ && depth == 0 && upper.startsWith(" AND NOT ", i)) {
                String left  = expr.substring(0, i).trim();
                String right = expr.substring(i + " AND NOT ".length()).trim();
                if (!left.isEmpty() && !right.isEmpty()) {
                    return Optional.of(new String[]{left, right});
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Finds the first top-level NEAR{n} or FOLLOWEDBY{n} operator.
     *
     * <p>Returns the left expression, right expression, distance, and directionality.
     * Only matches at depth=0 (not inside parentheses or quotes).
     */
    Optional<ProximityMatch> findTopLevelProximity(String expr) {
        int depth   = 0;
        boolean inQ = false;

        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);
            if (c == '"') {
                inQ = !inQ;
            }
            if (!inQ) {
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                }
            }
            if (!inQ && depth == 0 && i > 0 && expr.charAt(i - 1) == ' ') {
                // Try NEAR{n}
                Matcher nearMatcher = NEAR_OP.matcher(expr.substring(i));
                if (nearMatcher.lookingAt()) {
                    int n     = Integer.parseInt(nearMatcher.group(1));
                    String left  = expr.substring(0, i - 1).trim();
                    String right = expr.substring(i + nearMatcher.end()).trim();
                    if (!left.isEmpty() && !right.isEmpty()) {
                        return Optional.of(new ProximityMatch(left, right, n, true));
                    }
                }
                // Try FOLLOWEDBY{n}
                Matcher fbMatcher = FOLLOWEDBY_OP.matcher(expr.substring(i));
                if (fbMatcher.lookingAt()) {
                    int n     = Integer.parseInt(fbMatcher.group(1));
                    String left  = expr.substring(0, i - 1).trim();
                    String right = expr.substring(i + fbMatcher.end()).trim();
                    if (!left.isEmpty() && !right.isEmpty()) {
                        return Optional.of(new ProximityMatch(left, right, n, false));
                    }
                }
            }
        }
        return Optional.empty();
    }

    // ── Character-level helpers ───────────────────────────────────────────────

    /**
     * Returns true if the expression starts with {@code (} and that opening
     * parenthesis has its matching {@code )} at the very end of the string.
     * Respects quoted strings (ignores parens inside quotes).
     */
    boolean isWrappedInParens(String expr) {
        if (expr.isEmpty() || expr.charAt(0) != '(') {
            return false;
        }
        int depth   = 0;
        boolean inQ = false;
        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);
            if (c == '"') {
                inQ = !inQ;
            }
            if (!inQ) {
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                    if (depth == 0) {
                        return i == expr.length() - 1;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Escapes PCRE metacharacters in a literal string.
     *
     * <p>Metacharacters escaped: {@code \ . ^ $ | + ( ) [ ] { } < >}
     * Non-ASCII characters are preserved as-is (safe with UTF8 flag).
     * Apostrophe ({@code '}) and exclamation ({@code !}) are not PCRE metacharacters.
     * Whitespace is preserved.
     */
    String escapeSpecialChars(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text.length() * 2);
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            int charCount = Character.charCount(cp);

            if (cp < 128) {
                // ASCII: check for PCRE metacharacters
                char c = (char) cp;
                if (PCRE_META.indexOf(c) >= 0) {
                    sb.append('\\');
                }
                sb.append(c);
            } else {
                // Non-ASCII: append as-is (UTF8 flag handles matching)
                sb.appendCodePoint(cp);
            }
            i += charCount;
        }
        return sb.toString();
    }

    /**
     * Returns true if the text contains any character outside ASCII (U+0000–U+007F).
     */
    boolean hasNonAscii(String text) {
        if (text == null) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 0x7F) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true if the text contains any emoji codepoint.
     *
     * <p>Covers all major emoji blocks including emoticons, symbols, transport,
     * flags, and new supplemental symbol blocks.
     */
    boolean hasEmoji(String text) {
        if (text == null) {
            return false;
        }
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (isEmojiCodePoint(cp)) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return false;
    }

    /**
     * Returns true if the Unicode codepoint belongs to an emoji block.
     *
     * <p>Covers: Emoticons (1F600-1F64F), Misc Symbols and Pictographs (1F300-1F5FF),
     * Transport (1F680-1F6FF), Supplemental Symbols (1F900-1F9FF, 1FA00-1FAFF),
     * Misc Symbols BMP (2600-27BF), Flags (1F1E0-1F1FF),
     * Variation Selectors (FE00-FE0F), Enclosed Alphanumeric (1F100-1F1FF).
     */
    boolean isEmojiCodePoint(int cp) {
        return (cp >= 0x1F600 && cp <= 0x1F64F)  // Emoticons
                || (cp >= 0x1F300 && cp <= 0x1F5FF)  // Misc Symbols & Pictographs
                || (cp >= 0x1F680 && cp <= 0x1F6FF)  // Transport & Map
                || (cp >= 0x1F700 && cp <= 0x1F77F)  // Alchemical Symbols
                || (cp >= 0x1F780 && cp <= 0x1F7FF)  // Geometric Shapes Extended
                || (cp >= 0x1F800 && cp <= 0x1F8FF)  // Supplemental Arrows-C
                || (cp >= 0x1F900 && cp <= 0x1F9FF)  // Supplemental Symbols
                || (cp >= 0x1FA00 && cp <= 0x1FA6F)  // Chess Symbols
                || (cp >= 0x1FA70 && cp <= 0x1FAFF)  // Symbols & Pictographs Extended-A
                || (cp >= 0x2600  && cp <= 0x26FF)   // Misc Symbols (BMP)
                || (cp >= 0x2700  && cp <= 0x27BF)   // Dingbats
                || (cp >= 0xFE00  && cp <= 0xFE0F)   // Variation Selectors
                || (cp >= 0x1F1E0 && cp <= 0x1F1FF)  // Enclosed Alphanumeric Supplement (Flags)
                || (cp >= 0x1F100 && cp <= 0x1F1FF); // Enclosed Alphanumeric
    }

    /**
     * Returns {@code true} when {@code text} contains a {@code [} that is never
     * closed by a matching {@code ]}, which would produce an unclosed character
     * class — a guaranteed Hyperscan compile failure.
     *
     * <p>Accounts for backslash-escaped brackets so that {@code \[} (a valid
     * escaped literal) is not mistaken for an opening character class.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code [unclosed}  → true  (opens, never closes)</li>
     *   <li>{@code [a-z]}      → false (opens and closes)</li>
     *   <li>{@code \[literal]} → false (escaped bracket, not a class opener)</li>
     *   <li>{@code no bracket} → false</li>
     * </ul>
     */
    private boolean hasUnclosedCharClass(String text) {
        boolean open = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // Skip the next character when we see a backslash escape (e.g. \[ or \])
            if (c == '\\' && i + 1 < text.length()) {
                i++;
                continue;
            }
            if (c == '[') {
                open = true;
            } else if (c == ']' && open) {
                open = false;
            }
        }
        return open;
    }

    // ── Internal exception ────────────────────────────────────────────────────

    /** Thrown when translation cannot proceed due to a logical error. */
    static final class TranslationException extends RuntimeException {
        TranslationException(String message) {
            super(message);
        }
    }
}
