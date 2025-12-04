/**
 * Authors (group members): Nathan, Dylin, Kevin, Thomas
 * Email addresses of group members: nrouse2024@my.fit.edu, dirons2024@my.fit.edu, kwolfe2023@my.fit.edu, tdo2024@my.fit.edu
 * Group name: 34C
 *
 * Course: Algorithms and Data Structures
 * Section: 3
 *
 * Description of the overall algorithm:
 *   We implement an autocomplete “sidekick” that returns up to five suggestions
 *   after each character typed. The design combines a compressed trie
 *   with small prefix caches to achieve low latency.
 *
 *   • Preprocessing:
 *     - Read historical queries, normalize them (lowercase, collapse/truncate spaces),
 *       and count frequency.
 *     - Insert each query into a compressed trie using edge labels (substring slices)
 *       so long chains of single-child nodes are merged into one edge.
 *     - Build O(1) prefix caches for 1-, 2-, and 3-character prefixes (arrays/maps)
 *       holding the top-5 highest-frequency queries that start with those prefixes.
 *     - Compute a global top-5 list as a fallback.
 *
 *   • Guessing (per keystroke):
 *     - If the typed prefix length is 1–3, return the corresponding cache immediately.
 *     - For longer prefixes, traverse the compressed trie using longest-common-prefix
 *       comparisons on edge labels to find the best matching node.
 *     - Re-rank the node’s local top candidates with a live score:
 *         score = freq * 1000 + commonPrefixDepth * 2000 – queryLength,
 *       which prefers higher frequency, deeper prefix matches, and shorter queries.
 *
 *   • Feedback / Online learning:
 *     - When the user selects a suggestion, normalize it, then increase its frequency
 *       (+5 if the guess was correct, +1 otherwise) and reinsert to update local
 *       top-5 lists and keep caches fresh.
 *
 *   • Complexity (informal):
 *     - Build time ≈ O(N·L) for N distinct queries of average length L, with smaller
 *       constants due to edge compression and bounded top-5 maintenance.
 *     - Guess time: O(1) average for the first three characters via caches; otherwise
 *       a short compressed walk plus scoring of at most five items (effectively O(1)).
 *     - Space: fewer nodes than a naive trie for shared prefixes, plus small,
 *       bounded prefix caches and per-node top-5 arrays.
 */

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;

public class QuerySidekick {

    /**
     * Trie node for the compressed trie.
     * Holds:
     * - {@code label}: an edge label (substring slice over a base string).
     * - {@code childArray}: fixed-size array of children (26 letters + 10 digits).
     * - {@code top}: the node's local top-5 queries by frequency (ties handled by insertion order).
     */
    static class Node {
        EdgeLabel label;                  // Slice representing the edge leading to this node
        Node[] childArray = new Node[36]; // Children indexed by [a–z,0–9]
        Top5 top = new Top5();            // Local top suggestions reachable under this node
    }

    /**
     * EdgeLabel represents a slice (start..end) into a base string.
     * This lets us compress long chains of single-child nodes into one edge.
     */
    static final class EdgeLabel {
        final String base; // Base string from which we slice
        final int start;   // Inclusive start offset
        final int end;     // Exclusive end offset

        EdgeLabel(String base, int start, int end) {
            this.base = base;
            this.start = start;
            this.end = end;
        }

        /** @return length of the slice */
        int length() { return end - start; }

        /** @return character at offset i within the slice */
        char charAt(int i) { return base.charAt(start + i); }

        /** @return first character of the slice */
        char first() { return base.charAt(start); }
    }

    /**
     * Small struct holding a bounded top-5 array.
     * We insert by position, keeping it sorted by decreasing frequency.
     * (Ranking during live guess adds extra depth-based scoring.)
     */
    static final class Top5 {
        final String[] arr = new String[5];
        int size = 0; // number of occupied entries in arr
    }

    // Root of the compressed trie
    private final Node root = new Node();

    // Frequency map: query - count
    private final HashMap<String, Integer> freq = new HashMap<>();

    // Prefix caches (for immediate suggestions on first keystrokes)
    private final String[][] firstTop = new String[128][5];              // by first ASCII char
    private final Map<String, String[]> twoCharMap = new HashMap<>(4096, 0.75f);
    private final Map<String, String[]> threeCharMap = new HashMap<>(16384, 0.75f);

