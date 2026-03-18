# Project Guidelines

## Code Style
- Use Scala 3 idioms already used in this codebase: sealed traits, enums, case classes, and explicit pattern matching.
- Prefer explicit error handling over unsafe shortcuts (for example, avoid Option.get-style access patterns).
- Keep domain terms and protocol names consistent with existing code in src/main/scala/com/wayrecall/tracker/domain and src/main/scala/com/wayrecall/tracker/protocol.
- Follow existing ZIO style: dependency wiring with ZLayer, effects in ZIO types, shared mutable state via Ref/atomic types.

## Architecture
- Respect current boundaries:
  - network: TCP server, connection lifecycle, registry
  - protocol: parsing and protocol auto-detection
  - service/filter: processing pipeline and filters
  - storage: Redis and Kafka integrations
  - command: command domain and protocol-specific encoders
  - api: HTTP endpoints and diagnostics
- Keep hot-path processing non-blocking for Netty I/O threads. Preserve async/forking patterns used in connection handling.
- If adding or changing architecture-level behavior, align with ADRs in docs/DECISIONS.md.

## Build and Test
- Canonical local commands:
  - sbt compile
  - sbt test
  - sbt run
  - sbt assembly
- Typical local dependencies: Redis and Kafka. For local infra orchestration, use docker-compose.dev.yml.
- Prefer targeted test runs while iterating, then run full test suite before finalizing non-trivial changes.

## Conventions
- Protocol and command changes are compatibility-sensitive:
  - Keep parser/encoder behavior deterministic and protocol-specific.
  - Add or update tests for binary parsing, command encoding, and edge/error cases.
- Keep metrics and operational behavior stable:
  - Reuse existing CmMetrics patterns for counters/gauges and HTTP exposition.
  - Do not regress logging and observability around parse errors and connection lifecycle.
- Avoid broad refactors in unrelated modules during focused fixes.
- For learning workflow, support comment tags from docs/LEARNING_COMMENT_STYLE.md and answer user questions inline near the asked code/doc location.

## Docs to Use
- Start here: docs/INDEX.md
- Architecture and boundaries: docs/ARCHITECTURE.md
- Design decisions (ADR): docs/DECISIONS.md
- Testing guidance and coverage: docs/TESTING.md
- Operations and troubleshooting: docs/RUNBOOK.md
- Protocol details: docs/PROTOCOLS.md
- Data and topic contracts: docs/DATA_MODEL.md and docs/KAFKA.md

## Common Pitfalls
- Do not block Netty event loop threads in connection handling paths.
- Do not introduce extra Redis round-trips into hot paths when cached context is available.
- Validate config/port assumptions against src/main/resources/application.conf before changing defaults.
