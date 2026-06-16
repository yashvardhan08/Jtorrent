# Jtorrent — System Design Document

## 1. Overview

Jtorrent is a **BitTorrent client** written in Java 21, built on Spring Boot 4.0. It downloads files from the BitTorrent network using native UDP tracker protocol (BEP-0015), HTTP trackers, and the standard peer wire protocol.

**Stack:** Java 21, Spring Boot 4.0 (DI only — no web layer), Maven, Guava

---

## 2. Architecture Diagram

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                          JtorrentApplication                                 │
│                      (Spring Boot CommandLineRunner)                         │
│                                                                              │
│  ┌─────────────────┐  ┌──────────────────┐  ┌────────────────────────────┐  │
│  │  BencodeParser   │  │  TrackerService   │  │  Main Download Loop       │  │
│  │                  │  │                  │  │                            │  │
│  │  .torrent file   │  │  UDP (BEP-0015)  │  │  ThreadPool (50 threads)  │  │
│  │  → Map<String,   │  │  HTTP(S)         │  │  Peer cooldown (60s)      │  │
│  │    Object>        │  │  announce-list   │  │  Tracker re-query loop    │  │
│  │  infoBytes[]      │  │  fallback        │  │  Progress logging (30s)  │  │
│  └────────┬─────────┘  └────────┬─────────┘  └─────────────┬──────────────┘  │
└───────────┼─────────────────────┼──────────────────────────┼─────────────────┘
            │ infoHash, left      │ peerId, infoHash         │
            ▼                     ▼                          ▼

┌──────────────────────────────────────────────────────────────────────────────┐
│                             PeerService                                       │
│               (One instance per peer connection, runs in thread pool)         │
│                                                                              │
│  ┌──────────┐    ┌─────────────┐    ┌───────────────────┐    ┌───────────┐  │
│  │Handshake │───▶│  Send       │───▶│  Wait for         │───▶│ Download  │  │
│  │68 bytes  │    │  Interested │    │  Unchoke +        │    │ Loop      │  │
│  │pstr=19   │    │  (msg ID 2) │    │  BITFIELD/HAVE    │    │           │  │
│  └──────────┘    └─────────────┘    └───────────────────┘    └─────┬─────┘  │
│                                                                    │        │
│  ┌─────────────────────────────────────────────────────────────────┘        │
│  │                                                                          │
│  │  ┌─────────────────────────────────────────────────────────────────┐     │
│  │  │ downloadPiece(index)                                            │     │
│  │  │                                                                 │     │
│  │  │  1. Send initial burst of 5 block requests (msg ID 6, 16KB)    │     │
│  │  │  2. Loop: receive piece responses (msg ID 7)                    │     │
│  │  │  3. On each response: copy block → pieceData[], refill window   │     │
│  │  │  4. When all blocks received: SHA-1 hash verification           │     │
│  │  │  5. On valid: writePiece() + markPieceComplete()                │     │
│  │  │  6. On fail:  markPieceFailed() (releases for other peers)      │     │
│  │  └──────────────────────────────────────┬──────────────────────────┘     │
│  └─────────────────────────────────────────┼────────────────────────────────┘
└────────────────────────────────────────────┼─────────────────────────────────┘
                                             │
                                             ▼

┌──────────────────────────────────────────────────────────────────────────────┐
│                           TorrentManager                                      │
│                   (Piece State & Selection — "The Brain")                      │
│                                                                              │
│  STATE                                     METHODS                           │
│  ┌──────────────────────────────┐          ┌──────────────────────────┐      │
│  │ completedPieces[boolean[]]   │          │ getOptimalPieceToDownload│      │
│  │ inProgressPieces[boolean[]]  │          │   (rarest-first)         │      │
│  │ rarityMap[int[]]             │          │ registerPeerBitfield()   │      │
│  │ completedCount               │          │ registerSinglePiece()    │      │
│  │ piecesAllHashes[byte[]]      │          │ getExpectedHash(index)   │      │
│  │ TorrentFileManager (ref)     │          │ isComplete()             │      │
│  └──────────────────────────────┘          │ writePiece() ───delegates────┐  │
│                                            │ markPieceComplete()         │  │
│                                            │ markPieceFailed()           │  │
│                                            └──────────────────────────┘  │  │
│  All public state methods are synchronized                               │  │
└──────────────────────────────────────────────────────────────────────────┘  │
                                    ┌─────────────────────────────────────────┘
                                    ▼

