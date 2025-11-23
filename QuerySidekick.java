
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
        String label;                         // compressed edge label for this edge
        ArrayList<Node> children = new ArrayList<>();
        ArrayList<String> top = new ArrayList<>(5); // always kept sorted (size ≤ 5)
        boolean isLeaf = false;

        Node(String label) { this.label = label; }
    }

    private final Node root = new Node("");
    private final HashMap<String, Integer> freq = new HashMap<>();
    private String currentPrefix = "";

    // Global fallback (top-5 most frequent overall)
    private final ArrayList<String> globalTop5 = new ArrayList<>(5);

    // ===== Normalization =====
    private String fixQueryString(String s) {
        s = s.toLowerCase().trim();
        s = s.replaceAll("\\s+", " ");   // collapse runs of whitespace
        return s.intern();               // dedupe query strings in memory
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

    // ===== Prefix utilities =====
    private int longestCommonPrefixLength(String a, String b) {
        int len = Math.min(a.length(), b.length());
        int i = 0;
        while (i < len && a.charAt(i) == b.charAt(i)) i++;
        return i;
    }
    private int commonPrefixLen(String a, String b) {
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) i++;
        return i;
    }

    // ===== Scoring =====
    // Path-aware score: freq dominates; deeper prefix match rewarded; shorter wins ties
    private int scorePathAware(String pathPrefix, String q) {
        int f   = freq.getOrDefault(q, 0);
        int dep = commonPrefixLen(pathPrefix, q);
        int len = q.length();
        return f * 1000 + dep * 1200 - len;
    }

    // Live score: stronger bias for the exact typed prefix
    private int scoreLive(String livePrefix, String q) {
        int f   = freq.getOrDefault(q, 0);
        int dep = commonPrefixLen(livePrefix, q);
        int len = q.length();
        return f * 1000 + dep * 2000 - len;
    }

    private void sortTopPathAware(ArrayList<String> list, String pathPrefix) {
        list.sort((a, b) -> Integer.compare(
                scorePathAware(pathPrefix, b),
                scorePathAware(pathPrefix, a)
        ));
        while (list.size() > 5) list.remove(list.size() - 1);
    }

    private void considerTop(Node node, String pathPrefix, String query) {
        if (!node.top.contains(query)) node.top.add(query);
        sortTopPathAware(node.top, pathPrefix);
    }

    // ===== Compressed insertion =====
    private void insertCompressed(String query) {
        Node node = root;
        String remaining = query;
        String pathPrefix = "";      // cumulative prefix along the path

        while (true) {
            if (node.children.isEmpty()) {
                // No children: create a child with the entire remaining
                Node child = new Node(remaining);
                child.isLeaf = true;
                node.children.add(child);
                considerTop(child, pathPrefix + child.label, query);
                break;
            }

            // Find child with best LCP with remaining
            Node match = null;
            int matchIdx = -1;
            int bestLcp = 0;
            for (int i = 0; i < node.children.size(); i++) {
                Node c = node.children.get(i);
                int lcp = longestCommonPrefixLength(remaining, c.label);
                if (lcp > bestLcp) { bestLcp = lcp; match = c; matchIdx = i; }
                if (bestLcp == remaining.length() || (match != null && bestLcp == match.label.length())) break;
            }

            if (match == null || bestLcp == 0) {
                // No overlap: create sibling with entire remaining
                Node sibling = new Node(remaining);
                sibling.isLeaf = true;
                node.children.add(sibling);
                considerTop(sibling, pathPrefix + sibling.label, query);
                break;
            }

            if (bestLcp == match.label.length()) {
                // Consume full child label and descend
                pathPrefix += match.label;               // extend path
                remaining = remaining.substring(bestLcp);
                considerTop(match, pathPrefix, query);
                if (remaining.isEmpty()) {
                    match.isLeaf = true;
                    break;
                }
                node = match;
                continue;
            }

            // Partial overlap: split child into commonPrefix + two children
            String common    = match.label.substring(0, bestLcp);
            String oldSuffix = match.label.substring(bestLcp);
            String newSuffix = remaining.substring(bestLcp);

            Node split = new Node(common);
            split.isLeaf = false;

            // Reassign old child under its suffix
            match.label = oldSuffix;          // keep match's children intact
            split.children.add(match);
            considerTop(match, pathPrefix + split.label + match.label, query);

            if (newSuffix.length() > 0) {
                Node newChild = new Node(newSuffix);
                newChild.isLeaf = true;
                split.children.add(newChild);
                considerTop(newChild, pathPrefix + split.label + newChild.label, query);
            } else {
                // New query ends at split point
                split.isLeaf = true;
            }

            // Replace in parent's children list
            node.children.set(matchIdx, split);
            considerTop(split, pathPrefix + split.label, query);
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
                int lcp = longestCommonPrefixLength(remaining, c.label);
                if (lcp > bestLcp) { bestLcp = lcp; match = c; }
                if (bestLcp == remaining.length() || (match != null && bestLcp == match.label.length())) break;
            }

            if (match == null || bestLcp == 0) break;  // no progress

            if (bestLcp == remaining.length()) {
                // Typed prefix ends inside this edge
                lastReached = match;
                break;
            }

            if (bestLcp == match.label.length()) {
                remaining = remaining.substring(bestLcp);
                lastReached = match;
                if (remaining.isEmpty()) break;
                node = match;
            } else {
                // Diverges inside edge; anchor at match
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

        // (Optional) refresh globalTop5 to reflect feedback:
        // globalTop5.clear();
        // ArrayList<String> keys = new ArrayList<>(freq.keySet());
        // keys.sort((a, b) -> freq.get(b) - freq.get(a));
        // for (int i = 0; i < Math.min(5, keys.size()); i++) globalTop5.add(keys.get(i));
    }
}
