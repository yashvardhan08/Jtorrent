package com.jtorrent.bittorrent.service;

import java.util.Arrays;

public class TorrentManager {

    private final int totalPieces;
    private final int standardPieceSize;
    private final long totalLength;
    private final byte[] piecesAllHashes;
    private final TorrentFileManager fileManager;
    private final boolean[] completedPieces;
    private final int[] rarityMap;
    private final boolean[] inProgressPieces;
    private int completedCount = 0;

    public TorrentManager(int totalPieces, int standardPieceSize, long totalLength, byte[] piecesAllHashes, TorrentFileManager fileManager) {
        this.totalPieces = totalPieces;
        this.standardPieceSize = standardPieceSize;
        this.totalLength = totalLength;
        this.piecesAllHashes = piecesAllHashes;
        this.fileManager = fileManager;
        this.completedPieces = new boolean[totalPieces];
        this.rarityMap = new int[totalPieces];
        this.inProgressPieces = new boolean[totalPieces];
    }

    // Getter for the PeerService to use
    public int getStandardPieceSize() {
        return this.standardPieceSize;
    }

    // Getter for the PeerService to use
    public int getTotalPieces() {
        return this.totalPieces;
    }

    // Get the exact size of a specific piece (handles final irregular piece)
    public int getPieceSize(int pieceIndex) {
        if (pieceIndex < 0 || pieceIndex >= totalPieces) {
            return 0;
        }
        if (pieceIndex == totalPieces - 1) {
            return (int) (totalLength - (long) (totalPieces - 1) * standardPieceSize);
        }
        return standardPieceSize;
    }

    // Get the expected SHA-1 hash for a specific piece index
    public byte[] getExpectedHash(int pieceIndex) {
        if (pieceIndex < 0 || pieceIndex >= totalPieces || piecesAllHashes == null) {
            return null;
        }
        int offset = pieceIndex * 20;
        if (offset + 20 > piecesAllHashes.length) {
            return null;
        }
        return Arrays.copyOfRange(piecesAllHashes, offset, offset + 20);
    }

    public synchronized int getCompletedPiecesCount() {
        return completedCount; // O(1)
    }

    public synchronized boolean isPieceCompleted(int pieceIndex) {
        return pieceIndex >= 0 && pieceIndex < totalPieces && completedPieces[pieceIndex];
    }

    public synchronized boolean isPieceInProgress(int pieceIndex) {
        return pieceIndex >= 0 && pieceIndex < totalPieces && inProgressPieces[pieceIndex];
    }

    public synchronized void markPieceInProgress(int pieceIndex) {
        if (pieceIndex >= 0 && pieceIndex < totalPieces) {
            inProgressPieces[pieceIndex] = true;
        }
    }

    // Add this to update the rarity map when a peer sends a 'HAVE' (ID 4) message
    public synchronized void registerSinglePiece(int pieceIndex) {
        if (pieceIndex >= 0 && pieceIndex < totalPieces) {
            rarityMap[pieceIndex]++;
        }
    }

    /**
     * Called when a peer sends a Bitfield (Message ID 5)
     */
    public synchronized void registerPeerBitfield(byte[] bitfield) {
        for (int i = 0; i < totalPieces; i++) {
            int byteIndex = i / 8;
            int bitIndex = 7 - (i % 8); // Read from left to right

            // If the peer's bitfield covers this index and the bit is 1
            if (byteIndex < bitfield.length && ((bitfield[byteIndex] >> bitIndex) & 1) == 1) {
                rarityMap[i]++; // Increase the availability count
            }
        }
        System.out.println("Updated Rarity Map. Current availability of Piece 0: " + rarityMap[0]);
    }

    /**
     * Called when a peer Unchokes us. We find the rarest piece THEY have,
     * that WE don't have, and that isn't already being downloaded.
     */
    public synchronized int getOptimalPieceToDownload(byte[] peerBitfield) {
        int targetPiece = -1;
        int lowestAvailability = Integer.MAX_VALUE;

        for (int i = 0; i < totalPieces; i++) {
            // 1. Do we already have it or are we downloading it? Skip.
            if (completedPieces[i] || inProgressPieces[i]) continue;

            // 2. Does this specific peer have it?
            int byteIndex = i / 8;
            int bitIndex = 7 - (i % 8);
            boolean peerHasPiece = byteIndex < peerBitfield.length && ((peerBitfield[byteIndex] >> bitIndex) & 1) == 1;

            if (peerHasPiece) {
                // 3. Is it the rarest one we've seen so far?
                if (rarityMap[i] < lowestAvailability) {
                    lowestAvailability = rarityMap[i];
                    targetPiece = i;
                }
            }
        }

        // Mark it as in-progress so another thread doesn't grab it
        if (targetPiece != -1) {
            inProgressPieces[targetPiece] = true;
        }

        return targetPiece;
    }

    public synchronized void writePiece(int pieceIndex, byte[] data) throws Exception {
        fileManager.writePiece(pieceIndex, data);
    }

    public synchronized boolean isComplete() {
        return completedCount >= totalPieces;
    }

    /**
     * Called when a piece is fully downloaded and hash-checked.
     */
    public synchronized void markPieceComplete(int pieceIndex) {
        if (!completedPieces[pieceIndex]) {
            completedPieces[pieceIndex] = true;
            completedCount++; // O(1) increment
        }

        inProgressPieces[pieceIndex] = false;

        System.out.println("PROGRESS: Piece " + pieceIndex + " completed!");
        System.out.println("Download Status: " + completedCount + " / " + totalPieces);
    }

    /**
     * Called when a piece fails hash check or download fails.
     */
    public synchronized void markPieceFailed(int pieceIndex) {
        if (pieceIndex >= 0 && pieceIndex < totalPieces) {
            inProgressPieces[pieceIndex] = false;
        }
        System.out.println("PROGRESS: Piece " + pieceIndex + " failed verification and was released.");
    }
}