┌──────────────────────────────────────────────────────────────────────────────┐
│                          TorrentFileManager                                   │
│                         (Disk I/O — "The Hands")                              │
│                                                                              │
│  ┌────────────────────────────────────────────────────────────────────┐      │
│  │ List<FileMapping>                                                  │      │
│  │  ┌──────────────────────┬──────────────┬──────────────┐            │      │
│  │  │ File                 │ startOffset  │ length       │            │      │
│  │  ├──────────────────────┼──────────────┼──────────────┤            │      │
│  │  │ Tears/1-intro.mkv   │ 0            │ 314_572_800  │            │      │
│  │  │ Tears/2-main.mp4    │ 314_572_800  │ 209_715_200  │            │      │
│  │  │ Tears/3-credits.srt │ 524_288_000  │ 47_138_507   │            │      │
│  │  └──────────────────────┴──────────────┴──────────────┘            │      │
│  │                                                                    │      │
│  │  writePiece(pieceIndex, pieceData):                                │      │
│  │    1. globalOffset = pieceIndex × pieceLength                      │      │
│  │    2. For each FileMapping that overlaps:                          │      │
│  │       a. Calculate local offset within the file                    │      │
│  │       b. Slice pieceData[] to fit within file boundary             │      │
│  │       c. RandomAccessFile.seek(offset) + .write(slice)             │      │
│  │       d. Advance bufferOffset, repeat for next file                │      │
│  │                                                                    │      │
│  │  initializeFiles(infoDict, downloadDir):                           │      │
│  │    - Parses single-file (length) or multi-file (files[])           │      │
│  │    - Creates directory structure and pre-allocates all files       │      │
│  └────────────────────────────────────────────────────────────────────┘      │
└──────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Component Responsibilities

