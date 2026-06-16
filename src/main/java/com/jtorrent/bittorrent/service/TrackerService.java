package com.jtorrent.bittorrent.service;

import com.jtorrent.bittorrent.BencodeParser;
import com.jtorrent.bittorrent.model.Peer;
import com.jtorrent.bittorrent.utils.PeerIdGenerator;
import org.springframework.stereotype.Service;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

@Service
public class TrackerService {

    public Map<String, Object> getPeers(List<String> trackers, byte[] infoHash, long fileLength, String event) throws Exception {
        Exception lastException = null;

        for (String url : trackers) {
            try {
                System.out.println("Attempting tracker: " + url);

                if (url.startsWith("udp://")) {
                    return getPeersUDP(url, infoHash, fileLength, event);
                } else if (url.startsWith("http://") || url.startsWith("https://")) {
                    return getPeersHTTP(url, infoHash, fileLength, event);
                } else {
                    System.out.println("Skipping unsupported tracker protocol: " + url);
                }

            } catch (Exception e) {
                System.err.println("Tracker failed (" + url + "): " + e.getMessage());
                lastException = e;
            }
        }

        throw new RuntimeException("All trackers failed to respond. Last error: " +
                (lastException != null ? lastException.getMessage() : "Unknown"));
    }

    private Map<String, Object> getPeersUDP(String announceUrl, byte[] infoHash, long fileLength, String event) throws Exception {
        URI uri = new URI(announceUrl);
        String host = uri.getHost();
        int port = uri.getPort() == -1 ? 80 : uri.getPort();

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(5000);
            InetAddress address = InetAddress.getByName(host);
            Random random = new Random();

            long magicConnectionId = 0x41727101980L;
            int connectTransactionId = random.nextInt();

            ByteBuffer connectReq = ByteBuffer.allocate(16);
            connectReq.putLong(magicConnectionId);
            connectReq.putInt(0);
            connectReq.putInt(connectTransactionId);

            DatagramPacket connectPacket = new DatagramPacket(connectReq.array(), 16, address, port);
            socket.send(connectPacket);

            byte[] connectResBuf = new byte[16];
            DatagramPacket connectResPacket = new DatagramPacket(connectResBuf, 16);
            socket.receive(connectResPacket);

            ByteBuffer connectRes = ByteBuffer.wrap(connectResBuf);
            if (connectRes.getInt() != 0 || connectRes.getInt() != connectTransactionId) {
                throw new RuntimeException("Invalid UDP connection response from tracker");
            }
            long connectionId = connectRes.getLong();

            int eventCode = 0;
            if (event != null) {
                if (event.equalsIgnoreCase("completed")) eventCode = 1;
                else if (event.equalsIgnoreCase("started")) eventCode = 2;
                else if (event.equalsIgnoreCase("stopped")) eventCode = 3;
            }

            int announceTransactionId = random.nextInt();
            ByteBuffer announceReq = ByteBuffer.allocate(98);
            announceReq.putLong(connectionId);
            announceReq.putInt(1);
            announceReq.putInt(announceTransactionId);
            announceReq.put(infoHash);
            announceReq.put(PeerIdGenerator.generate().getBytes(StandardCharsets.ISO_8859_1));
            announceReq.putLong(0);
            announceReq.putLong(fileLength);
            announceReq.putLong(0);
            announceReq.putInt(eventCode);
            announceReq.putInt(0);
            announceReq.putInt(random.nextInt());
            announceReq.putInt(50);
            announceReq.putShort((short) 6881);

            DatagramPacket announcePacket = new DatagramPacket(announceReq.array(), 98, address, port);
            socket.send(announcePacket);

            byte[] announceResBuf = new byte[4096];
            DatagramPacket announceResPacket = new DatagramPacket(announceResBuf, announceResBuf.length);
            socket.receive(announceResPacket);

            ByteBuffer announceRes = ByteBuffer.wrap(announceResBuf, 0, announceResPacket.getLength());
            if (announceRes.getInt() != 1 || announceRes.getInt() != announceTransactionId) {
                throw new RuntimeException("Invalid UDP announce response from tracker");
            }

            int interval = announceRes.getInt();
            int leechers = announceRes.getInt();
            int seeders = announceRes.getInt();

            byte[] peersBytes = new byte[announceRes.remaining()];
            announceRes.get(peersBytes);

            Map<String, Object> responseMap = new HashMap<>();
            responseMap.put("interval", interval);
            responseMap.put("peers", peersBytes);

            return responseMap;
        }
    }

    private Map<String, Object> getPeersHTTP(String announceUrl, byte[] infoHash, long fileLength, String event) throws Exception {
        StringBuilder hexHash = new StringBuilder();
        for (byte b : infoHash) {
            hexHash.append(String.format("%%%02x", b & 0xFF));
        }

        StringBuilder urlBuilder = new StringBuilder();
        urlBuilder.append(announceUrl)
                .append("?info_hash=").append(hexHash)
                .append("&peer_id=").append(PeerIdGenerator.generate())
                .append("&port=6881")
                .append("&uploaded=0")
                .append("&downloaded=0")
                .append("&left=").append(fileLength)
                .append("&compact=1")
                .append("&numwant=50");

        if (event != null && !event.isEmpty()) {
            urlBuilder.append("&event=").append(event);
        }

        URL url = new URL(urlBuilder.toString());
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");

        try (java.io.InputStream in = connection.getInputStream()) {
            byte[] responseBytes = in.readAllBytes();
            BencodeParser parser = new BencodeParser(responseBytes);
            return (Map<String, Object>) parser.parse();
        } finally {
            connection.disconnect();
        }
    }

    public java.util.List<Peer> parseCompactPeers(Object peersBlob) {
        byte[] peers;

        if (peersBlob instanceof String s) {
            peers = s.getBytes(StandardCharsets.ISO_8859_1);
        } else {
            peers = (byte[]) peersBlob;
        }

        java.util.List<Peer> peerList = new java.util.ArrayList<>();

        for (int i = 0; i <= peers.length - 6; i += 6) {
            String ip = String.format("%d.%d.%d.%d",
                    peers[i] & 0xFF,
                    peers[i + 1] & 0xFF,
                    peers[i + 2] & 0xFF,
                    peers[i + 3] & 0xFF);

            int port = ((peers[i + 4] & 0xFF) << 8) | (peers[i + 5] & 0xFF);

            peerList.add(new Peer(ip, port));
        }
        return peerList;
    }
}
