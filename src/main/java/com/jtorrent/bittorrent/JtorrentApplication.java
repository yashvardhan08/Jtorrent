package com.jtorrent.bittorrent;

import com.jtorrent.bittorrent.model.Peer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import com.jtorrent.bittorrent.service.TrackerService;
import com.jtorrent.bittorrent.service.PeerService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Map;

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
        Path torrentPath = Path.of("C:\\Users\\The Beast\\Desktop\\ubuntu-24.04.4-desktop-amd64.iso.torrent");

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

        System.out.println("Tracker URL: " + torrentData.get("announce"));
        System.out.println("Info Hash (Hex): " + hexString);

        byte[] announceBytes = (byte[]) torrentData.get("announce");
        String announceUrl = new String(announceBytes, StandardCharsets.UTF_8);
        Map<String, Object> info = (Map<String, Object>) torrentData.get("info");
        long length = (long) info.get("length");

        // NEW: Call the tracker!
        Map<String, Object> trackerResponse = null;
        try {
            trackerResponse = trackerService.getPeers(announceUrl, infoHash, length);
            System.out.println("Tracker Response: " + trackerResponse);

            // If compact=1 was used, "peers" will be a byte array (Binary)
            if (trackerResponse.get("peers") instanceof String peers) {
                System.out.println("Found Peers (Binary String)!");
            }
        } catch (Exception e) {
            System.err.println("Tracker failed: " + e.getMessage());
        }

        // Inside your run method, after getting the trackerResponse:
        java.util.List<Peer> peers = null;
        if (trackerResponse.containsKey("peers")) {
            Object peersBlob = trackerResponse.get("peers");
            if (peersBlob instanceof byte[] b) {
                System.out.println("Peers blob length: " + b.length + " bytes");
            } else if (peersBlob instanceof String s) {
                System.out.println("Peers blob length (String): " + s.length());
            }
            peers = trackerService.parseCompactPeers(peersBlob);

            System.out.println("--- Found " + peers.size() + " Peers ---");

            // Print the first 5 peers to verify
            peers.stream().limit(5).forEach(peer -> System.out.println("Discovered: " + peer));
        } else {
            System.out.println("Tracker response did not contain any peers.");
        }

        if (!peers.isEmpty()) {
            String myPeerId = com.jtorrent.bittorrent.utils.PeerIdGenerator.generate();
            boolean connected = false;

            // Try the first 10 peers until one works
            for (int i = 0; i < Math.min(peers.size(), 50); i++) {
                Peer currentPeer = peers.get(i);
                try {
                    peerService.connectToPeer(currentPeer, infoHash, myPeerId);
                    connected = true;
                    break; // EXIT the loop if we get a success!
                } catch (Exception e) {
                    System.out.println("Peer " + currentPeer + " failed: " + e.getMessage());
                }
            }

            if (!connected) {
                System.out.println("Could not establish a handshake with the first 50 peers. Try increasing the limit or using a different torrent file.");
            }
        }
    }


}
