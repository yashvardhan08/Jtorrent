package com.jtorrent.bittorrent.service;

import com.jtorrent.bittorrent.model.Peer;
import org.springframework.stereotype.Service;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;

@Service
public class PeerService {

    public void connectToPeer(Peer peer, byte[] infoHash, String peerId, TorrentManager manager) throws Exception {
        System.out.println("Attempting handshake with: " + peer);

        try (Socket socket = new Socket(peer.ip(), peer.port())) {
            socket.setSoTimeout(5000); // Timeout for slow peers
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

            // --- BUILD HANDSHAKE ---
            ByteBuffer buffer = ByteBuffer.allocate(68);
            buffer.put((byte) 19);                          // pstrlen
            buffer.put("BitTorrent protocol".getBytes());   // pstr
            buffer.put(new byte[8]);                        // reserved
            buffer.put(infoHash);                           // 20-byte info hash
            buffer.put(peerId.getBytes());                  // 20-byte peer ID

            out.write(buffer.array());
            out.flush();

            // --- READ HANDSHAKE RESPONSE ---
            byte[] response = new byte[68];
            in.readFully(response);
            System.out.println("Handshake successful with peer: " + peer);

            // --- SEND INTERESTED ---
            out.writeInt(1);  // message length
            out.writeByte(2); // Interested message ID
            out.flush();
            System.out.println("Sent: Interested");

            // --- WAIT FOR UNCHOKE AND PIECE INFO ---
            byte[] peerBitfield = new byte[(manager.getTotalPieces() + 7) / 8];
            boolean unchoked = false;
            boolean receivedAnyPieceInfo = false;

            while (!unchoked || !receivedAnyPieceInfo) {
                int length = in.readInt();
                if (length == 0) continue; // Keep-alive

                byte id = in.readByte();
                if (id == 5) { // BITFIELD
                    in.readFully(peerBitfield);
                    manager.registerPeerBitfield(peerBitfield);
                    receivedAnyPieceInfo = true;
                    System.out.println("Received BITFIELD from peer.");
                } else if (id == 4) { // HAVE
                    int pieceIndex = in.readInt();
                    int byteIdx = pieceIndex / 8;
                    int bitIdx = 7 - (pieceIndex % 8);
                    peerBitfield[byteIdx] |= (1 << bitIdx);
                    manager.registerSinglePiece(pieceIndex);
                    receivedAnyPieceInfo = true;
                    System.out.println("Received HAVE for piece " + pieceIndex);
                } else if (id == 1) { // UNCHOKE
                    unchoked = true;
                    System.out.println("Peer " + peer.ip() + " has UNCHOKED us.");
                } else {
                    in.skipBytes(length - 1); // skip other messages
                }
            }

            System.out.println("Ready to start downloading from peer: " + peer);

            // --- DOWNLOAD LOOP ---
            while (true) {
                int targetPieceIndex = manager.getOptimalPieceToDownload(peerBitfield);
                if (targetPieceIndex == -1) {
                    System.out.println("Waiting for more piece announcements from " + peer.ip() + "...");
                    boolean gotNewInfo = waitForPieceInfo(in, peerBitfield, manager, peer);
                    if (!gotNewInfo) {
                        System.out.println("No more useful pieces from " + peer.ip() + ". Closing connection.");
                        break;
                    }
                    continue;
                }

                System.out.println("Starting download of piece #" + targetPieceIndex);
                try {
                    downloadPiece(targetPieceIndex, out, in, manager);
                } catch (IOException e) {
                    manager.markPieceFailed(targetPieceIndex);
                    System.out.println("Connection lost during piece " + targetPieceIndex + ": " + e.getMessage());
                    break;
                } catch (Exception e) {
                    manager.markPieceFailed(targetPieceIndex);
                    System.out.println("Piece " + targetPieceIndex + " failed: " + e.getMessage() + ". Trying next piece.");
                }
            }
        }
    }

