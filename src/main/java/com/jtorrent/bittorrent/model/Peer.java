package com.jtorrent.bittorrent.model;

public record Peer(String ip, int port) {
    @Override
    public String toString() {
        return ip + ":" + port;
    }
}