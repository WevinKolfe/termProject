import java.util.*;
/*

  Authors (group members): Nathan, Dylan, Thomas, Kevin
  Email addresses of group members: kwolfe2023@my.fit.edu,
  Group name: 34C

  Course: Algorithms and Data Structures
  Section: 3&4

  Description of the overall algorithm:


*/

public class Trie {
    class TrieNode {
        Map<Character, TrieNode> children = new HashMap<>();
        boolean isEndOfWord = false;
        int frequency = 0;
    }
    private TrieNode root;

    public Trie() {
        root = new TrieNode();
    }

    public void insert(String word) {
        TrieNode node = root;
        for (char ch : word.toCharArray()) {
            node.children.putIfAbsent(ch, new TrieNode());
            node = node.children.get(ch);
        }
        node.isEndOfWord = true;
        node.frequency++;
    }

    public void printTrie() {
        printNode(root, "");
    }

    private void printNode(TrieNode node, String prefix) {
        if (node.isEndOfWord) {
            System.out.println(prefix);
        }
        for (Map.Entry<Character, TrieNode> entry : node.children.entrySet()) {
            printNode(entry.getValue(), prefix + entry.getKey());
        }
    }


    public TrieNode getRoot() {
        return root;
    }
}