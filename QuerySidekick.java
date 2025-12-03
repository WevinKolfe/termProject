
/*
 Authors (group members): Nathan, Dylan, Kevin, Thomas
 Email addresses of group members: nrouse2024@my.fit.edu dirons2024@my.fit.edu kwolfe2023@my.fit.edu tdo2024@my.fit.edu
 Group name: 34C
 Course: Algorithms and Data Structures
 Section: 3
 Description of the overall algorithm:
 - Compressed-edge trie (Patricia-like): each node stores a whole edge label `label`
   instead of a single character to reduce node count and memory.
 - Every node keeps a strict top-5 list of full queries under that prefix, ranked by
   a path-aware score: frequency (dominant), depth of prefix match, then shorter length.
 - Guess-time: traverse compressed edges; if exact path is missing, use nearest-prefix
   fallback; then re-rank the ≤5 candidates against the live typed prefix; if none, global top-5.
 - Feedback: bump frequency (larger if correct) and reinsert to refresh tops along the path.
*/

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;

public class QuerySidekick {
    // ===== Compressed-edge trie =====
    static class Node {
        EdgeLabel label; // compressed edge label slice
        ArrayList<Node> children = new ArrayList<>();
        ArrayList<String> top = new ArrayList<>(5); // always kept sorted (size ≤ 5)
        Node() {}
    }

    static final class EdgeLabel {
        final String base; // original query string
        final int start;   // inclusive
        final int end;     // exclusive
        EdgeLabel(String base, int start, int end) {
            this.base = base;
            this.start = start;
            this.end = end;
        }
        int length() { return end - start; }
        char charAt(int i) { return base.charAt(start + i); }
        char first() { return base.charAt(start); }
        @Override
        public String toString() { return base.substring(start, end); }
    }

    private final Node root = new Node();
    private final HashMap<String, Integer> freq = new HashMap<>();
    private String currentPrefix = "";
    // Global fallback (top-5 most frequent overall)
    private final ArrayList<String> globalTop5 = new ArrayList<>(5);

    // ===== Normalization =====
    private String fixQueryString(String s) {
        s = s.toLowerCase().trim();
        s = s.replaceAll("\\s+", " "); // collapse runs of whitespace
        return s.intern(); // dedupe query strings in memory
    }

    // ===== Preprocessing =====
    public void processOldQueries(String filename) throws IOException {
        try (BufferedReader br = new BufferedReader(new FileReader(filename))) {
            String line;
            while ((line = br.readLine()) != null) {
                String query = fixQueryString(line);
                if (query.length() == 0) continue;
                freq.put(query, freq.getOrDefault(query, 0) + 1);
            }
        }
        // Insert queries in descending frequency order to seed tops early
        ArrayList<String> keys = new ArrayList<>(freq.keySet());
        keys.sort((a, b) -> freq.get(b) - freq.get(a));
        for (String key : keys) insertCompressed(key);
        // Prepare global fallback top-5
        for (int i = 0; i < Math.min(5, keys.size()); i++) globalTop5.add(keys.get(i));
        // Seed root so the first keystroke has strong suggestions
        root.top.clear();
        root.top.addAll(globalTop5);
    }

    // ===== LCP helpers =====
    private int lcpEdgeWithString(EdgeLabel e, String s) {
        int n = Math.min(e.length(), s.length());
        int i = 0;
        while (i < n && e.charAt(i) == s.charAt(i)) i++;
        return i;
    }

