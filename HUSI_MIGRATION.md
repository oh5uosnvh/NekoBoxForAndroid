# Husi core migration notes

This branch keeps the existing NekoBox Android UI and gomobile/JNI surface unchanged.

## Included in this stage

- Husi-derived URL test measurement logic under `libcore/husiurltest`.
- Existing Neko `Libcore.urlTest(BoxInstance, link, timeout)` remains the only UI-facing entry point.
- HTTP/HTTPS HEAD testing, HTTP/3/QUIC build-tag support, unified-delay second request, timeout normalization, and direct fallback.
- The xhttp/Heysocks protocol adapter from the local protocol source bundle under `libcore/protocol/xhttp`.
- `heysocks://` parsing into Neko's existing custom outbound (`ConfigBean`) path; no new UI screens or preference layouts.
- Android deep-link registration for `heysocks`.

## Deliberately not done yet

The husi repository is not a drop-in replacement for Neko's Go core. Its current sing-box dependency is `v1.15.0-alpha.13`, while this Neko fork's exported platform wrapper, custom Juicity adapter, and generated gomobile API are based on an older sing-box API. Replacing the module in one step would break the platform/JNI contract and requires a separate compatibility port.

The safe next stage is to port the husi platform wrapper and registry changes behind the existing Neko exports, then build all Android ABIs before changing the sing-box source pin. Do not copy husi protobuf/gRPC host services into Neko's JNI core.

## Credential safety

The xhttp protocol accepts runtime `sess` and `auth` values through the profile JSON. They are not embedded in source, tests, logs, or this document. The fixed default key material is part of the protocol implementation, not an account credential. Rotate/revoke any GitHub token that was pasted into chat before pushing.
