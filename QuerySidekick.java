
/*
 Authors (group members): Nathan, Dylan, Thomas, Kevin
 Email addresses of group members: kwolfe2023@my.fit.edu
 Group name: 34C
 Course: Algorithms and Data Structures
 Section: 3&4

 Description of the overall algorithm:
 - Compressed trie (Patricia-like) where each node stores a compressed edge label `prefix`
 - Each node maintains a deterministic top-5 list of full queries (`ArrayList<String>`),
   sorted by a score that combines frequency, shorter length preference, and
   depth of prefix match (using the full path prefix).
 - Preprocessing builds freq map and inserts queries into the trie, seeding top-5 along the path.
 - Guessing traverses by consuming compressed labels; if missing, we return a global fallback top-5.
 - Feedback bumps frequency, ensures the query is present, and refreshes fallback cheaply.
*/

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class QuerySidekick {

    // ---------------- Node definition ----------------
    static class Node {
        String prefix;                                 // compressed edge label for this node
        HashMap<Character, Node> children = new HashMap<>();
        ArrayList<String> top = new ArrayList<>(5);    // deterministic top-5 (sorted by score)
        boolean isLeaf = false;
        Node(String prefix) { this.prefix = prefix; }
    }

    private Node root = new Node("");
    private HashMap<String, Integer> freq = new HashMap<>();
    private String currentPrefix = "";

    private final ArrayList<String> globalTop5 = new ArrayList<>(5);
    private final HashMap<String, Boolean> inGlobalTop = new HashMap<>(); // membership cache


    // ---------------- Normalization ----------------
    private String fixQueryString(String s) {
        s = s.toLowerCase().trim();
        // correct whitespace normalization
        return s.replaceAll("\\s+", " ");
    }

    // ---------------- Preprocessing ----------------
    public void processOldQueries(String filename) throws IOException {
        BufferedReader br = new BufferedReader(new FileReader(filename));
        String line;
        while ((line = br.readLine()) != null) {
            String query = fixQueryString(line);
            if (query.length() == 0) continue;
            freq.put(query, freq.getOrDefault(query, 0) + 1);
        }
        br.close();

        // Insert in descending frequency order to seed good tops early
        ArrayList<String> keys = new ArrayList<>(freq.keySet());
        keys.sort((a, b) -> freq.get(b) - freq.get(a));

        for (String key : keys) {
            insertCompressed(key);
        }

        // Build global fallback top-5 by frequency
        for (int i = 0; i < Math.min(5, keys.size()); i++){
            globalTop5.add(keys.get(i));
        }

        inGlobalTop.clear();
        for (String s : globalTop5){
            inGlobalTop.put(s, true);
        }


        // Seed root with fallback (useful for first character guesses)
        root.top.clear();
        root.top.addAll(globalTop5);


        // NEW: compute subtree top-5 bottom-up (preprocessing only)
        finalizeTopSuggestions();

    }

    // Run AFTER all insertions to compute true subtree top-5 for every node.
    public void finalizeTopSuggestions() {
        computeSubtreeTop(root, "");
    }

    // Post-order traversal that recomputes node.top by merging children's top lists
    private void computeSubtreeTop(Node node, String pathPrefixSoFar) {
        if (node == null) return;

        String myPath = pathPrefixSoFar + node.prefix;

        // First, compute for all children (post-order)
        for (Node child : node.children.values()) {
            computeSubtreeTop(child, myPath);
        }

        // Merge candidates: take all children's top (≤ 5 each) + keep any local entries already present
        ArrayList<String> candidates = new ArrayList<>();
        // retain any locally inserted suggestions
        candidates.addAll(node.top);

        for (Node child : node.children.values()) {
            for (String s : child.top) {
                if (!candidates.contains(s)) candidates.add(s);
            }
        }

        // Score and keep the best 5 deterministically
        candidates.sort((a, b) -> Integer.compare(
                scoreQuery(myPath, b),
                scoreQuery(myPath, a)
        ));

        node.top.clear();
        for (int i = 0; i < Math.min(5, candidates.size()); i++) {
            node.top.add(candidates.get(i));
        }
    }

    // ---------------- Trie utilities ----------------
    private String longestCommonPrefix(String a, String b) {
        int minLen = Math.min(a.length(), b.length());
        int i = 0;
        while (i < minLen && a.charAt(i) == b.charAt(i)) i++;
        return a.substring(0, i);
    }

    private int commonPrefixLength(String a, String b) {
        int len = Math.min(a.length(), b.length());
        int i = 0;
        while (i < len && a.charAt(i) == b.charAt(i)) i++;
        return i;
    }

    // Path-aware scoring: freq dominant, shorter queries preferred, deeper match rewarded
    private int scoreQuery(String pathPrefix, String q) {
        int freqScore = freq.getOrDefault(q, 0) * 1000;
        int lenPenalty = - q.length();
        int matchBonus = commonPrefixLength(pathPrefix, q) * 1200;
        return freqScore + lenPenalty + matchBonus;
    }

    // Score using the LIVE typed prefix (currentPrefix) to focus ranking at guess time
    private int scoreQueryLive(String livePrefix, String q) {
        int freqScore  = freq.getOrDefault(q, 0) * 1000;
        int lenPenalty = - q.length();
        int matchBonus = commonPrefixLength(livePrefix, q) * 2000; // strong bias to deeper live match
        return freqScore + lenPenalty + matchBonus;
    }

    /**
     * Keep node.top as a sorted list (desc by score), size <= 5.
     * If q exists, we just re-sort; else insert or replace worst if better.
     */
    private void considerTop(Node node, String pathPrefix, String q) {
        int newScore = scoreQuery(pathPrefix, q);

        int idx = node.top.indexOf(q);
        if (idx < 0) {
            if (node.top.size() < 5) {
                node.top.add(q);
            } else {
                int worstIdx = 0;
                int worstScore = scoreQuery(pathPrefix, node.top.get(0));
                for (int i = 1; i < node.top.size(); i++) {
                    int s = scoreQuery(pathPrefix, node.top.get(i));
                    if (s < worstScore) { worstScore = s; worstIdx = i; }
                }
                if (newScore > worstScore) node.top.set(worstIdx, q);
            }
        }
        // sort descending by score so iteration order is deterministic
        node.top.sort((a, b) -> Integer.compare(
                scoreQuery(pathPrefix, b), scoreQuery(pathPrefix, a)
        ));
    }

    // ---------------- Insertion into compressed trie ----------------
    private void insertCompressed(String query) {
        Node node = root;
        String remaining = query;
        String pathPrefix = "";  // cumulative prefix from root to this node

        while (true) {
            // Update top-5 at current node using full path prefix
            considerTop(node, pathPrefix + node.prefix, query);

            String commonPrefix = longestCommonPrefix(node.prefix, remaining);

            if (commonPrefix.equals(node.prefix)) {
                // consume matched portion of edge label
                pathPrefix += node.prefix;
                remaining = remaining.substring(commonPrefix.length());

                if (remaining.isEmpty()) {
                    // query ends exactly here
                    node.isLeaf = true;
                    break;
                }

                char nextChar = remaining.charAt(0);
                if (!node.children.containsKey(nextChar)) {
                    // create a compressed child with the entire remaining
                    Node child = new Node(remaining);
                    child.isLeaf = true;
                    node.children.put(nextChar, child);
                    // prime child's top (it will also be considered on future traversals)
                    considerTop(child, pathPrefix, query);
                    break;
                }
                // descend
                node = node.children.get(nextChar);

            } else {
                // split current node into commonPrefix + two children
                String oldSuffix = node.prefix.substring(commonPrefix.length()); // must be > 0
                Node oldChild = new Node(oldSuffix);
                oldChild.children = node.children;
                oldChild.isLeaf = node.isLeaf;

                // rewire current node to the split point
                node.prefix = commonPrefix;
                node.children = new HashMap<>();
                node.isLeaf = false;

                // add old child under its first char (oldSuffix guaranteed non-empty)
                node.children.put(oldSuffix.charAt(0), oldChild);

                // compute new suffix for the inserted query
                String newSuffix = remaining.substring(commonPrefix.length());

                if (newSuffix.length() > 0) {
                    Node newChild = new Node(newSuffix);
                    newChild.isLeaf = true;
                    node.children.put(newSuffix.charAt(0), newChild);

                    // update top at split node and new child
                    considerTop(node, pathPrefix + node.prefix, query);
                    considerTop(newChild, pathPrefix + node.prefix, query);
                } else {
                    // the new query ends exactly at the split point (no new child)
                    node.isLeaf = true;
                    considerTop(node, pathPrefix + node.prefix, query);
                }
                break;
            }
        }
    }

    // ---------------- Guessing ----------------


    public String[] guess(char ch, int index) {
        // Normalize typed char to lower case to match your lower-cased old queries
        ch = Character.toLowerCase(ch);

        if (index == 0) currentPrefix = Character.toString(ch);
        else currentPrefix += ch;

        Node node = root;
        String remaining = currentPrefix;

        while (true) {
            if (node == null) break;
            if (!node.prefix.isEmpty()) {
                String edge = node.prefix;
                int lcp = commonPrefixLength(remaining, edge);
                if (lcp == remaining.length()) break;             // typed prefix inside edge
                else if (lcp == edge.length()) {
                    remaining = remaining.substring(edge.length());
                    if (remaining.isEmpty()) break;
                    node = node.children.get(remaining.charAt(0));
                } else { node = null; break; }
            } else {
                if (remaining.isEmpty()) break;
                node = node.children.get(remaining.charAt(0));
            }
        }

        // Build result from precomputed node.top; re-rank ≤ 5 against live prefix (cheap)
        String[] result = new String[5];
        List<String> base = (node != null && !node.top.isEmpty()) ? node.top : globalTop5;

        ArrayList<String> pool = new ArrayList<>(base); // size ≤ 5
        pool.sort((a, b) -> Integer.compare(
                scoreQueryLive(currentPrefix, b),
                scoreQueryLive(currentPrefix, a)
        ));

        for (int i = 0; i < 5; i++) result[i] = i < pool.size() ? pool.get(i) : "";
        return result;
    }



    // ---------------- Feedback ----------------

    public void feedback(boolean isCorrect, String query) {
        if (query == null || query.isEmpty()) return;
        int inc = isCorrect ? 5 : 1;
        int newFreq = freq.getOrDefault(query, 0) + inc;
        freq.put(query, newFreq);

        // Ensure the query is present in the trie (cheap split if needed)
        insertCompressed(query);

        // ---- O(1) globalTop5 maintenance ----
        boolean alreadyIn = inGlobalTop.getOrDefault(query, false);
        if (alreadyIn) {
            // Re-sort just the five to reflect the updated frequency
            globalTop5.sort((a, b) -> Integer.compare(
                    freq.getOrDefault(b, 0), freq.getOrDefault(a, 0)
            ));
        } else {
            // Check if the new query should displace the current worst of the five
            int worstIdx = -1;
            int worstFreq = Integer.MAX_VALUE;
            for (int i = 0; i < globalTop5.size(); i++) {
                int f = freq.getOrDefault(globalTop5.get(i), 0);
                if (f < worstFreq) { worstFreq = f; worstIdx = i; }
            }
            if (globalTop5.size() < 5) {
                globalTop5.add(query);
                inGlobalTop.put(query, true);
            } else if (newFreq > worstFreq) {
                String removed = globalTop5.get(worstIdx);
                inGlobalTop.remove(removed);
                globalTop5.set(worstIdx, query);
                inGlobalTop.put(query, true);
            }
            // Keep top-5 order deterministic
            globalTop5.sort((a, b) -> Integer.compare(
                    freq.getOrDefault(b, 0), freq.getOrDefault(a, 0)
            ));
        }
    }

}