    private boolean waitForPieceInfo(DataInputStream in, byte[] peerBitfield, TorrentManager manager, Peer peer) throws IOException {
        while (true) {
            int length = in.readInt();
            if (length == 0) continue;
            byte id = in.readByte();
            if (id == 5) {
                in.readFully(peerBitfield);
                manager.registerPeerBitfield(peerBitfield);
                System.out.println("Received BITFIELD from peer " + peer.ip());
                return true;
            } else if (id == 4) {
                int pieceIndex = in.readInt();
                int byteIdx = pieceIndex / 8;
                int bitIdx = 7 - (pieceIndex % 8);
                peerBitfield[byteIdx] |= (1 << bitIdx);
                manager.registerSinglePiece(pieceIndex);
                System.out.println("Received HAVE for piece " + pieceIndex + " from " + peer.ip());
                return true;
            } else if (id == 0) {
                System.out.println("Peer " + peer.ip() + " choked us.");
            } else {
                in.skipBytes(length - 1);
            }
        }
    }

    private void downloadPiece(int index, DataOutputStream out, DataInputStream in, TorrentManager manager) throws Exception {
        int pieceSize = manager.getPieceSize(index);
        int blockSize = 16384;
        byte[] pieceData = new byte[pieceSize];
        int totalBlocks = (pieceSize + blockSize - 1) / blockSize;
        boolean[] blockReceived = new boolean[totalBlocks];
        int uniqueBlocks = 0;
        int nextBlockToRequest = 0;
        int WINDOW_SIZE = 5;

        System.out.println("Starting download of Piece #" + index + " (" + pieceSize + " bytes, " + totalBlocks + " blocks, window=" + WINDOW_SIZE + ")");

        // Send initial window of requests
        while (nextBlockToRequest < totalBlocks && (nextBlockToRequest - uniqueBlocks) < WINDOW_SIZE) {
            int begin = nextBlockToRequest * blockSize;
            int toRequest = Math.min(blockSize, pieceSize - begin);
            out.writeInt(13);
            out.writeByte(6);
            out.writeInt(index);
            out.writeInt(begin);
            out.writeInt(toRequest);
            out.flush();
            nextBlockToRequest++;
        }

        while (uniqueBlocks < totalBlocks) {
            int msgLen = in.readInt();
            if (msgLen == 0) continue;

            byte msgId = in.readByte();

            if (msgId == 7) {
                in.readInt(); // index (discard)
                int begin = in.readInt();
                byte[] block = new byte[msgLen - 9];
                in.readFully(block);

                System.arraycopy(block, 0, pieceData, begin, block.length);
                int blockIdx = begin / blockSize;
                if (!blockReceived[blockIdx]) {
                    blockReceived[blockIdx] = true;
                    uniqueBlocks++;
                }

                // Send more requests to keep the window full
                while (nextBlockToRequest < totalBlocks && (nextBlockToRequest - uniqueBlocks) < WINDOW_SIZE) {
                    int reqBegin = nextBlockToRequest * blockSize;
                    int toRequest = Math.min(blockSize, pieceSize - reqBegin);
                    out.writeInt(13);
                    out.writeByte(6);
                    out.writeInt(index);
                    out.writeInt(reqBegin);
                    out.writeInt(toRequest);
                    out.flush();
                    nextBlockToRequest++;
                }
            } else if (msgId == 0) {
                throw new RuntimeException("Peer choked us during piece download");
            } else if (msgId == 4) {
                int pieceIndex = in.readInt();
                manager.registerSinglePiece(pieceIndex);
            } else {
                in.skipBytes(msgLen - 1);
            }
        }

        // Verify SHA-1 Hash
        byte[] expectedHash = manager.getExpectedHash(index);
        if (expectedHash != null) {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] actualHash = md.digest(pieceData);
            if (!java.util.Arrays.equals(actualHash, expectedHash)) {
                StringBuilder sb = new StringBuilder();
                sb.append("SHA-1 mismatch for piece ").append(index).append(". ");
                sb.append("Expected: ");
                for (byte b : expectedHash) sb.append(String.format("%02x", b));
                sb.append(", Actual: ");
                for (byte b : actualHash) sb.append(String.format("%02x", b));
                throw new RuntimeException(sb.toString());
            }
        }

        // Write piece to disk
        manager.writePiece(index, pieceData);
        manager.markPieceComplete(index);
    }
}