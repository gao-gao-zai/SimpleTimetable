# SimpleTimetable reconstruction protocol 1

This branch reconstructs behavior from conversation evidence. Exact lost capability fields, ACK layout, LZ4 envelope and timeout constants were not recoverable. The values below are this implementation's documented choices, tested across Kotlin and JavaScript. They are not a claim of byte compatibility with the lost 0.1.27 binaries.

Android is the GATT client; the watch is the server. Service `d7300001-9e2c-4c53-a1aa-2cc0bf3d7710`, RX write-with-response `d7300002-9e2c-4c53-a1aa-2cc0bf3d7710`, TX notify `d7300003-9e2c-4c53-a1aa-2cc0bf3d7710`, optional read probe `d7300004-9e2c-4c53-a1aa-2cc0bf3d7710`, CCCD 2902. The probe's initial value is deliberately invalid; only its real read callback returns `STPROBE1` followed by little-endian CRC16. Probe failure never blocks HELLO/READY. No BlueXlink, account, network service or pairing-code advertisement.

## Framing and capability negotiation

Integers are little endian. Header: magic `53` hex, version `01`, operation byte, transfer ID uint16, sequence uint16, payload length byte, CRC16 uint16. CRC16/CCITT-FALSE init FFFF polynomial 1021 covers bytes 0..7 followed by payload. Operations START=1, DATA=2, END=3, ACK=4; ERROR=5 reserved.

HELLO and READY always use uncompressed JSON in 10 B DATA chunks (20 B ATT values). Every connection starts fresh. HELLO advertises `transport:{chunk:10|100,lz4:true,consent:1}`. Android requests MTU 113 and advertises 100 only if the actual negotiated MTU is at least 113. The server returns the intersection in READY; subsequent messages may use it. Small MTU can still use negotiated LZ4 with 10 B chunks. Consent support is required: old 0.1.0 application peers receive an upgrade message. The old 10 B transport decoder remains covered, but old applications cannot implement the new consent flow.

Legacy START seq0 contains wire length uint32 and IEEE CRC32 uint32. Extended START adds chunk byte (10 or 100) and codec byte (0=raw, 1=standard independent LZ4 block), still fitting a small packet. Compression is selected only if compressed bytes are fewer than raw UTF-8 JSON bytes. Both raw and wire length are bounded at 65536; decompression cannot exceed that bound. DATA seq1..ceil(wireLength/chunk), exact chunk size except the final packet. END seq=last DATA+1 has no payload.

ACK sequence is the next expected sequence. Extended ACK also includes chunk byte and wire CRC32, binding it to the transfer's representation. Sender rejects stale ACKs from another chunk size/content. Duplicate DATA/END within the session do not append or stage twice. A START with changed chunk size resets that receiver to the beginning. A missed large-packet ACK causes fallback to 10 B while retaining compressed bytes. Packet wait is 2 s, up to five retries; END gets a separate 60 s finalization wait before retry/fallback. These timeout choices are reconstructed, not recovered historical constants.

Receive buffers are bounded session memory, with no per-packet file writes. Disconnect, cancellation and restart discard incomplete buffers; the user requests a new transfer. There is no cross-restart packet resume. The generic Receiver persistence hook exists for fault-injection tests; production links use memory. Old async finalization checks the connection generation before delivery or ACK. Decompression and strict UTF-8 decoding run in bounded steps and yield between stages; parsing, validation, preview storage and its readback precede completion. The original decoded byte length is retained rather than inferred by reserializing JSON (which could change Unicode escaping).

## Application flow

Every message includes `protocol:1` and a unique nonce. HELLO includes the six-digit code; READY confirms it and capabilities. The code stays in memory, expires after five minutes, and five wrong attempts close the watch session. HELLO_ERROR distinguishes CODE_MISMATCH from UPGRADE_REQUIRED. Codes prevent accidental connection; this protocol does not claim encryption.

Connection does not automatically send a catalog or replay an old profile. Explicit LIST requests a CATALOG (`profiles:[{id,name,revision}]`). PULL requests that the peer initiate the same send/consent flow for one complete profile.

1. Sender selects a complete profile and sends REQUEST metadata only: requestId, profileId, name, revision, raw body byte size and counts. No profile body is present.
2. Receiver explicitly accepts or rejects via RESPONSE (`requestId`, `accepted`). Consent waits at most 60 s. Cancel, reject, timeout and disconnect remain distinct states. Old request IDs cannot authorize a new body.
3. Only accepted requests permit OFFER (`requestId`, profile, revision). Receiver checks request identity, revision and decoded byte size before durable preview staging. Complete profile means all subjects, layouts, plans, groups and temporary rules, including unknown metadata; device display/calibration stays local.
4. STAGED (`requestId`, revision) says the preview was generated. It does not mean saved. Sender now displays that the receiving device must choose a save action.
5. Receiver previews and chooses replace, keep, or copy. Confirmation is bound to the exact offer nonce and current local revision. After successful atomic live persistence, SAVED contains offerNonce, profileId, revision and choice. Only this receipt completes the send. Failure cannot emit SAVED.

CANCEL is scoped to requestId. Fresh requests are required after reconnect. Recent saved nonces/receipts suppress repeated application effects. Revision is CRC32 of canonical JSON: sorted object keys, retained array order, eight lowercase hex digits. It is change detection, not cryptographic authentication. Common revision advances only after confirmed replacement; copy/keep do not invent a common base. No automatic per-lesson merge.

## Verification

Synthetic vectors in `vectors.json`, `lz4-vectors.json`, `transfer-vectors.json`; the LZ4 vectors include independently generated liblz4 blocks. JavaScript streams are decoded in Kotlin tests, then Kotlin streams are decoded by `blueos/scripts/verify-android-interop.mjs`. See [the current recovery report](../docs/recovery-2026-10-30.md) for actual results and remaining device/build limits.
