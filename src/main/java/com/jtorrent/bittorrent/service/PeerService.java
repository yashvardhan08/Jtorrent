package com.jtorrent.bittorrent.service;

import com.jtorrent.bittorrent.model.Peer;
import org.springframework.stereotype.Service;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;

@Service
public class PeerService {

    public void connectToPeer(Peer peer, byte[] infoHash, String peerId) throws Exception {
        System.out.println("Attempting handshake with: " + peer);

        try (Socket socket = new Socket(peer.ip(), peer.port())) {
            socket.setSoTimeout(5000); // Don't wait forever
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

            // --- BUILD HANDSHAKE ---
            ByteBuffer buffer = ByteBuffer.allocate(68);
            buffer.put((byte) 19);                          // Length
            buffer.put("BitTorrent protocol".getBytes());    // String
            buffer.put(new byte[8]);                        // 8 Reserved bits
            buffer.put(infoHash);                           // 20-byte Info Hash
            buffer.put(peerId.getBytes());                  // 20-byte Peer ID

            // Send it!
            out.write(buffer.array());
            out.flush();

            // --- READ RESPONSE ---
            byte[] response = new byte[68];
            in.readFully(response);

            System.out.println("Handshake successful! Received response from peer.");
            // We can verify the response hash matches our hash here
        }
    }
}