# SimpleTimetable BLE protocol 1 (new recovery protocol)

This is a new wire protocol. UUIDs/bytes of the lost implementation were never retrieved.
Android is the GATT client, BlueOS the server. Service `d7300001-9e2c-4c53-a1aa-2cc0bf3d7710`, RX write-with-response `d7300002-9e2c-4c53-a1aa-2cc0bf3d7710`, TX notify `d7300003-9e2c-4c53-a1aa-2cc0bf3d7710`, CCCD 2902. Broadcast only during an explicit synchronization session. No personal data or connection code in advertising.

## Transport

All integers little endian; ATT values at most 20 bytes (default MTU 23 supported). Header: magic `53` hex, version `01`, operation byte, transfer ID uint16, sequence uint16, payload length byte, CRC16 uint16, payload 0..10 bytes. CRC16/CCITT-FALSE init FFFF polynomial 1021 covers bytes 0..7 then payload (excluding checksum bytes).

Operations: START=1, DATA=2, END=3, ACK=4, ERROR=5 (reserved; not emitted by this implementation). START seq0 contains length uint32 and IEEE CRC32 uint32 of complete UTF-8 JSON. Limit 262144 bytes. DATA seq1..ceil(length/10), exactly 10 bytes except final chunk. END seq=last DATA+1, empty payload. ACK sequence is next expected sequence. START with same transfer ID/size/checksum resumes journaled position, including completed transfers. Duplicate DATA/END are acknowledged without appending/applying twice; unknown/future DATA returns current ACK. Invalid frame, oversized data or failed storage never advances cursor. After reconnect the sender resends START using the same transfer ID/message. Retry after 2s, maximum five retries, then show interruption and allow reconnect. One queued outgoing message per direction; ACK packets bypass outgoing message queue. Changing direction is an explicit user action.

Receiver journal is separate from live timetable. Persist journal before ACK. END validates full size/checksum/strict UTF-8/JSON, durably stages an idempotent application message (nonce), then ACKs. Transport completion means delivery to staging, never implicit replacement of live data. Application `SAVED` is sent only after user choice and successful live storage. Sender must retain unsaved state if no SAVED is received. Complete profile includes Android schema-1 Profile fields, colors and unknown metadata; device calibration/display settings are excluded.

## Application JSON

All messages include `protocol:1` and unique `nonce`. HELLO has six-digit `code`; READY follows only after matching a fresh watch-displayed code (session 5 minutes, max five failures). Code is an accidental pairing guard, not encryption or strong authentication: BLE traffic is not claimed confidential. Sensitive data requires a trusted nearby environment; this preview does not claim bonded/encrypted transport.

CATALOG has `profiles:[{id,name,revision}]`; PULL has `profileId`; OFFER has `profile` and `revision`; SAVED has `offerNonce`, `profileId`, `revision`, `choice` (`replace`/`copy`/`keep`). Only paired peer may request or send profiles. CRC32 revision is deterministic canonical JSON (lexicographically sorted object keys, array order retained), eight lowercase hex digits. It detects accidental change, not adversarial tampering. Explicit profile selection, direction and preview remain required regardless of revision.

Store last common revision per profile only after replacement confirmed by SAVED. Equal revisions: same. Local==base: suggest receive; remote==base: suggest send; otherwise choose/conflict. No automatic per-lesson merging. Replace incoming, keep local, or keep incoming as new profile copy; recheck local revision at save to reject stale preview. Copy/keep must not establish a false common revision. Activation and local settings stay local.

## Current evidence

`node --test blueos/tests/*.test.js` passed 20 tests on 2026-10-22 covering rule boundaries, split temporary periods, swap/expiry, input validation, checksum vectors, duplicate/lost ACK, restart/resume, failed persistence and stale preview. This is platform-independent logic verification, not BlueOS compilation or GT2 radio validation.

Preview confirmations are bound to the exact OFFER nonce as well as the local revision. If another OFFER arrives while a confirmation is open, the old confirmation fails and requires a fresh preview. Transfer journal memory is capped at six concurrent/recent IDs.
