package com.jtorrent.bittorrent;

import java.util.Map;

public record TorrentMetadata(
        String announce,      // The Tracker URL
        long length,          // Size of the file in bytes
        String name,          // Name of the file
        byte[] infoHash       // SHA-1 Hash of the "info" section (Critical!)
) {}
