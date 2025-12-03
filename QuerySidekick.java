
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;

public class QuerySidekick {
    // ===== Compressed-edge trie =====
    static class Node {
        EdgeLabel label;
        ArrayList<Node> children = new ArrayList<>();
        ArrayList<String> top = new ArrayList<>(5);
        Node() {}
    }

    static final class EdgeLabel {
        final String base;
        final int start;
        final int end;
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

    // Prefix maps for instant lookup
    private final Map<Character, String[]> firstCharMap = new HashMap<>();
    private final Map<String, String[]> twoCharMap = new HashMap<>();

    // Global fallback
    private final String[] globalTop5 = new String[5];
    private int globalTopSize = 0;

    private String currentPrefix = "";

    // ===== Normalization =====
    private String fixQueryString(String s) {
        s = s.toLowerCase().trim();
        s = s.replaceAll("\\s+", " ");
        return s.intern();
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

        ArrayList<String> keys = new ArrayList<>(freq.keySet());
        keys.sort((a, b) -> freq.get(b) - freq.get(a));

        // Build trie and prefix maps
        for (String key : keys) {
            insertCompressed(key);
            char first = key.charAt(0);
            firstCharMap.computeIfAbsent(first, k -> new String[5]);
            insertIntoTop5(firstCharMap.get(first), key);

            if (key.length() > 1) {
                String two = key.substring(0, 2);
                twoCharMap.computeIfAbsent(two, k -> new String[5]);
                insertIntoTop5(twoCharMap.get(two), key);
            }
        }

        // Global top-5
        globalTopSize = Math.min(5, keys.size());
        for (int i = 0; i < globalTopSize; i++) globalTop5[i] = keys.get(i);

        root.top.clear();
        for (int i = 0; i < globalTopSize; i++) root.top.add(globalTop5[i]);
    }

    // Insert into fixed-size top-5 array
    private void insertIntoTop5(String[] arr, String q) {
        int s = freq.getOrDefault(q, 0);
        int size = 0;
        while (size < 5 && arr[size] != null) size++;
        int pos = 0;
        while (pos < size && freq.getOrDefault(arr[pos], 0) >= s) pos++;
        if (pos >= 5) return;
        for (int i = Math.min(4, size); i > pos; i--) arr[i] = arr[i - 1];
        arr[pos] = q;
    }

    // ===== LCP helpers =====
    private int lcpEdgeWithString(EdgeLabel e, String s) {
        int n = Math.min(e.length(), s.length());
        int i = 0;
        while (i < n && e.charAt(i) == s.charAt(i)) i++;
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
        return f * 1000 + dep * 2000 - q.length();
    }

    private void considerTop(Node node, int prefixLen, String query) {
        if (!node.top.contains(query)) node.top.add(query);
        node.top.sort((a, b) -> Integer.compare(
                scorePathAwareLen(prefixLen, b),
                scorePathAwareLen(prefixLen, a)
        ));
        while (node.top.size() > 5) node.top.remove(node.top.size() - 1);
    }

    // ===== insertCompressed =====
    private void insertCompressed(String query) {
        Node node = root;
        int qStart = 0;
        int qEnd = query.length();
        int prefixLen = 0;

        while (true) {
            if (node.children.isEmpty()) {
                Node child = new Node();
                child.label = new EdgeLabel(query, qStart, qEnd);
                node.children.add(child);
                considerTop(child, prefixLen + child.label.length(), query);
                break;
            }

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

            if (match == null || bestLcp == 0) {
                Node sibling = new Node();
                sibling.label = new EdgeLabel(query, qStart, qEnd);
                node.children.add(sibling);
                considerTop(sibling, prefixLen + sibling.label.length(), query);
                break;
            }

            if (bestLcp == match.label.length()) {
                prefixLen += bestLcp;
                qStart += bestLcp;
                considerTop(match, prefixLen, query);
                if (qStart == qEnd) break;
                node = match;
                continue;
            }

            EdgeLabel oldLabel = match.label;
            EdgeLabel commonSlice = new EdgeLabel(oldLabel.base, oldLabel.start, oldLabel.start + bestLcp);
            EdgeLabel oldSuffix = new EdgeLabel(oldLabel.base, oldLabel.start + bestLcp, oldLabel.end);
            EdgeLabel newSuffix = new EdgeLabel(query, qStart + bestLcp, qEnd);

            Node split = new Node();
            split.label = commonSlice;

            match.label = oldSuffix;
            split.children.add(match);

            Node newChild = new Node();
            newChild.label = newSuffix;
            split.children.add(newChild);

            node.children.set(matchIdx, split);

            considerTop(split, prefixLen + split.label.length(), query);
            considerTop(match, prefixLen + split.label.length() + match.label.length(), query);
            considerTop(newChild, prefixLen + split.label.length() + newChild.label.length(), query);
            break;
        }
    }

    // ===== guess with prefix maps =====
    public String[] guess(char ch, int index) {
        ch = Character.toLowerCase(ch);
        currentPrefix = (index == 0) ? Character.toString(ch) : (currentPrefix + ch);

        // Instant lookup for first char
        if (index == 0) {
            return firstCharMap.getOrDefault(ch, emptyTop5());
        }

        // Instant lookup for two chars
        if (index == 1) {
            String two = currentPrefix.substring(0, 2);
            return twoCharMap.getOrDefault(two, emptyTop5());
        }

        // For longer prefixes, use trie
        Node node = root;
        String remaining = currentPrefix;
        Node lastReached = root;

        while (true) {
            if (node == null) break;
            Node match = null;
            int bestLcp = 0;
            for (Node c : node.children) {
                int lcp = lcpEdgeWithString(c.label, remaining);
                if (lcp > bestLcp) { bestLcp = lcp; match = c; }
                if (bestLcp == remaining.length() || (match != null && bestLcp == match.label.length())) break;
            }
            if (match == null || bestLcp == 0) break;
            if (bestLcp == remaining.length()) {
                lastReached = match;
                break;
            }
            if (bestLcp == match.label.length()) {
                remaining = remaining.substring(bestLcp);
                lastReached = match;
                node = match;
            } else {
                lastReached = match;
                break;
            }
        }

        String[] out = new String[5];
        int outSize = 0;
        List<String> src = (lastReached != null && !lastReached.top.isEmpty()) ? lastReached.top : Arrays.asList(globalTop5);
        for (String q : src) {
            if (q == null) continue;
            int s = scoreLive(currentPrefix, q);
            int pos = 0;
            while (pos < outSize && scoreLive(currentPrefix, out[pos]) >= s) pos++;
            if (pos < 5) {
                for (int j = Math.min(4, outSize); j > pos; j--) out[j] = out[j - 1];
                out[pos] = q;
                if (outSize < 5) outSize++;
            }
        }
        for (int i = outSize; i < 5; i++) out[i] = "";
        return out;
    }

    private String[] emptyTop5() {
        return new String[]{"", "", "", "", ""};
    }

    // ===== Feedback =====
    public void feedback(boolean isCorrect, String query) {
        if (query == null) return;
        query = fixQueryString(query);
        int inc = isCorrect ? 5 : 1;
        freq.put(query, freq.getOrDefault(query, 0) + inc);
        insertCompressed(query);
    }
}
