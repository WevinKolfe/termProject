
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;

public class QuerySidekick {
    static class Node {
        EdgeLabel label;
        Node[] childArray = new Node[36]; // 26 letters + 10 digits
        Top5 top = new Top5();
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
    }

    static final class Top5 {
        final String[] arr = new String[5];
        int size = 0;
    }

    private final Node root = new Node();
    private final HashMap<String, Integer> freq = new HashMap<>();

    // Prefix maps (compacted)
    private final String[][] firstTop = new String[128][5];
    private final Map<String, String[]> twoCharMap = new HashMap<>(4096, 0.75f);
    private final Map<String, String[]> threeCharMap = new HashMap<>(16384, 0.75f);

    private final String[] globalTop5 = new String[5];
    private int globalTopSize = 0;

    private String currentPrefix = "";

    // Compute index for childArray
    private int charIndex(char c) {
        if (c >= 'a' && c <= 'z') return c - 'a';
        if (c >= '0' && c <= '9') return 26 + (c - '0');
        return -1; // unsupported char
    }

    private String fixQueryString(String s) {
        s = s.toLowerCase();
        StringBuilder sb = new StringBuilder(s.length());
        boolean inSpace = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean isSpace = (c <= ' ');
            if (isSpace) {
                if (!inSpace) { sb.append(' '); inSpace = true; }
            } else {
                sb.append(c);
                inSpace = false;
            }
        }
        int start = 0, end = sb.length();
        while (start < end && sb.charAt(start) == ' ') start++;
        while (end > start && sb.charAt(end - 1) == ' ') end--;
        return (start == 0 && end == sb.length()) ? sb.toString() : sb.substring(start, end);
    }

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

        for (String key : keys) {
            insertCompressed(key);
            char first = key.charAt(0);
            if (first < 128) insertIntoTop5(firstTop[first], key);
            if (key.length() > 1) {
                String two = key.substring(0, 2);
                twoCharMap.computeIfAbsent(two, k -> new String[5]);
                insertIntoTop5(twoCharMap.get(two), key);
            }
            if (key.length() > 2) {
                String three = key.substring(0, 3);
                threeCharMap.computeIfAbsent(three, k -> new String[5]);
                insertIntoTop5(threeCharMap.get(three), key);
            }
        }
        globalTopSize = Math.min(5, keys.size());
        for (int i = 0; i < globalTopSize; i++) globalTop5[i] = keys.get(i);
        root.top.size = 0;
        for (int i = 0; i < globalTopSize; i++) root.top.arr[root.top.size++] = globalTop5[i];
    }

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

    private int commonPrefixLen(String a, String b) {
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) i++;
        return i;
    }

    private int lcpEdgeWithSlice(EdgeLabel e, String s, int sStart, int sEnd) {
        int n = Math.min(e.length(), sEnd - sStart);
        int i = 0;
        while (i < n && e.base.charAt(e.start + i) == s.charAt(sStart + i)) i++;
        return i;
    }

    private int scoreLive(String livePrefix, String q) {
        int f = freq.getOrDefault(q, 0);
        int dep = commonPrefixLen(livePrefix, q);
        return f * 1000 + dep * 2000 - q.length();
    }

    private void considerTop(Node node, String query) {
        int fq = freq.getOrDefault(query, 0);
        for (int i = 0; i < node.top.size; i++) {
            if (query.equals(node.top.arr[i])) return;
        }
        int pos = 0;
        while (pos < node.top.size && freq.getOrDefault(node.top.arr[pos], 0) >= fq) pos++;
        if (pos >= 5) return;
        int limit = Math.min(4, node.top.size);
        for (int i = limit; i > pos; i--) node.top.arr[i] = node.top.arr[i - 1];
        node.top.arr[pos] = query;
        if (node.top.size < 5) node.top.size++;
    }

    private void insertCompressed(String query) {
        Node node = root;
        int qStart = 0;
        int qEnd = query.length();


        while (true) {
            // Skip unsupported chars
            while (qStart < qEnd && charIndex(query.charAt(qStart)) == -1) {
                qStart++;
            }
            if (qStart >= qEnd) break;

            int idx = charIndex(query.charAt(qStart));
            if (idx < 0) break; // safety check

            if (node.childArray[idx] == null) {
                Node child = new Node();
                child.label = new EdgeLabel(query, qStart, qEnd);
                node.childArray[idx] = child;
                considerTop(child, query);
                break;
            }

            Node match = node.childArray[idx];
            int bestLcp = lcpEdgeWithSlice(match.label, query, qStart, qEnd);

            if (bestLcp == 0) {
                Node sibling = new Node();
                sibling.label = new EdgeLabel(query, qStart, qEnd);
                node.childArray[idx] = sibling;
                considerTop(sibling, query);
                break;
            }

            if (bestLcp == match.label.length()) {
                qStart += bestLcp;
                considerTop(match, query);
                if (qStart == qEnd) break;
                node = match;
                continue;
            }

            // Split logic
            EdgeLabel oldLabel = match.label;
            EdgeLabel commonSlice = new EdgeLabel(oldLabel.base, oldLabel.start, oldLabel.start + bestLcp);
            EdgeLabel oldSuffix = new EdgeLabel(oldLabel.base, oldLabel.start + bestLcp, oldLabel.end);
            EdgeLabel newSuffix = new EdgeLabel(query, qStart + bestLcp, qEnd);

            Node split = new Node();
            split.label = commonSlice;

            match.label = oldSuffix;
            int oldIdx = charIndex(match.label.first());
            if (oldIdx >= 0) split.childArray[oldIdx] = match;

            if (newSuffix.length() > 0) {
                Node newChild = new Node();
                newChild.label = newSuffix;
                int newIdx = charIndex(newChild.label.first());
                if (newIdx >= 0) split.childArray[newIdx] = newChild;
                considerTop(newChild, query);
            }

            node.childArray[idx] = split;
            considerTop(split, query);
            considerTop(match, query);
            break;
        }
    }

    public String[] guess(char ch, int index) {
        ch = Character.toLowerCase(ch);
        currentPrefix = (index == 0) ? Character.toString(ch) : (currentPrefix + ch);

        if (index == 0 && ch < 128) return firstTop[ch] != null ? firstTop[ch] : emptyTop5();
        if (index == 1) return twoCharMap.getOrDefault(currentPrefix.substring(0, 2), emptyTop5());
        if (index == 2) return threeCharMap.getOrDefault(currentPrefix.substring(0, 3), emptyTop5());

        Node node = root;
        String remaining = currentPrefix;
        Node lastReached = root;
        int rStart = 0, rEnd = remaining.length();


        while (true) {
            while (rStart < rEnd && charIndex(remaining.charAt(rStart)) == -1) {
                rStart++;
            }
            if (rStart >= rEnd) break;

            int idx = charIndex(remaining.charAt(rStart));
            if (idx < 0 || node == null) break;

            Node match = node.childArray[idx];
            if (match == null) break;

            int lcp = lcpEdgeWithSlice(match.label, remaining, rStart, rEnd);
            if (lcp == (rEnd - rStart)) { lastReached = match; break; }
            if (lcp == match.label.length()) {
                rStart += lcp;
                lastReached = match;
                node = match;
            } else {
                lastReached = match;
                break;
            }
        }


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
        for (int i = outSize; i < 5; i++) out[i] = "";
        return out;
    }

    private String[] emptyTop5() {
        return new String[]{"", "", "", "", ""};
    }

    public void feedback(boolean isCorrect, String query) {
        if (query == null) return;
        query = fixQueryString(query);
        int inc = isCorrect ? 5 : 1;
        freq.put(query, freq.getOrDefault(query, 0) + inc);
        insertCompressed(query);
    }
}
