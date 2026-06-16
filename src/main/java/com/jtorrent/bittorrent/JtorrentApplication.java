package com.jtorrent.bittorrent;

import com.jtorrent.bittorrent.model.Peer;
import com.jtorrent.bittorrent.service.TorrentManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import com.jtorrent.bittorrent.service.TrackerService;
import com.jtorrent.bittorrent.service.PeerService;
import java.util.concurrent.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@SpringBootApplication
public class JtorrentApplication implements org.springframework.boot.CommandLineRunner{

    @Autowired
    private TrackerService trackerService;

    @Autowired
    private PeerService peerService;

    public static void main(String[] args) {
        SpringApplication.run(JtorrentApplication.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        // 1. Path to your downloaded .torrent file
//        if (args.length == 0) {
//            System.out.println("Usage: java -jar jtorrent.jar <path_to_torrent_file>");
//            return;
//        }

        Path torrentPath = Path.of("C:\\Users\\The Beast\\Desktop\\tears-of-steel.torrent");

        if (!Files.exists(torrentPath)) {
            System.out.println("Please place a .torrent file at the path specified!");
            return;
        }

        // 2. Read raw bytes
        byte[] torrentBytes = Files.readAllBytes(torrentPath);

        // 2. Parse it
        BencodeParser parser = new BencodeParser(torrentBytes);
        Map<String, Object> torrentData = (Map<String, Object>) parser.parse();

        // 3. Get the captured Info Bytes
        byte[] rawInfoBytes = parser.getInfoBytes();

        if (rawInfoBytes == null) {
            System.out.println("Failed to find 'info' dictionary!");
            return;
        }

        // 4. Calculate SHA-1 Hash
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] infoHash = md.digest(rawInfoBytes);

        // 5. Print the hash in Hex format (so we humans can read it)
        StringBuilder hexString = new StringBuilder();
        for (byte b : infoHash) {
            hexString.append(String.format("%02x", b));
        }

        // Build a resilient list of trackers from announce + announce-list
        java.util.List<String> trackers = new java.util.ArrayList<>();

        if (torrentData.containsKey("announce")) {
            byte[] announceBytes = (byte[]) torrentData.get("announce");
            trackers.add(new String(announceBytes, StandardCharsets.UTF_8));
        }

        if (torrentData.containsKey("announce-list")) {
            java.util.List<Object> tiers = (java.util.List<Object>) torrentData.get("announce-list");
            for (Object tierObj : tiers) {
                java.util.List<Object> tier = (java.util.List<Object>) tierObj;
                for (Object trackerObj : tier) {
                    String trackerUrl;
                    if (trackerObj instanceof byte[]) {
                        trackerUrl = new String((byte[]) trackerObj, StandardCharsets.UTF_8);
                    } else {
                        trackerUrl = trackerObj.toString();
                    }
                    if (!trackers.contains(trackerUrl)) {
                        trackers.add(trackerUrl);
                    }
                }
            }
        }

        System.out.println("Loaded " + trackers.size() + " trackers from torrent file.");
        System.out.println("Info Hash (Hex): " + hexString);

        String announceUrl = trackers.get(0);
        Map<String, Object> info = (Map<String, Object>) torrentData.get("info");

        // Handle both single-file and multi-file torrents
        long totalLength;
        if (info.containsKey("length")) {
            totalLength = (long) info.get("length");
        } else {
            java.util.List<Map<String, Object>> files = (java.util.List<Map<String, Object>>) info.get("files");
            totalLength = 0;
            for (Map<String, Object> fileEntry : files) {
                totalLength += (long) fileEntry.get("length");
            }
        }

        // NEW: Get piece details
        long standardPieceLength = ((Number) info.get("piece length")).longValue();
        byte[] piecesAllHashes = (byte[]) info.get("pieces");
        int totalPieces = (int) Math.ceil((double) totalLength / standardPieceLength);

        byte[] nameBytes = (byte[]) info.get("name");
        String fileName = new String(nameBytes, StandardCharsets.UTF_8);
        System.out.println("File Name: " + fileName);

        // Pre-allocate the file
        java.io.File destFile = new java.io.File(fileName);
        System.out.println("Pre-allocating file of size " + totalLength + " bytes...");
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(destFile, "rw")) {
            raf.setLength(totalLength);
        }
        System.out.println("File pre-allocated successfully.");

        // Pass total pieces, standard length, total length, pieces hashes, and destFile to the manager
        TorrentManager torrentManager = new TorrentManager(totalPieces, (int) standardPieceLength, totalLength, piecesAllHashes, destFile);