    // Global fallback top-5 when a node lacks local top suggestions
    private final String[] globalTop5 = new String[5];
    private int globalTopSize = 0;

    // Current live prefix (updated on each guess)
    private String currentPrefix = "";

    /**
     * Maps supported characters to child indices:
     *  - 'a'..'z' → 0..25
     *  - '0'..'9' → 26..35
     * Non-supported characters return -1 and are skipped.
     */
    private int charIndex(char c) {
        if (c >= 'a' && c <= 'z') return c - 'a';
        if (c >= '0' && c <= '9') return 26 + (c - '0');
        return -1; // unsupported char
    }

    /**
     * Normalizes a query string:
     * - to lower-case
     * - collapses all whitespace runs to a single space
     * - trims leading/trailing spaces
     * Avoids regex overhead by scanning characters.
     */
    private String fixQueryString(String s) {
        s = s.toLowerCase();
        StringBuilder sb = new StringBuilder(s.length());
        boolean inSpace = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean isSpace = (c <= ' ');
            if (isSpace) {
                // collapse consecutive spaces into a single space
                if (!inSpace) { sb.append(' '); inSpace = true; }
            } else {
                sb.append(c);
                inSpace = false;
            }
        }
        // trim leading/trailing spaces
        int start = 0, end = sb.length();
        while (start < end && sb.charAt(start) == ' ') start++;
        while (end > start && sb.charAt(end - 1) == ' ') end--;
        return (start == 0 && end == sb.length()) ? sb.toString() : sb.substring(start, end);
    }

    /**
     * Loads historical queries from a file and builds all indexes.
     * Steps:
     * 1) Normalize each query and accumulate frequency.
     * 2) Sort keys by decreasing frequency (for deterministic top-5 buildup).
     * 3) Insert each key into the compressed trie.
     * 4) Populate prefix caches for 1-, 2-, and 3-character prefixes.
     * 5) Fill globalTop5 for fallback suggestions.
     *
     * @param filename path to a text file, one query per line
     */
    public void processOldQueries(String filename) throws IOException {
        // Read and normalize historical queries; build frequency map
        try (BufferedReader br = new BufferedReader(new FileReader(filename))) {
            String line;
            while ((line = br.readLine()) != null) {
                String query = fixQueryString(line);
                if (query.length() == 0) continue;
                freq.put(query, freq.getOrDefault(query, 0) + 1);
            }
        }

        // Stable order: high-to-low frequency for compact/top-5 construction
        ArrayList<String> keys = new ArrayList<>(freq.keySet());
        keys.sort((a, b) -> freq.get(b) - freq.get(a));

        // Build compressed trie and prefix caches
        for (String key : keys) {
            insertCompressed(key);

            // First-character cache
            char first = key.charAt(0);
            if (first < 128) insertIntoTop5(firstTop[first], key);

            // Two-character cache
            if (key.length() > 1) {
                String two = key.substring(0, 2);
                twoCharMap.computeIfAbsent(two, k -> new String[5]);
                insertIntoTop5(twoCharMap.get(two), key);
            }

            // Three-character cache
            if (key.length() > 2) {
                String three = key.substring(0, 3);
                threeCharMap.computeIfAbsent(three, k -> new String[5]);
                insertIntoTop5(threeCharMap.get(three), key);
            }
        }

        // Global fallback top-5
        globalTopSize = Math.min(5, keys.size());
        for (int i = 0; i < globalTopSize; i++) globalTop5[i] = keys.get(i);

        // Also seed the root node's local top with globalTop5
        root.top.size = 0;
        for (int i = 0; i < globalTopSize; i++) root.top.arr[root.top.size++] = globalTop5[i];
    }

    /**
     * Inserts a query into a 5-slot array in decreasing frequency order.
     * If the array is not yet allocated (null), no-op.
     * @param arr top-5 array
     * @param q   query to insert
     */
    private void insertIntoTop5(String[] arr, String q) {
        if (arr == null) return;
        int s = freq.getOrDefault(q, 0);
        int size = 0;
        while (size < 5 && arr[size] != null) size++;
        int pos = 0;
        while (pos < size && freq.getOrDefault(arr[pos], 0) >= s) pos++;
        if (pos >= 5) return;
        for (int i = Math.min(4, size); i > pos; i--) arr[i] = arr[i - 1];
        arr[pos] = q;
    }

    /** @return length of common prefix of two strings */
    private int commonPrefixLen(String a, String b) {
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) i++;
        return i;
    }

    /**
     * Longest common prefix between an edge label slice and a string slice.
     *
     * @param e      edge label (slice over its base string)
     * @param s      target string
     * @param sStart starting index (inclusive) in s
     * @param sEnd   ending index (exclusive) in s
     * @return number of matching characters from the start
     */
    private int lcpEdgeWithSlice(EdgeLabel e, String s, int sStart, int sEnd) {
        int n = Math.min(e.length(), sEnd - sStart);
        int i = 0;
        while (i < n && e.base.charAt(e.start + i) == s.charAt(sStart + i)) i++;
        return i;
    }

    /**
     * Live scoring function during guessing.
     * Favors: higher frequency, deeper match with the <i>current</i> prefix,
     * and shorter queries.
     */
    private int scoreLive(String livePrefix, String q) {
        int f = freq.getOrDefault(q, 0);
        int dep = commonPrefixLen(livePrefix, q);
        return f * 1000 + dep * 2000 - q.length();
    }

    /**
     * Consider inserting {@code query} into a node's local Top5 list in decreasing
     * frequency order (no full sort). If already present, do nothing.
     */
    private void considerTop(Node node, String query) {
        int fq = freq.getOrDefault(query, 0);
        // skip if already present
        for (int i = 0; i < node.top.size; i++) {
            if (query.equals(node.top.arr[i])) return;
        }
        // find insertion position by frequency
        int pos = 0;
        while (pos < node.top.size && freq.getOrDefault(node.top.arr[pos], 0) >= fq) pos++;
        if (pos >= 5) return; // would fall beyond capacity
        int limit = Math.min(4, node.top.size);
        for (int i = limit; i > pos; i--) node.top.arr[i] = node.top.arr[i - 1];
        node.top.arr[pos] = query;
        if (node.top.size < 5) node.top.size++;
    }

    /**
     * Insert a query into the compressed trie.
     * Behavior:
     * - Skips unsupported characters.
     * - Uses longest-common-prefix (LCP) against the existing edge label.
     * - If partial match: split the edge into common slice + old suffix + new suffix.
     * - Updates per-node Top5 lists incrementally via {@link #considerTop}.
     */
    private void insertCompressed(String query) {
        Node node = root;
        int qStart = 0;
        int qEnd = query.length();
        while (true) {
            // Skip unsupported characters at the front
            while (qStart < qEnd && charIndex(query.charAt(qStart)) == -1) {
                qStart++;
            }
            if (qStart >= qEnd) break; // the remainder contains no supported chars

            int idx = charIndex(query.charAt(qStart));
            if (idx < 0) break; // safety

            // Case 1: no child on this first character → create a new leaf with full suffix
            if (node.childArray[idx] == null) {
                Node child = new Node();
                child.label = new EdgeLabel(query, qStart, qEnd);
                node.childArray[idx] = child;
                considerTop(child, query);  // update local top-5
                break;
            }

            // Case 2: match an existing child; compute LCP with its edge label
            Node match = node.childArray[idx];
            int bestLcp = lcpEdgeWithSlice(match.label, query, qStart, qEnd);

            if (bestLcp == 0) {
                // Disjoint: replace existing child with a sibling leaf (keeps structure simple)
                Node sibling = new Node();
                sibling.label = new EdgeLabel(query, qStart, qEnd);
                node.childArray[idx] = sibling;
                considerTop(sibling, query);
                break;
            }

            if (bestLcp == match.label.length()) {
                // The entire edge label matches → advance along this edge
                qStart += bestLcp;
                considerTop(match, query);
                if (qStart == qEnd) break; // query fully inserted
                node = match;               // descend
                continue;
            }

            // Case 3: partial match → split the edge into (commonSlice → oldSuffix & newSuffix)
            EdgeLabel oldLabel = match.label;
            EdgeLabel commonSlice = new EdgeLabel(oldLabel.base, oldLabel.start, oldLabel.start + bestLcp);
            EdgeLabel oldSuffix  = new EdgeLabel(oldLabel.base, oldLabel.start + bestLcp, oldLabel.end);
            EdgeLabel newSuffix  = new EdgeLabel(query, qStart + bestLcp, qEnd);

            Node split = new Node();
            split.label = commonSlice;            // new parent holds common prefix slice

            // reattach the old child with its suffix under the split
            match.label = oldSuffix;
            int oldIdx = charIndex(match.label.first());
            if (oldIdx >= 0) split.childArray[oldIdx] = match;

            // attach the new suffix as a child under the split
            if (newSuffix.length() > 0) {
                Node newChild = new Node();
                newChild.label = newSuffix;
                int newIdx = charIndex(newChild.label.first());
                if (newIdx >= 0) split.childArray[newIdx] = newChild;
                considerTop(newChild, query);
            }

            // Replace parent's child pointer with the split node
            node.childArray[idx] = split;
            considerTop(split, query);
            considerTop(match, query);
            break;
        }
    }

    /**
     * Produce up to 5 suggestions after a user types one character.
     *
     * @param ch    the new character typed
     * @param index index of the character within the current query (0-based)
     * @return array of 5 suggestions (may contain empty strings as placeholders)
     */
    public String[] guess(char ch, int index) {
        ch = Character.toLowerCase(ch);
        currentPrefix = (index == 0) ? Character.toString(ch) : (currentPrefix + ch);

        // Fast paths for early keystrokes via prefix caches
        if (index == 0 && ch < 128) return firstTop[ch] != null ? firstTop[ch] : emptyTop5();
        if (index == 1) return twoCharMap.getOrDefault(currentPrefix.substring(0, 2), emptyTop5());
        if (index == 2) return threeCharMap.getOrDefault(currentPrefix.substring(0, 3), emptyTop5());

        // Otherwise perform a compressed walk and re-rank locally
        Node node = root;
        String remaining = currentPrefix;
        Node lastReached = root; // last node whose edge matches a prefix of remaining
        int rStart = 0, rEnd = remaining.length();

        while (true) {
            // Skip unsupported chars in the remaining slice
            while (rStart < rEnd && charIndex(remaining.charAt(rStart)) == -1) {
                rStart++;
            }
            if (rStart >= rEnd) break;

            int idx = charIndex(remaining.charAt(rStart));
            if (idx < 0 || node == null) break;

            Node match = node.childArray[idx];
            if (match == null) break;

            int lcp = lcpEdgeWithSlice(match.label, remaining, rStart, rEnd);
            if (lcp == (rEnd - rStart)) { // entire remaining matched inside this edge
                lastReached = match;
                break;
            }
            if (lcp == match.label.length()) {
                // Edge entirely matched → advance
                rStart += lcp;
                lastReached = match;
                node = match;
            } else {
                // Partial edge match → stop at this node
                lastReached = match;
                break;
            }
        }

        // Build output by re-scoring at most 5 candidates (from local top or global fallback)
        String[] out = new String[5];
        int[] outScore = new int[5];
        int outSize = 0;

        String[] srcArr = (lastReached != null && lastReached.top.size > 0) ? lastReached.top.arr : globalTop5;
        int srcSize = (lastReached != null && lastReached.top.size > 0) ? lastReached.top.size : globalTopSize;

        for (int i = 0; i < srcSize; i++) {
            String q = srcArr[i];
            if (q == null) continue;
            int s = scoreLive(currentPrefix, q);
            int pos = 0;
            while (pos < outSize && outScore[pos] >= s) pos++;
            if (pos < 5) {
                for (int j = Math.min(4, outSize); j > pos; j--) {
                    out[j] = out[j - 1];
                    outScore[j] = outScore[j - 1];
                }
                out[pos] = q;
                outScore[pos] = s;
                if (outSize < 5) outSize++;
            }
        }

        // Pad remaining slots with empty strings
        for (int i = outSize; i < 5; i++) out[i] = "";
        return out;
    }

    /** @return a 5-element array of empty strings */
    private String[] emptyTop5() {
        return new String[]{"", "", "", "", ""};
    }

    /**
     * Feedback loop: adjust frequency and reinsert the query.
     * @param isCorrect whether the chosen suggestion was correct
     * @param query     the chosen suggestion
     */
    public void feedback(boolean isCorrect, String query) {
        if (query == null) return;
        query = fixQueryString(query);
        int inc = isCorrect ? 5 : 1;                   // stronger reinforcement if correct
        freq.put(query, freq.getOrDefault(query, 0) + inc);
        insertCompressed(query);                       // update nodes/caches incrementally
    }
}