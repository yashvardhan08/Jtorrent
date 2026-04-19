package com.jtorrent.bittorrent.utils;

import java.util.Random;

public class PeerIdGenerator {
    public static String generate() {
        // -JT = JTorrent, 0001 = version
        StringBuilder id = new StringBuilder("-JT0001-");
        Random random = new Random();
        while (id.length() < 20) {
            id.append(random.nextInt(10));
        }
        return id.toString();
    }
}