### JtorrentApplication
- **Role:** Orchestrator, entry point
- Creates `TorrentFileManager`, `TorrentManager`, parses `.torrent` file
- Runs the main download loop:
  - Submits peer connections to a **fixed thread pool (50 threads)**
  - Maintains **peer cooldown** (60s between reconnect attempts to same peer)
  - **Re-queries tracker** periodically (respects tracker's `interval`)
  - Logs **progress every 30 seconds**
  - Sends `completed` event to tracker when done

### TrackerService
- **Role:** Peer discovery
- Supports **UDP tracker protocol (BEP-0015)** — two-step handshake: connect → announce
- Supports **HTTP/HTTPS trackers** — standard GET with bencoded response
- **Fallback loop** — iterates through `announce-list` tiers, skipping unsupported protocols
- Maps string events (`started`, `completed`, `stopped`) to BEP-0015 integer codes

### PeerService
- **Role:** Peer wire protocol, data transfer
- **Handshake** (68 bytes): sends `pstrlen=19`, `"BitTorrent protocol"`, info hash, peer ID
- **Interested → Unchoke handshake**: waits for BITFIELD/HAVE and UNCHOKE
- **Rarest-first download loop**: queries `TorrentManager` for optimal piece, downloads it
- **Sliding window pipelining** (`WINDOW_SIZE=5`): sends up to 5 concurrent 16KB block requests
- **SHA-1 verification** on every completed piece before writing to disk
- **Graceful failure handling**: IOException = connection lost, other = piece failed (released)

### TorrentManager
- **Role:** Decision maker, state tracker
- Tracks **piece completion** (`completedPieces[]`, `completedCount` O(1))
- Tracks **in-progress pieces** to avoid duplicate work
- Tracks **piece rarity** across all connected peers (`rarityMap[]`)
- **Rarest-first** piece selection algorithm
- Provides **expected SHA-1 hashes** for verification
- Delegates disk writes to `TorrentFileManager`
- All public methods are `synchronized` for thread safety

### TorrentFileManager
- **Role:** Disk I/O
- **Single-file torrent**: writes to one flat file named after `info.name`
- **Multi-file torrent**: creates full directory tree, pre-allocates each file
- **Boundary-crossing writes**: a single piece can span multiple files — `writePiece()` slices the byte array at file boundaries and writes each segment to the correct file
- **Pre-allocation**: each file is created with `RandomAccessFile.setLength()` before any writes

### BencodeParser
- **Role:** Torrent metadata deserialization
- Parses the Bencoding format (strings, integers, lists, dictionaries)
- Captures raw `info` dictionary bytes **before decoding** (needed for SHA-1 infohash)
- Strings returned as `byte[]` to preserve binary data; keys coerced to `String`

---

## 4. Data Flow Walkthrough

```
1. STARTUP
   JtorrentApplication.main()
     → Spring Boot starts, injects TrackerService, PeerService
     → Reads .torrent file as byte[]
     → BencodeParser.parse() → Map<String, Object> torrentData
     → parser.getInfoBytes() → raw byte[] of "info" dictionary
     → SHA-1(raw info bytes) → infoHash (20 bytes)

2. FILE SETUP
   TorrentFileManager.initializeFiles(info, ".")
     → Detects single-file (has "length") or multi-file (has "files")
     → Creates directories, pre-allocates files via RandomAccessFile.setLength()
     → Builds List<FileMapping> with global byte offsets

3. TRACKER QUERY
   TorrentManager created with totalPieces, pieceSize, totalLength, hashes, fileManager
   TrackerService.getPeers(trackers, infoHash, left, "started")
     → Iterates trackers in order
     → Skips unsupported protocols
     → UDP: BEP-0015 connect → announce (returns raw binary peers)
     → HTTP: GET request with URL-encoded params (returns bencoded response)
     → parseCompactPeers() → List<Peer>

4. PEER CONNECTIONS (concurrent)
   For each peer (up to 50 in thread pool):
     PeerService.connectToPeer(peer, infoHash, peerId, torrentManager)
       → TCP socket to peer.ip:peer.port
       → Send 68-byte handshake
       → Read 68-byte handshake response
       → Send Interested (msg ID 2)
       → Wait for Unchoke (msg ID 1) + BITFIELD (msg ID 5) / HAVE (msg ID 4)
       → registerPeerBitfield() → rarityMap updated

5. DOWNLOAD LOOP (per peer)
   while true:
     manager.getOptimalPieceToDownload(peerBitfield)
       → Skips completed + in-progress pieces
       → Finds rarest piece this peer has
       → Marks it in-progress (other peers skip it)

     downloadPiece(index):
       → Send up to 5 block requests at once (sliding window)
       → Loop: receive piece responses → copy to pieceData[] → send more requests
       → SHA-1 hash(pieceData) == expectedHash?
         YES → manager.writePiece(index, pieceData)
                → delegates to TorrentFileManager.writePiece()
                → slices across file boundaries, writes to RandomAccessFile
                → manager.markPieceComplete(index)
         NO  → manager.markPieceFailed(index) → piece released

6. COMPLETION
   All pieces done → executor.shutdown()
     → TrackerService.getPeers(trackers, infoHash, 0, "completed")
```

---

## 5. BitTorrent Protocol Implementation

### Supported BEPs

| BEP | Description | Status |
|-----|-------------|--------|
| BEP-0003 | BitTorrent Protocol (wire) | ✅ Handshake, Interested, Unchoke, Have, Bitfield, Request, Piece, Choke |
| BEP-0015 | UDP Tracker Protocol | ✅ Connect + announce, event codes |
| BEP-0012 | Multi-tracker (announce-list) | ✅ Tiered fallback |
| BEP-0023 | Compact peer lists | ✅ Tracker uses `compact=1` |

### Wire Protocol Message IDs

| ID | Message | Implemented |
|----|---------|-------------|
| 0  | Choke | ✅ Handled (throws during download) |
| 1  | Unchoke | ✅ Waited for before download |
| 2  | Interested | ✅ Sent after handshake |
| 3  | Not Interested | ❌ Not sent |
| 4  | Have | ✅ Tracked in rarityMap |
| 5  | Bitfield | ✅ Parsed, fed to rarest-first |
| 6  | Request | ✅ Sent with sliding window |
| 7  | Piece | ✅ Received, assembled, verified |
| 8  | Cancel | ❌ Not sent |

### Block Pipelining

```
WINDOW_SIZE = 5
blockSize = 16384 (16 KB)

Timeline:
PeerService          Peer
    │                  │
    ├─ Request(0) ────▶│
    ├─ Request(1) ────▶│
    ├─ Request(2) ────▶│
    ├─ Request(3) ────▶│
    ├─ Request(4) ────▶│
    │                  │
    │◀─── Piece(0) ───┤
    ├─ Request(5) ────▶│  ← window refilled
    │◀─── Piece(2) ───┤
    ├─ Request(6) ────▶│
    │◀─── Piece(1) ───┤
    ├─ Request(7) ────▶│
    │◀─── Piece(3) ───┤
    ├─ Request(8) ────▶│
    │   ...            │
```

---

## 6. Concurrency Model

```
JtorrentApplication
    │
    ├── FixedThreadPool(50) ──── PeerService threads
    │     │                         │
    │     │  synchronized          │  manager.getOptimalPieceToDownload()
    │     │  (TorrentManager)      │  manager.markPieceComplete()
    │     │                         │  manager.markPieceFailed()
    │     │                         │
    │     │  synchronized          │  fileManager.writePiece()
    │     │  (TorrentFileManager)  │     (inner lock, redundant)
    │     │                         │
    │     └── Socket timeout: 5s   │  socket.setSoTimeout(5000)
    │
    └── Main thread (periodic)
          │  Thread.sleep(5000)    │  Tracker re-query
          │                         │  Progress logging
```

### Thread Safety Mechanisms

| Mechanism | Where | Purpose |
|-----------|-------|---------|
| `synchronized` methods | `TorrentManager` | Piece state, rarity, completion — all peer threads coordinate here |
| `synchronized` methods | `TorrentFileManager` | Disk writes — one at a time (fast path, low contention) |
| `ConcurrentHashMap.newKeySet()` | `JtorrentApplication` | Peer deduplication — `add()` fails if already connected |
| `SocketTimeoutException` | `PeerService` | Stops blocking on dead peers after 5s |
| Per-peer `peerCooldown` | `JtorrentApplication` | Prevents reconnect storms to same peer within 60s |

### Known Gaps

- **No piece timeout**: If a peer grabs a piece via `getOptimalPieceToDownload()` then stalls (but doesn't disconnect), that piece stays `inProgress` indefinitely. No timer releases it.
- **Double synchronization**: `TorrentManager.writePiece()` is `synchronized` and delegates to `TorrentFileManager.writePiece()` which is also `synchronized`. The outer lock is redundant.

---

## 7. Project Structure

```
src/main/java/com/jtorrent/bittorrent/
├── JtorrentApplication.java     Entry point, orchestrator, main loop
├── BencodeParser.java           .torrent file decoder
├── TorrentMetadata.java         Record for parsed metadata
├── model/
│   └── Peer.java                Record(ip, port)
├── service/
│   ├── PeerService.java         Wire protocol, block pipelining, SHA-1
│   ├── TrackerService.java      UDP/HTTP tracker client, compact peer parsing
│   ├── TorrentManager.java      Piece state, rarest-first selection
│   └── TorrentFileManager.java  Disk I/O, single/multi-file, boundary slicing
└── utils/
    └── PeerIdGenerator.java     Generates 20-byte peer ID (-JT0001-XXXXXXXXX)

src/main/resources/
└── application.properties       Spring app name

pom.xml                          Spring Boot 4.0.5, Java 21, Guava
```

---

## 8. How to Run

```bash
# Build
./mvnw clean package -DskipTests

# Run
java -jar target/bittorrent-0.0.1-SNAPSHOT.jar /path/to/file.torrent

# Or via Maven
./mvnw spring-boot:run -Dspring-boot.run.arguments="/path/to/file.torrent"
```

---

## 9. Known Issues & Future Work

### Critical (crash or hang)

| # | Bug | File:Line | Details | Status |
|---|-----|-----------|---------|--------|
| 1 | **CHOKE infinite loop** | `PeerService.java:123-128` | `waitForPieceInfo()` loops forever on CHOKE — never returns false, thread hangs | ❌ Open |
| 2 | **No bounds check on received block** | `PeerService.java:168` | `System.arraycopy` has no validation of `begin` or `block.length`. Malicious peer → `ArrayIndexOutOfBounds` crash | ❌ Open |
| 3 | **UDP response buffer too small** | `TrackerService.java:106` | Fixed 4096-byte buffer silently truncates tracker responses with ~680+ peers | ❌ Open |

### Moderate (incorrect behavior)

| # | Bug | File:Line | Details | Status |
|---|-----|-----------|---------|--------|
| 4 | **RandomAccessFile open/close per piece** | `TorrentFileManager.java:88-91` | File opened, seeked, written, closed for every single piece — thousands of syscalls | ❌ Open |
| 5 | **rarityMap never decrements** | `TorrentManager.java:89-99` | Peer disconnects → its pieces stay counted. Rarity inflates, rarest-first degrades | ❌ Open |
| 6 | **Integer.parseInt for string length** | `BencodeParser.java:91` | String length parsed as `int` — spec allows strings >2GB, would throw `NumberFormatException` | ❌ Open |
| 7 | **blockIdx assumes 16KB alignment** | `PeerService.java:169` | `begin / blockSize` assumes `begin` is a multiple of 16384. Malformed response → wrong block slot | ❌ Open |
| 8 | **No validation of received block size** | `PeerService.java:165` | `msgLen - 9` determines block array size — no check against expected size | ❌ Open |
| 9 | **completedBytes ignores last piece** | `JtorrentApplication.java:123` | Uses `standardPieceLength` for all pieces — last piece is often smaller. Tracker `left` value slightly wrong | ❌ Open |

### Fixed

| # | Bug | Fix |
|---|-----|-----|
| 10 | **Hardcoded torrent path** | Now accepts path as CLI argument (`args[0]`) |

### Feature Gaps

| Area | Current | Desired |
|------|---------|---------|
| **DHT** | None | BEP-0005 for trackerless peer discovery |
| **PEX** | None | BEP-0011 for peer exchange between peers |
| **Piece timeout** | None — stale pieces block others | Timer that releases pieces after N seconds |
| **Endgame mode** | None — last few pieces can stall | Request from multiple peers, cancel duplicates |
| **Tit-for-tat unchoking** | None — other peers may choke us | BEP-0003 choke/unchoke algorithm |
| **Resume support** | None — restarts from zero | Save/load piece completion bitfield |
| **Upload** | None — only downloads | Send HAVE messages, unchoke uploaders |

---

## 10. Lessons Learned

### 1. Single-File → FileMapping Refactor

The original implementation wrote all pieces into a single `RandomAccessFile` at a byte offset (`pieceIndex × pieceSize`). This worked for single-file torrents but failed with a `NullPointerException` on multi-file torrents (e.g., Internet Archive books with hundreds of page images).

**Refactor:** Extracted `TorrentFileManager` with a `List<FileMapping>` that tracks each file's global byte offset. The `writePiece()` method slices the piece byte array at file boundaries, writing each segment to the correct file. This decoupled disk I/O from piece management — `PeerService` and `TorrentManager` never changed.

**Key insight:** A piece can span multiple files. The slice logic (`Math.max/min` to find the overlap, `System.arraycopy` with offset tracking) handles any combination of piece sizes and file boundaries.

### 2. Separating "Brain" from "Hands"

`TorrentManager` originally handled piece state, rarity, AND disk I/O. By moving I/O to `TorrentFileManager`, each class has a single responsibility:

- **TorrentManager** — decisions (which piece to download next, tracking completion)
- **TorrentFileManager** — execution (pre-allocating disks, writing bytes)

This made testing easier (mock the file manager) and prevented a God Object.

### 3. Synchronization Strategy

All state in `TorrentManager` is `synchronized` because multiple peer threads call it concurrently. The outer lock on `TorrentManager.writePiece()` is redundant since `TorrentFileManager.writePiece()` is also `synchronized` — a minor cleanup for a future PR.
