# Kaff transfer protocol (v1)

Kaff moves files between two Android phones over **Google Nearby Connections** with
`Strategy.P2P_POINT_TO_POINT`: one link at a time, bootstrapped over Bluetooth and upgraded to
Wi-Fi Direct or a hotspot when the radios allow it. The service id is `dev.kaff.app.transfer`.

Control messages travel as Nearby **BYTES** payloads (at most 32 KB each). File contents travel as
**FILE** payloads. Nearby encrypts the link; the authentication digits let both users confirm they
are talking to the phone in front of them.

## Envelope

Every BYTES payload is UTF-8 JSON:

```json
{ "v": 1, "msg": { "type": "accept", "transferId": "8f0c…" } }
```

- `v` is the protocol version. A receiver that sees a different `v` reports
  `BadMessage(unsupportedVersion = true)`, and the session ends with "update both apps".
- Unknown fields are ignored, so v1 can gain optional fields without a version bump.
- Payloads over 32 KB, invalid JSON or an unknown `type` are dropped as malformed.
- Message contents are never logged.

## Messages

| type     | direction           | fields                                                        | meaning |
|----------|---------------------|---------------------------------------------------------------|---------|
| `hello`  | both, after connect | `version`, `deviceName`                                       | Version and name exchange. |
| `offer`  | sender → receiver   | `transferId`, `files[]` (`name`, `size`, `mime`, `sha256`), `thumbnail?` | What the sender grabbed. `thumbnail` is a Base64 JPEG of the first file, ≤ 16 KB raw. |
| `armed`  | sender → receiver   | `transferId`, `windowMs`                                       | The fist was confirmed; the offer is live for `windowMs` (5000). |
| `accept` | receiver → sender   | `transferId`                                                   | Palm held, or Accept tapped. |
| `reject` | receiver → sender   | `transferId`, `reason`                                         | `UserDeclined` or `Timeout` (watch window ran out). |
| `cancel` | either              | `transferId`, `reason`                                         | `Timeout`, `UserCancelled`, `Integrity`, `StorageError`. |
| `file`   | sender → receiver   | `transferId`, `index`, `payloadId`                             | FILE payload `payloadId` carries `files[index]`. |
| `done`   | receiver → sender   | `transferId`, `sha256[]`                                       | Receipt: hashes of the saved files, in offer order. |

### Differences from the original brief

- **`sha256` is in OFFER.** The brief put the hash only in DONE. To *verify* on the receiver, the
  expected hash must be known before saving, so the sender hashes while preparing the file (before
  the fist). DONE then echoes the verified hashes as a receipt, and the sender compares them too.
- **Multi-file.** `offer.files` is a list (max 20 per offer), and the `file` header maps each FILE
  payload to its index, because Nearby does not guarantee that a FILE payload arrives after the
  BYTES message sent before it. The receiver buffers files that arrive before their header.
- **Thumbnail cap is 16 KB, not 20 KB.** Base64 adds 33 %, and the whole OFFER must fit in Nearby's
  32 KB BYTES limit. If an offer still does not fit, the codec drops the thumbnail and keeps the
  file list.

## Flow

```mermaid
sequenceDiagram
    participant S as Sender
    participant R as Receiver
    R->>R: advertise (receive screen or Standby)
    S->>S: pick files, hash + thumbnail, discover
    S->>R: requestConnection
    Note over S,R: both show the same 4 auth digits
    R->>R: user taps "code matches" (acceptConnection)
    S-->>R: connected
    S->>R: hello
    R->>S: hello
    S->>S: fist confirmed (~200 ms stable)
    S->>R: offer + armed(5000)
    R->>R: camera on, watch 8 s
    R->>S: accept (palm held 700 ms or Accept)
    loop each file
        S->>R: FILE payload
        S->>R: file(index, payloadId)
    end
    R->>R: verify size + SHA-256, save to MediaStore
    R->>S: done(sha256[])
    S->>S: compare hashes, success
    Note over S,R: sender disconnects; receiver lingers 1.5 s, then disconnects
```

## Timeouts

| timer   | owner    | length | on expiry |
|---------|----------|--------|-----------|
| Armed   | sender   | 5 s    | `cancel(Timeout)`; sender back to Ready, needs a new fist. |
| Watch   | receiver | 8 s    | `reject(Timeout)`; receiver back to Connected. |
| Receipt | sender   | 60 s   | Fails with "transfer failed"; link dropped. |
| Idle    | both     | 90 s   | A paired link with no offer is dropped. The receiver keeps advertising. |
| Linger  | receiver | 1.5 s  | After DONE, gives the receipt time to arrive, then disconnects. |

## Security model

- **The digits gate everything.** The receiver calls `acceptConnection` only after the user taps
  "code matches", so no BYTES or FILE payload can arrive from a phone the user did not confirm.
- **The palm is a trigger, not an identity check.** It only works in the `Offered` state, which
  needs a confirmed link. The Accept/Decline buttons are always available.
- **Integrity.** Each file is hashed while it is copied into MediaStore. On any mismatch the row is
  deleted before it ever becomes visible (`IS_PENDING`), and both sides report an integrity error.
- **File names** from the other phone are stripped of path separators and reserved characters.
- **Nothing persists** except transfer metadata in the local history (names, sizes, peer name,
  result). No contents, no thumbnails, no servers.
