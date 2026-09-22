# ADR-0001: LAN-only transport for MVP 1.0

- Status: accepted
- Date: 2026-09-20
- Decision owner: DeviceBridge maintainer
- Related specification: `docs/technical-specification.md`, sections 12.2, 12.3 and 20
- Related change: `prepare-mvp-security-release`

## Context

DeviceBridge is one Android application that exposes a bundled browser client over the phone's local network. The MVP must work without a cloud relay or a separately installed desktop application. A dynamic private IPv4 address cannot use an ordinary publicly trusted TLS certificate without another service, a domain, or explicit certificate installation on the computer.

HTTP/WebSocket session authorization prevents unapproved API use, but it does not encrypt traffic from another participant on the same network. This transport limitation must remain visible and must not be described as end-to-end or transport encryption.

## Decision

MVP 1.0 uses option 4 from the technical specification: one Android application with LAN-only HTTP/WebSocket transport in a trusted private network.

The release contract therefore requires:

- an explicit trusted-network warning before transfer;
- short-lived pairing plus phone approval and bearer authorization;
- exact Host/Origin checks and no permissive CORS;
- no external backend, analytics, CDN, cloud relay, or desktop companion;
- immediate listener/session shutdown after stop, network loss, or local-network permission revocation;
- a fail-closed release verdict when security or distribution evidence is missing.

The session token is an authorization credential only. It is not a claim of transport confidentiality.

## Rejected alternatives

### Local HTTPS with user-managed trust

Rejected for MVP because certificate provisioning and trust onboarding materially change installation and recovery flows. It may be reconsidered in a separate change.

### WebRTC/DTLS

Rejected for MVP because it changes transport negotiation, browser compatibility, protocol implementation, and acceptance scope. It requires a dedicated design and threat review.

### Desktop companion

Rejected for MVP because it creates a second distributed application and contradicts the one-Android-app product decision. It remains a future option if the browser-only model proves insufficient.

### Cloud relay or account service

Rejected because offline-first local operation and absence of external runtime infrastructure are product constraints.

## Consequences

Positive consequences are a small distribution surface, offline operation, and no computer-side installation. Residual risk is that text, file contents, and session credentials are readable to a capable observer on the same LAN. The user must use a trusted private network and avoid sensitive transfers on public or unknown Wi-Fi.

This ADR does not authorize weakening pairing, request validation, credential storage, logging, file validation, or lifecycle controls. A future encrypted transport must be proposed as a separate OpenSpec change and must preserve existing authorization guarantees.
