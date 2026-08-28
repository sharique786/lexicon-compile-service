package com.db.macs3.ecomms.spectre;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reference implementation of how a downstream consumer (Lexicon Scan
 * Engine / Lexicon Scanner Service) can evaluate one lexicon-compile-service
 * {@code resolvedPatterns} string against real message text — i.e. how
 * NEAR/FOLLOWEDBY/AND NOT, no longer compiled into Hyperscan regex by this
 * service, get re-applied downstream using plain {@code java.util.regex}.
 *
 * <p><b>Not consumed by lexicon-compile-service itself</b> — this class is a
 * worked example/blueprint only, kept in the test tree, so its intent and
 * limitations can be documented plainly rather than hidden inside a
 * "for reference" comment on a class this service actually depends on. See
 * {@code TermCompilationResult#resolvedPatterns()} class Javadoc for the
 * exact string contract parsed here — e.g.
 * {@code "bash FOLLOWEDBY{30} (?:fuck|fck)"} or
 * {@code "insider AND NOT ((?:wordA...) FOLLOWEDBY{2} (?:wordH...) FOLLOWEDBY{2} (?:wordO...))"}.
 *
 * <p><b>Usage</b>
 * <pre>{@code
 * Node tree = ResolvedPatternMatcher.parse(result.resolvedPatterns(), result.hyperscanFlags());
 * boolean matched = ResolvedPatternMatcher.matches(tree, messageText);
 * }</pre>
 *
 * <p><b>The evaluation model — word-index spans, not single word indices</b>
 * <p>{@link TokenProximityMatcher} (this class's own seed) assumed every
 * leaf was exactly one word, matched via {@code Pattern.matches()} against
 * one cleaned token. Real {@code regexPattern}/{@code exclusionRegex}
 * leaves are frequently multi-word phrases (e.g. {@code "the rate"},
 * {@code "fed rate move"}), so this class instead finds every occurrence of
 * a leaf pattern directly in the message text via {@code Matcher.find()},
 * then maps each match's character span onto the WORD indices it overlaps
 * (words are simply {@code \S+} runs, matching how this project's own
 * {@code MultiLanguagePatternBuilder}/{@code Tokenizer} treat "words" for
 * Natural-Language proximity). The gap between two occurrences is the
 * number of whole words strictly between them — the same definition
 * {@code NEAR{n}}/{@code FOLLOWEDBY{n}} use in the lexicon operator
 * language itself.
 *
 * <p><b>A chain of NEAR/FOLLOWEDBY is a genuine constraint-satisfaction
 * search, not a left-to-right greedy match</b>
 * <p>{@code resolvedPatterns} renders a chained proximity structure (e.g.
 * {@code (A FOLLOWEDBY{4} B) FOLLOWEDBY{4} C}) as flat text —
 * {@code "A FOLLOWEDBY{4} B FOLLOWEDBY{4} C"} — deliberately: the string
 * itself doesn't need to encode which pairs were originally nested, only
 * that every CONSECUTIVE pair in the chain must satisfy its own operator
 * and distance against ONE common, simultaneously-chosen occurrence of each
 * leaf. {@link #matchesChain} finds this via straightforward backtracking
 * over each leaf's candidate occurrences — correct and easy to follow,
 * intentionally not optimised, appropriate for a reference implementation.
 *
 * <p><b>Known simplification of the parser, documented rather than hidden</b>
 * <p>{@link #parse} recognises {@code NEAR{n}}/{@code FOLLOWEDBY{n}}/
 * {@code AND NOT (} boundaries by literal text matching at each string's
 * TOP paren-nesting level. This is safe for every string this service
 * actually produces — a leaf's own regex text never happens to contain the
 * literal substring {@code " NEAR{<digits>} "} etc., since Natural Language
 * lexicon terms only ever produce those exact keywords via the reserved,
 * exact-case {@code NEAR{n}}/{@code FOLLOWEDBY{n}}/{@code AND NOT} syntax,
 * which this service's own tokenizer already consumes as an operator
 * rather than ever emitting as literal leaf text — but this is not a
 * hardened, general-purpose PCRE parser.
 */
public final class ResolvedPatternMatcher {

    private ResolvedPatternMatcher() {
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Parses one {@code resolvedPatterns} string into an evaluable tree.
     *
     * @param resolvedPatterns the exact string from {@code TermCompilationResult#resolvedPatterns()}
     *                         (or {@code TranslationResult.Success#resolvedPattern()})
     * @param hyperscanFlags   the term's {@code hyperscanFlags} bitmask — used to decide whether
     *                         leaf patterns need {@code Pattern.UNICODE_CHARACTER_CLASS} (mirrors
     *                         {@code HS_FLAG_UTF8}, bit 32) in addition to the case-insensitivity
     *                         every leaf always needs
     */
    public static Node parse(String resolvedPatterns, int hyperscanFlags) {
        int javaFlags = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        if ((hyperscanFlags & 32) != 0) { // HS_FLAG_UTF8
            javaFlags |= Pattern.UNICODE_CHARACTER_CLASS;
        }
        return parseAndNot(resolvedPatterns.trim(), javaFlags);
    }

    /**
     * Evaluates a parsed tree against one message — {@code true} iff the
     * whole term (proximity structure, and AND NOT's required-present /
     * excluded-absent condition) is satisfied somewhere in {@code messageText}.
     */
    public static boolean matches(Node node, String messageText) {
        return switch (node) {
            case Node.Chain chain -> matchesChain(chain, messageText);
            case Node.AndNot andNot ->
                    matches(andNot.required(), messageText) && !matches(andNot.excluded(), messageText);
        };
    }

    // ── Tree shape ────────────────────────────────────────────────────────

    /**
     * A parsed {@code resolvedPatterns} tree. Exactly two shapes: a
     * (possibly length-1, i.e. no proximity at all) chain of leaves
     * connected by NEAR/FOLLOWEDBY, or one AND NOT wrapping a required chain
     * and an excluded chain (which — per this service's guarantee that AND
     * NOT is never nested — is always itself a plain {@code Chain}, never a
     * further {@code AndNot}).
     */
    public sealed interface Node {

        /**
         * @param leaves    one compiled pattern per leaf, in left-to-right term order
         * @param operators {@code "NEAR"} or {@code "FOLLOWEDBY"} between consecutive leaves —
         *                  {@code operators.size() == leaves.size() - 1}
         * @param distances the raw, un-clamped distance for each operator — same size as {@code operators}
         */
        record Chain(List<Pattern> leaves, List<String> operators, List<Integer> distances) implements Node {
        }

        record AndNot(Node required, Node excluded) implements Node {
        }
    }

    // ── Parsing ───────────────────────────────────────────────────────────

    private static final String AND_NOT_MARKER = " AND NOT (";
    private static final Pattern PROXIMITY_KEYWORD = Pattern.compile(" (NEAR|FOLLOWEDBY)\\{(\\d+)\\} ");

    private static Node parseAndNot(String text, int javaFlags) {
        int markerAt = findTopLevel(text, AND_NOT_MARKER);
        if (markerAt < 0) {
            return parseChain(text, javaFlags);
        }
        String requiredText = text.substring(0, markerAt);
        int openParenAt = markerAt + AND_NOT_MARKER.length() - 1;
        int closeParenAt = matchingCloseParen(text, openParenAt);
        String excludedText = text.substring(openParenAt + 1, closeParenAt);
        return new Node.AndNot(parseChain(requiredText, javaFlags), parseChain(excludedText, javaFlags));
    }

    private static Node.Chain parseChain(String text, int javaFlags) {
        List<String> segments = new ArrayList<>();
        List<String> operators = new ArrayList<>();
        List<Integer> distances = new ArrayList<>();

        int depth = 0;
        int segmentStart = 0;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
            if (depth == 0) {
                Matcher m = PROXIMITY_KEYWORD.matcher(text);
                m.region(i, text.length());
                if (m.lookingAt()) {
                    segments.add(text.substring(segmentStart, i));
                    operators.add(m.group(1));
                    distances.add(Integer.parseInt(m.group(2)));
                    i = m.end();
                    segmentStart = i;
                    continue;
                }
            }
            i++;
        }
        segments.add(text.substring(segmentStart));

        List<Pattern> leaves = segments.stream().map(s -> Pattern.compile(s, javaFlags)).toList();
        return new Node.Chain(leaves, operators, distances);
    }

    /**
     * The index of the first TOP-level (paren-depth 0) occurrence of
     * {@code marker} in {@code text}, or -1 if none — used to find the
     * (at most one) {@code " AND NOT ("} boundary without being confused by
     * a leaf's own parenthesised regex content.
     */
    private static int findTopLevel(String text, String marker) {
        int depth = 0;
        for (int i = 0; i <= text.length() - marker.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
            if (depth == 0 && text.startsWith(marker, i)) {
                return i;
            }
        }
        return -1;
    }

    private static int matchingCloseParen(String text, int openParenAt) {
        int depth = 0;
        for (int i = openParenAt; i < text.length(); i++) {
            if (text.charAt(i) == '(') {
                depth++;
            } else if (text.charAt(i) == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        throw new IllegalArgumentException("Unbalanced parentheses in resolvedPatterns text: " + text);
    }

    // ── Evaluation ────────────────────────────────────────────────────────

    private static boolean matchesChain(Node.Chain chain, String messageText) {
        List<int[]> words = wordSpans(messageText); // {startChar, endChar}, in message order
        List<List<Integer>> occurrenceWordIndicesPerLeaf = new ArrayList<>(chain.leaves().size());
        for (Pattern leaf : chain.leaves()) {
            List<Integer> occurrences = matchWordIndices(leaf, messageText, words);
            if (occurrences.isEmpty()) {
                return false; // this leaf never appears at all — the whole chain cannot match
            }
            occurrenceWordIndicesPerLeaf.add(occurrences);
        }
        return backtrack(occurrenceWordIndicesPerLeaf, chain.operators(), chain.distances(), 0, -1);
    }

    /**
     * Every whole-word occurrence of {@code leaf} in {@code messageText},
     * represented by the END word index it occupies (for a multi-word match,
     * the LAST word it spans) — proximity gap counting (see class Javadoc)
     * only cares about the boundary nearest the OTHER leaf in the pair, and
     * using the end index uniformly keeps the gap arithmetic in
     * {@link #backtrack} simple and symmetric for both NEAR directions.
     */
    private static List<Integer> matchWordIndices(Pattern leaf, String messageText, List<int[]> words) {
        List<Integer> indices = new ArrayList<>();
        Matcher m = leaf.matcher(messageText);
        while (m.find()) {
            int endWordIndex = wordIndexAtOrBefore(words, m.end());
            if (endWordIndex >= 0) {
                indices.add(endWordIndex);
            }
            if (m.end() == m.start()) {
                break; // guard against a zero-width match looping forever
            }
        }
        return indices;
    }

    private static List<int[]> wordSpans(String text) {
        List<int[]> spans = new ArrayList<>();
        Matcher m = Pattern.compile("\\S+").matcher(text);
        while (m.find()) {
            spans.add(new int[] {m.start(), m.end()});
        }
        return spans;
    }

    private static int wordIndexAtOrBefore(List<int[]> words, int charOffset) {
        for (int i = words.size() - 1; i >= 0; i--) {
            if (words.get(i)[0] < charOffset) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Backtracking search: choose one occurrence (a word index) for each
     * leaf, in order, such that every consecutive pair satisfies its
     * operator's constraint against the PREVIOUSLY chosen leaf's index —
     * {@code FOLLOWEDBY} requires strictly increasing indices (directional);
     * {@code NEAR} allows either direction. {@code gap} is the count of
     * whole words strictly between the two chosen indices, matching this
     * project's own {@code NEAR{n}}/{@code FOLLOWEDBY{n}} distance
     * definition exactly.
     */
    private static boolean backtrack(List<List<Integer>> occurrenceWordIndicesPerLeaf, List<String> operators,
                                      List<Integer> distances, int leafIndex, int previousChosenWordIndex) {
        if (leafIndex == occurrenceWordIndicesPerLeaf.size()) {
            return true;
        }
        for (int candidate : occurrenceWordIndicesPerLeaf.get(leafIndex)) {
            if (leafIndex == 0) {
                if (backtrack(occurrenceWordIndicesPerLeaf, operators, distances, leafIndex + 1, candidate)) {
                    return true;
                }
                continue;
            }
            String operator = operators.get(leafIndex - 1);
            int maxGap = distances.get(leafIndex - 1);
            boolean directionOk = "NEAR".equals(operator) || candidate > previousChosenWordIndex;
            int gap = Math.abs(candidate - previousChosenWordIndex) - 1;
            if (directionOk && gap >= 0 && gap <= maxGap
                    && backtrack(occurrenceWordIndicesPerLeaf, operators, distances, leafIndex + 1, candidate)) {
                return true;
            }
        }
        return false;
    }
}
