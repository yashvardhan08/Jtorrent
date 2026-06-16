package com.jtorrent.bittorrent.service;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class TorrentFileManager {

    public static class FileMapping {
        public final File file;
        public final long startOffset;
        public final long length;

        public FileMapping(File file, long startOffset, long length) {
            this.file = file;
            this.startOffset = startOffset;
            this.length = length;
        }
    }

    private final List<FileMapping> fileMappings = new ArrayList<>();
    private long totalSize = 0;
    private long pieceLength;

    public TorrentFileManager() {}

    public void initializeFiles(Map<String, Object> infoDict, String downloadDirPath) throws Exception {
        File downloadDir = new File(downloadDirPath);
        byte[] nameBytes = (byte[]) infoDict.get("name");
        String baseName = new String(nameBytes, StandardCharsets.UTF_8);
        this.pieceLength = (long) infoDict.get("piece length");

        if (infoDict.containsKey("files")) {
            File baseFolder = new File(downloadDir, baseName);
            baseFolder.mkdirs();
            List<Object> filesList = (List<Object>) infoDict.get("files");
            long currentOffset = 0;

            for (Object fileObj : filesList) {
                Map<String, Object> fileDict = (Map<String, Object>) fileObj;
                long length = (long) fileDict.get("length");
                List<Object> pathElements = (List<Object>) fileDict.get("path");

                File currentFile = baseFolder;
                for (Object pathElem : pathElements) {
                    currentFile = new File(currentFile, new String((byte[]) pathElem, StandardCharsets.UTF_8));
                }

                currentFile.getParentFile().mkdirs();
                try (RandomAccessFile raf = new RandomAccessFile(currentFile, "rw")) {
                    raf.setLength(length);
                }

                fileMappings.add(new FileMapping(currentFile, currentOffset, length));
                currentOffset += length;
            }
            this.totalSize = currentOffset;
            System.out.println("Initialized Multi-File Torrent: " + fileMappings.size() + " files, " + totalSize + " bytes total.");
        } else {
            long length = (long) infoDict.get("length");
            File singleFile = new File(downloadDir, baseName);
            singleFile.getParentFile().mkdirs();
            try (RandomAccessFile raf = new RandomAccessFile(singleFile, "rw")) {
                raf.setLength(length);
            }
            fileMappings.add(new FileMapping(singleFile, 0, length));
            this.totalSize = length;
            System.out.println("Initialized Single-File Torrent: " + singleFile.getName());
        }
    }

    public synchronized void writePiece(int pieceIndex, byte[] pieceData) throws Exception {
        long globalWriteOffset = (long) pieceIndex * this.pieceLength;
        int bytesLeftToWrite = pieceData.length;
        int bufferOffset = 0;

        for (FileMapping mapping : fileMappings) {
            if (globalWriteOffset < (mapping.startOffset + mapping.length) &&
                    (globalWriteOffset + bytesLeftToWrite) > mapping.startOffset) {

                long fileLocalOffset = Math.max(0, globalWriteOffset - mapping.startOffset);
                long maxBytesForThisFile = mapping.length - fileLocalOffset;
                int bytesToWriteNow = (int) Math.min(bytesLeftToWrite, maxBytesForThisFile);

                try (RandomAccessFile raf = new RandomAccessFile(mapping.file, "rw")) {
                    raf.seek(fileLocalOffset);
                    raf.write(pieceData, bufferOffset, bytesToWriteNow);
                }

                globalWriteOffset += bytesToWriteNow;
                bufferOffset += bytesToWriteNow;
                bytesLeftToWrite -= bytesToWriteNow;

                if (bytesLeftToWrite == 0) break;
            }
        }
    }

    public long getTotalSize() {
        return totalSize;
    }
}
