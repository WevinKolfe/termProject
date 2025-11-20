/*
  Authors (group members): Nathan, Dylin, Kevin, Thomas
  Email addresses of group members: nrouse2024@my.fit.edu dirons2024@my.fit.edu kwolfe2023@my.fit.edu tdo2024@my.fit.edu
  Group name: 34C

  Course: Algorithms and Data Structures
  Section: 3

  Description of the overall algorithm: Autocomplete search engine, provide top 5 guesses for each character of a query
*/

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;

public class QuerySidekick {

    // Trie
    static class Node {
        HashMap<Character, Node> next = new HashMap<>();
        ArrayList<String> top = new ArrayList<>(5);
    }

    private Node root = new Node();
    private HashMap<String, Integer> freq = new HashMap<>();
    private Node currentNode = root;
    private String currentPrefix = "";

    // Make all my queries look the same (lowercase, equal spaces, trimmed)
    private String fixQueryString(String s) {
        s = s.toLowerCase().trim();
        return s.replaceAll("\\s+", " ");
    }

    // Takes in each line of the file, fixes str, updates freq of word, inserts to trie
    public void processOldQueries(String filename) throws IOException {
      BufferedReader br = new BufferedReader(new FileReader(filename));
      String line;

      while ((line = br.readLine()) != null) {
        String query = fixQueryString(line);
        if (query.length() == 0) {
          continue;
        }
        freq.put(query, freq.getOrDefault(query,0) + 1);
      }
      br.close();
      
      ArrayList<String> keys = new ArrayList<>(freq.keySet());
      for (String key : keys) {
        insert(key);
      }
    }

    // Insert query in the trie
    private void insert(String query) {
        Node node = root;
        for (int i = 0; i < query.length(); i++) {
            char ch = query.charAt(i);
        if (!node.next.containsKey(ch)) {
            node.next.put(ch, new Node());
        }
        node = node.next.get(ch);
            updateTop(node, query);
        }
    }

    // Top 5 suggestions
    private void updateTop(Node node, String query) {
        if (!node.top.contains(query)) {
            node.top.add(query);
        }

        // Sort by freq and length, high freq is more important, if they are equal then shorter query is picked 
        for (int i = 0; i < node.top.size(); i++) {            //This is probably slower
            for (int j = i + 1; j < node.top.size(); j++) {
                String a = node.top.get(i);
                String b = node.top.get(j);
                int scoreA = freq.get(a) * 1000 - a.length();
                int scoreB = freq.get(b) * 1000 - b.length();
                if (scoreB > scoreA) {
                    node.top.set(i, b);
                    node.top.set(j, a);
                }
            }
        }

        while (node.top.size() > 5) {
            node.top.remove(node.top.size() - 1);
        }
    }

    // Top 5 guesses for the current prefix picked
    public String[] guess(char ch, int index) {
        if (index == 0) {      // New query, move current node to child of root matching the char
            currentPrefix = Character.toString(ch);
            currentNode = root.next.get(ch);
        } else {              // Add char to prefix, go further into trie
            currentPrefix += ch;
            if (currentNode != null) {
              currentNode = currentNode.next.get(ch);
            }
        }

        String[] result = new String[5];
        if (currentNode == null) {      // No query in trie with matching prefix
          return result;
        }

        for (int i = 0; i < currentNode.top.size() && i < 5; i++) {
            result[i] = currentNode.top.get(i);
        }

        return result;        // Result holds my top 5 guesses
    }

    public void feedback(boolean isCorrect, String query) {     // Check if guess is correct
        if (query == null) {
            return;
        }
        query = fixQueryString(query);
        freq.put(query, freq.getOrDefault(query, 0) + 1);      // Increase the freq, insert to trie, so prefix nodes updates tops
        insert(query);
    }
}