    private int lcpEdges(EdgeLabel a, EdgeLabel b) {
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) i++;
        return i;
    }

    private int commonPrefixLen(String a, String b) {
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) i++;
        return i;
    }

    // ===== Scoring =====
    private int scorePathAwareLen(int prefixLen, String q) {
        int f = freq.getOrDefault(q, 0);
        return f * 1000 + prefixLen * 1200 - q.length();
    }

    private int scoreLive(String livePrefix, String q) {
        int f = freq.getOrDefault(q, 0);
        int dep = commonPrefixLen(livePrefix, q);
        int len = q.length();
        return f * 1000 + dep * 2000 - len;
    }

    private void considerTop(Node node, int prefixLen, String query) {
        if (!node.top.contains(query)) node.top.add(query);
        node.top.sort((a, b) -> Integer.compare(
                scorePathAwareLen(prefixLen, b),
                scorePathAwareLen(prefixLen, a)
        ));
        while (node.top.size() > 5) node.top.remove(node.top.size() - 1);
    }

    // ===== Updated insertCompressed =====
    private void insertCompressed(String query) {
        Node node = root;
        int qStart = 0;
        int qEnd = query.length();
        int prefixLen = 0; // cumulative prefix length for scoring

        while (true) {
            // Case 1: No children → create new node with full remaining slice
            if (node.children.isEmpty()) {
                Node child = new Node();
                child.label = new EdgeLabel(query, qStart, qEnd);
                node.children.add(child);
                considerTop(child, prefixLen + child.label.length(), query);
                break;
            }

            // Case 2: Find best matching child by LCP
            Node match = null;
            int matchIdx = -1;
            int bestLcp = 0;
            for (int i = 0; i < node.children.size(); i++) {
                Node c = node.children.get(i);
                int lcp = lcpEdgeWithString(c.label, query.substring(qStart));
                if (lcp > bestLcp) {
                    bestLcp = lcp;
                    match = c;
                    matchIdx = i;
                }
                if (bestLcp == (qEnd - qStart) || (match != null && bestLcp == c.label.length())) break;
            }

            // Case 3: No overlap → create sibling
            if (match == null || bestLcp == 0) {
                Node sibling = new Node();
                sibling.label = new EdgeLabel(query, qStart, qEnd);
                node.children.add(sibling);
                considerTop(sibling, prefixLen + sibling.label.length(), query);
                break;
            }

            // Case 4: Full match of child label → descend
            if (bestLcp == match.label.length()) {
                prefixLen += bestLcp;
                qStart += bestLcp;
                considerTop(match, prefixLen, query);
                if (qStart == qEnd) break; // query fully consumed
                node = match;
                continue;
            }

            // Case 5: Partial overlap → split
            EdgeLabel oldLabel = match.label;
            EdgeLabel commonSlice = new EdgeLabel(oldLabel.base, oldLabel.start, oldLabel.start + bestLcp);
            EdgeLabel oldSuffix = new EdgeLabel(oldLabel.base, oldLabel.start + bestLcp, oldLabel.end);
            EdgeLabel newSuffix = new EdgeLabel(query, qStart + bestLcp, qEnd);

            Node split = new Node();
            split.label = commonSlice;

            // Reassign old child under its suffix
            match.label = oldSuffix;
            split.children.add(match);

            // Add new child for newSuffix
            Node newChild = new Node();
            newChild.label = newSuffix;
            split.children.add(newChild);

            // Replace in parent's children list
            node.children.set(matchIdx, split);

            // Update top lists
            considerTop(split, prefixLen + split.label.length(), query);
            considerTop(match, prefixLen + split.label.length() + match.label.length(), query);
            considerTop(newChild, prefixLen + split.label.length() + newChild.label.length(), query);
            break;
        }
    }

    // ===== Guess with nearest-prefix fallback and live re-rank =====
    public String[] guess(char ch, int index) {
        ch = Character.toLowerCase(ch); // normalize user input
        currentPrefix = (index == 0) ? Character.toString(ch) : (currentPrefix + ch);
        Node node = root;
        Node lastReached = root;
        String remaining = currentPrefix;

        while (true) {
            if (node == null) break;
            Node match = null;
            int bestLcp = 0;
            for (Node c : node.children) {
                int lcp = lcpEdgeWithString(c.label, remaining);
                if (lcp > bestLcp) { bestLcp = lcp; match = c; }
                if (bestLcp == remaining.length() || (match != null && bestLcp == match.label.length())) break;
            }
            if (match == null || bestLcp == 0) break; // no progress
            if (bestLcp == remaining.length()) {
                lastReached = match;
                break;
            }
            if (bestLcp == match.label.length()) {
                remaining = remaining.substring(bestLcp);
                lastReached = match;
                if (remaining.isEmpty()) break;
                node = match;
            } else {
                lastReached = match;
                break;
            }
        }

        // Prefer lastReached's top if present; else global
        ArrayList<String> src = (lastReached != null && !lastReached.top.isEmpty()) ? lastReached.top : globalTop5;
        // Live re-rank the ≤5 items (cheap)
        ArrayList<String> pool = new ArrayList<>(src);
        pool.sort((a, b) -> Integer.compare(
                scoreLive(currentPrefix, b),
                scoreLive(currentPrefix, a)
        ));
        String[] result = new String[5];
        for (int i = 0; i < 5; i++) result[i] = (i < pool.size()) ? pool.get(i) : "";
        return result;
    }

    // ===== Feedback =====
    public void feedback(boolean isCorrect, String query) {
        if (query == null) return;
        query = fixQueryString(query);
        int inc = isCorrect ? 5 : 1; // reward correct more
        freq.put(query, freq.getOrDefault(query, 0) + inc);
        insertCompressed(query); // refresh tops along the path
    }
}
