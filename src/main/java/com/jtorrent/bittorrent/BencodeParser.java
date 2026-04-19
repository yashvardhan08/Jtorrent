package com.jtorrent.bittorrent;

import java.nio.charset.StandardCharsets;

public class BencodeParser {
    private final byte[] data;
    private int index = 0;

    private byte[] infoBytes; // Stores the raw bytes of the 'info' dictionary

    public byte[] getInfoBytes() {
        return infoBytes;
    }

    public BencodeParser(byte[] data) {
        this.data = data;
    }

    public Object parse() {
        if (index >= data.length) return null;

        byte current = data[index];
        if (Character.isDigit(current)) {
            return parseString();
        } else if (current == 'i') {
            return parseInteger();
        } else if (current == 'l') { // NEW: List
            return parseList();
        } else if (current == 'd') { // NEW: Dictionary
            return parseMap();
        }
        throw new RuntimeException("Unknown type: " + (char)current);
    }

    private java.util.List<Object> parseList() {
        index++; // Skip 'l'
        java.util.List<Object> list = new java.util.ArrayList<>();

        while (index < data.length && data[index] != 'e') {
            list.add(parse());
        }

        index++; // Skip 'e'
        return list;
    }

    private java.util.Map<String, Object> parseMap() {
        index++; // Skip 'd'
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();

        while (index < data.length && data[index] != 'e') {
            // 1. Parse the key. If parseString() now returns byte[], keyObj will be a byte[].
            Object keyObj = parse();

            // 2. Convert keyObj to a String so we can use it in map.put() and "info".equals()
            String key;
            if (keyObj instanceof byte[] b) {
                key = new String(b, java.nio.charset.StandardCharsets.UTF_8);
            } else {
                key = keyObj.toString();
            }

            // --- THE MAGIC HAPPENS HERE ---
            int valueStartIndex = this.index;
            Object value = parse();
            int valueEndIndex = this.index;

            if ("info".equals(key)) {
                this.infoBytes = java.util.Arrays.copyOfRange(data, valueStartIndex, valueEndIndex);
            }
            // ------------------------------

            map.put(key, value);
        }

        index++; // Skip 'e'
        return map;
    }

    private Object parseString() {
        int colonIndex = -1;
        // Find the colon that separates length from content
        for (int i = index; i < data.length; i++) {
            if (data[i] == ':') {
                colonIndex = i;
                break;
            }
        }

        // Example: "5:hello" -> length is 5
        int length = Integer.parseInt(new String(data, index, colonIndex - index));
        index = colonIndex + 1; // Move past the colon

        byte[] stringBytes = java.util.Arrays.copyOfRange(data, index, index + length);

        String result = new String(data, index, length, StandardCharsets.UTF_8);
        index += length; // Move to the end of the string content
        return stringBytes;
    }

    private Long parseInteger() {
        index++; // Skip the 'i'
        int end = -1;
        // Find the 'e' that ends the integer
        for (int i = index; i < data.length; i++) {
            if (data[i] == 'e') {
                end = i;
                break;
            }
        }

        Long result = Long.parseLong(new String(data, index, end - index));
        index = end + 1; // Move past the 'e'
        return result;
    }
    
}
