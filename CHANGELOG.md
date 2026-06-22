# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased]

### Fixed

- Bug #3 — UDP tracker response buffer enlarged from 4096 to 65507 bytes to prevent silent truncation of large peer lists.
- Bug #2 — Added bounds and alignment validation on received piece blocks to prevent ArrayIndexOutOfBoundsException from malicious peers.