        // Logic for 'left' parameter: Total - (pieces_completed * piece_size)
        // Initially, pieces_completed is 0, so left = totalLength
        long completedBytes = torrentManager.getCompletedPiecesCount() * standardPieceLength;
        long left = totalLength - completedBytes;

        System.out.println("Tracker URL: " + announceUrl);
        System.out.println("Total Length: " + totalLength + " bytes");
        System.out.println("Remaining (left): " + left + " bytes");

        // NEW: Call the tracker!
        Map<String, Object> trackerResponse = null;
        try {
            trackerResponse = trackerService.getPeers(trackers, infoHash, left, "started");
            System.out.println("Tracker Response: " + trackerResponse);

            // If compact=1 was used, "peers" will be a byte array (Binary)
            if (trackerResponse.get("peers") instanceof String peers) {
                System.out.println("Found Peers (Binary String)!");
            }
        } catch (Exception e) {
            System.err.println("Tracker failed: " + e.getMessage());
        }

        java.util.List<Peer> peers = null;
        if (trackerResponse != null && trackerResponse.containsKey("peers")) {
            Object peersBlob = trackerResponse.get("peers");
            if (peersBlob instanceof byte[] b) {
                System.out.println("Peers blob length: " + b.length + " bytes");
            } else if (peersBlob instanceof String s) {
                System.out.println("Peers blob length (String): " + s.length());
            }
            peers = trackerService.parseCompactPeers(peersBlob);

            System.out.println("--- Found " + peers.size() + " Peers ---");

            peers.stream().limit(5).forEach(peer -> System.out.println("Discovered: " + peer));
        } else {
            System.out.println("Tracker did not return any peers yet. Will retry in the main loop.");
        }

        String myPeerId = com.jtorrent.bittorrent.utils.PeerIdGenerator.generate();
        Set<Peer> connectedPeers = ConcurrentHashMap.newKeySet();
        Map<Peer, Long> peerCooldown = new ConcurrentHashMap<>();
        ExecutorService executor = Executors.newFixedThreadPool(50);

        long lastTrackerQuery = 0;
        long trackerInterval = 60_000;
        long lastProgressLog = 0;

        while (!torrentManager.isComplete()) {
            completedBytes = (long) torrentManager.getCompletedPiecesCount() * standardPieceLength;
            left = totalLength - completedBytes;
            long now = System.currentTimeMillis();

            if (now - lastProgressLog > 30_000) {
                lastProgressLog = now;
                int pct = (int) (completedBytes * 100 / totalLength);
                System.out.println("--- Progress: " + completedBytes + " / " + totalLength + " bytes (" + pct + "%) ---");
            }

            if (peers != null) {
                for (Peer currentPeer : peers) {
                    long lastAttempt = peerCooldown.getOrDefault(currentPeer, 0L);
                    if (now - lastAttempt < 60_000) continue;
                    if (connectedPeers.add(currentPeer)) {
                        peerCooldown.put(currentPeer, now);
                        executor.submit(() -> {
                            Thread.currentThread().setName("Peer-" + currentPeer.ip());
                            try {
                                System.out.println("Connecting to: " + currentPeer);
                                peerService.connectToPeer(currentPeer, infoHash, myPeerId, torrentManager);
                            } catch (Exception e) {
                                // Peer failed - thread exits gracefully
                            } finally {
                                connectedPeers.remove(currentPeer);
                            }
                        });
                    }
                }
            }

            if (now - lastTrackerQuery > trackerInterval) {
                lastTrackerQuery = now;
                try {
                    String event = torrentManager.isComplete() ? "completed" : null;
                    Map<String, Object> freshResponse = trackerService.getPeers(trackers, infoHash, left, event);
                    if (freshResponse.containsKey("peers")) {
                        peers = trackerService.parseCompactPeers(freshResponse.get("peers"));
                        System.out.println("--- Re-queried tracker, found " + peers.size() + " peers ---");
                    }
                    if (freshResponse.containsKey("interval")) {
                        trackerInterval = Math.min(((Number) freshResponse.get("interval")).longValue() * 1000, 120_000);
                    }
                } catch (Exception e) {
                    System.err.println("Tracker re-query failed: " + e.getMessage());
                }
            }

            Thread.sleep(5_000);
        }

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);
        System.out.println("Download complete: " + fileName);

        // Notify tracker of completion
        try {
            trackerService.getPeers(trackers, infoHash, 0, "completed");
            System.out.println("Sent 'completed' event to tracker.");
        } catch (Exception e) {
            System.err.println("Failed to send completed event: " + e.getMessage());
        }
    }


}
