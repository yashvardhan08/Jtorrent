package com.jtorrent.bittorrent.service;

import com.jtorrent.bittorrent.BencodeParser;
import com.jtorrent.bittorrent.model.Peer;
import com.jtorrent.bittorrent.utils.PeerIdGenerator;
import org.springframework.stereotype.Service;

import java.net.HttpURLConnection;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

@Service
public class TrackerService {

    public Map<String, Object> getPeers(String announceUrl, byte[] infoHash, long fileLength) throws Exception {
        // 1. Manually encode the Info Hash for the URL
        StringBuilder hexHash = new StringBuilder();
        for (byte b : infoHash) {
            hexHash.append(String.format("%%%02x", b));
        }

        // 2. Build the URL string
        String urlString = announceUrl +
                "?info_hash=" + hexHash +
                "&peer_id=" + PeerIdGenerator.generate() +
                "&port=6881" +
                "&uploaded=0" +
                "&downloaded=0" +
                "&left=" + fileLength +
                "&compact=1";

        // 3. Use the classic HttpURLConnection
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");

        // 4. Read the raw bytes directly from the input stream
        try (java.io.InputStream in = connection.getInputStream()) {
            byte[] responseBytes = in.readAllBytes();

            // 5. Pass those bytes to your working BencodeParser
            BencodeParser parser = new BencodeParser(responseBytes);
            return (Map<String, Object>) parser.parse();
        } finally {
            connection.disconnect();
        }
    }

    public java.util.List<Peer> parseCompactPeers(Object peersBlob) {
        byte[] peers;

        // Handle both String and byte[] depending on your parser's output
        if (peersBlob instanceof String s) {
            peers = s.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        } else {
            peers = (byte[]) peersBlob;
        }

        java.util.List<Peer> peerList = new java.util.ArrayList<>();

        // Every 6 bytes = 1 Peer (4 bytes IP + 2 bytes Port)
        for (int i = 0; i <= peers.length - 6; i += 6) {
            String ip = String.format("%d.%d.%d.%d",
                    peers[i] & 0xFF,
                    peers[i + 1] & 0xFF,
                    peers[i + 2] & 0xFF,
                    peers[i + 3] & 0xFF);

            // Bit-shifting to combine two bytes into one 16-bit port number
            int port = ((peers[i + 4] & 0xFF) << 8) | (peers[i + 5] & 0xFF);

            peerList.add(new Peer(ip, port));
        }
        return peerList;
    }
}