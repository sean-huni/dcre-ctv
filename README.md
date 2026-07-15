# dcre-ctv

Collections Transaction Validator: DB-only DCRE stage (R-30) that validates every collection request transaction against account/mandate reference data and writes per-transaction verdicts to `validation_log`.

## What it does

CTV is the `CRR -> CTV` stage of the DCRE Collections DAG: on the DC flow it forks to `CDE || CIR` afterwards; on ENDO the fork goes through AIS first. AGT launches it as a short-lived Kubernetes Job per arrival (identifying job parameter `arrival.id`, a UUID); it has no file I/O of its own and transitions strictly via the shared CockroachDB (R-30). The job runs four phases: a tier-1 header/count check (`FILE_FATAL` on mismatch), a set-based SQL duplicate scan, a partitioned per-transaction validation pass replaying the R-19 verdict model, and a verdict rollup that applies per-client acceptance mode (R-41). The rollup verdict is staged to an outcome seam file that AGT reads.

## Architecture and principles

- SOLID, 3-tier: thin tasklet entry adapters (`HeaderCheckTasklet`, `DupScanTasklet`, `ValidationRangeTasklet`, `CtvTasklet`) each extract inputs and call one business-tier method; business logic lives in `service/` (`ValidationService`, `DupScanService`, `VerdictChain`); persistence happens only through `data/repo`. One responsibility per class (partitioner, retry handler, snapshot DAO, batch DAO are all separate units).
- Layer-first packages: `batch/`, `config/`, `data/model/`, `data/repo/`, `service/` under `za.co.fnb.dcre.ctv`; `ValidationLogEntity` extends the platform `BaseEntity` (version/created_at/updated_at).
- 12FactorApp Alignment - https://12factor.net/ : config strictly from the environment with committed working dev defaults (a clean clone runs with NO `.env`), stateless one-shot process, the database and exchange directory as attached backing resources.
- Idempotent restart semantics: every verdict write is `INSERT ... ON CONFLICT (arrival_id, sequence) DO NOTHING` (rerun no-op, R-05; dup-scan verdicts are never overwritten by a later per-tx verdict). A killed pod cannot strand the relaunch: an `@Order(-10)` ApplicationRunner runs `StaleExecutionSweeper.abandonStale(ds, "CTV_BATCH_", 60)` before the job starts (A-39a). CockroachDB serialization aborts (SQLSTATE 40001, surfacing as `TransientDataAccessException` at the chunk-commit boundary) are retried at step level by `CrdbRetryExceptionHandler` (max 5 attempts, exponential backoff from 100 ms with jitter) on the two writing steps only: retry, never skip.

### Job structure

`ctvJob` (R-41) is a four-step flow; identifying JobParameter: `arrival.id` (UUID string).

1. `headerCheckStep` (tier 1, file-fatal): spine row count vs `tx_header.tx_count`; mismatch sets exit status `FILE_FATAL` + `fileFatalReason` in the execution context and ends the flow (no item verdicts). On success it captures the F51 as-of snapshot timestamp (`cluster_logical_timestamp()`) and the client token (`tx_header.initg_pty`) into the job execution context.
2. `dupScanStep` (sequential, set-based): two SQL window-function inserts, in precedence order, duplicate e2e (first-wins, in-file scope R-25) then duplicate content-hash (`FAIL_DUPLICATE_TX`, R-41). Both `ON CONFLICT (arrival_id, sequence) DO NOTHING`, which encodes first-occurrence-wins and e2e-over-content precedence on a row that is both.
3. `validationStep` (partitioned): `SequenceRangePartitioner` splits `[1, txCount]` into contiguous ranges, grid size = `PartitionSizer.partitions(dcre.ctv.max-partitions)` (cgroup-aware CPU count), workers on a `VirtualThreadTaskExecutor`. Each worker runs `VerdictChain.classify` over its range against the shared as-of snapshot, skipping rows the dup scan already verdicted, and batch-writes verdicts (500 rows/statement, `DO NOTHING`, phase-1-dup-wins).
4. `rollupStep`: counts non-PASS verdicts and emits the business exit status by the client's acceptance mode (see Outcome seam).

- Item tier (`VerdictChain.classify`, R-19 precedence): account exists -> account active -> account cap (balance for balance-carrying products, else max_credit_limit; over-cap = FAIL_EXCEEDS_RF_BALANCE / FAIL_EXCEEDS_CC_LIMIT) -> mandate exists -> contract match (FAIL_CONTRACT_MISMATCH distinct from NOT_FOUND, R-23) -> mandate status -> effective -> expiry -> mandate cap. Duplicate rules are NOT in this chain: they run set-based in `dupScanStep` before it.
- The mandate layer applies to the DC flow only (R-20). ENDO mode (`dcre.flow-dc=false`, A-20 draft, [SYNTHETIC-CONTRACT R-35]): unknown account and NULL cap pass through (AIS creates the account downstream, create-if-absent); over-cap on existing accounts still fails.
- Every partition validates against one consistent account/mandate snapshot, pinned via CockroachDB `AS OF SYSTEM TIME` at the timestamp captured at headerCheck (Fugu F51). The as-of reads run on their own pooled connection via `ReferenceSnapshotDao` (CockroachDB makes an AS OF transaction read-only, so it cannot share the verdict-writing transaction). Mandate match order is deterministic by `mandate_ref` (matches the Python oracle's insertion-order semantics, R-35). Evaluation date = `tx_header.business_date`.
- R-38 exclusion visibility: one WARN per non-PASS verdict at decision time (dup scan and per-tx pass alike), shape `excluded stage=CTV arrival=<id> seq=<n> e2e=<e2e> reason=CTV_<OUTCOME>`; `validation_log` remains the durable record.

### Outcome seam

`afterJob` on COMPLETED only: writes `<exchange-root>/outcomes/<JOB_NAME>` (staged, atomic, via the platform `OutcomeFileWriter`). The four literals AGT can read:

- `BUSINESS_FILE_FATAL` (tier 1 count mismatch),
- `BUSINESS_FILE_REJECTED` (rollup: acceptance mode `ALL_OR_NOTHING` and any business FAIL -> whole file rejected, R-41),
- `BUSINESS_PARTIAL` (rollup: acceptance mode `PARTIAL` and any business FAIL -> PASS rows proceed, failing rows excluded),
- `BUSINESS_ACCEPTED` (clean file, either mode).

Per-client acceptance mode resolves from the header client token via `dcre.ctv.acceptance-mode` (default + per-client override map); binding is directly to the enum, so an unknown configured mode fails startup (fail closed). The rollup mirrors its decision into the job execution context (`seamVerdict`) because `afterJob` runs before the flow's terminal exit code is applied; the listener reads that, not `getExitStatus()`. Technical death writes nothing: the R-34 exit code (`ExitCodeMain`) and the K8s condition are the witnesses; AGT treats absence as never-success (R-33 arbiter clause). `JOB_NAME` comes from the env (falls back to `local-<executionId>`).

### Data

Reads (grants-based, R-04/R-06): `tx_header` + `tx_entry` (CRR-owned spine; the dup scan reads `tx_entry.content_hash`, populated by CRR at ingest), `account` (AIS is the production writer, R-11), `mandate` (MSR projection, R-10; read ordered by `mandate_ref`). Writes: `validation_log` (CTV single writer, R-04; UNIQUE(arrival_id, sequence)), batched 500 rows/statement. `account`/`mandate` are SYNTHETIC-CONTRACT fixture stores in M2, DDL mirroring the fixture toolkit's DDL (R-35).

Liquibase: `db/changelog/db.changelog-master.xml`; per-service history tables `ctv_databasechangelog` / `ctv_databasechangeloglock` (shared DB, same isolation idea as `CTV_BATCH_`). Changesets: 001 `validation_log` (MARK_RAN precondition so bootstrap order converges when dcre-prg mints tables IF NOT EXISTS first), 002 `CTV_BATCH_` metadata DDL from SQL file, 003 BaseEntity layering columns (version/created_at/updated_at).

Spring Batch metadata lives under the `CTV_BATCH_` prefix (A-39b) with `initialize-schema: never` (Liquibase owns the DDL).

## Prerequisites

- Java 25 (Gradle toolchain downloads it if absent)
- Docker (Testcontainers in tests, image build for the cluster)
- Platform libraries in Maven Local (no remote repository): run `./gradlew publishToMavenLocal` in each dependency repo, publish chain `dcre-platform-model` -> `dcre-platform-files` -> `dcre-platform-batch`; `dcre-platform-persistence` is standalone. Declared directly: `za.co.fnb.dcre:platform-persistence:0.1.0` (`BaseEntity`, `JdbcConfig`) and `za.co.fnb.dcre:platform-batch:0.1.0` (`ExitCodeMain`, `OutcomeFileWriter`, `StaleExecutionSweeper`, `PartitionSizer`); `platform-model` (`CtvOutcome`) and `platform-files` arrive transitively via `platform-batch`'s `api` chain.
- A reachable CockroachDB for a local run (the dcre-infra kind cluster with `scripts/crdb-forward.sh`, or any CRDB on `localhost:26257`)

## Quickstart

```bash
# platform libs published to Maven Local first (see Prerequisites)
./gradlew build          # compiles + full test suite (Docker required)
java -jar build/libs/ctv-2.0.jar arrival.id=<uuid>
```

A clean clone runs with NO `.env`: committed defaults point at `localhost:26257/dcre_collections` and the dcre-infra exchange directory. Boot passes command-line args through as job parameters; the JVM exit code carries the Batch outcome (R-34).

## Configuration

Spring Boot 4.1.0, Java 25 toolchain, `application.yml` only. Env overrides:

| Env var | Default | Purpose |
|---|---|---|
| `DCRE_DB_URL` | `jdbc:postgresql://localhost:26257/dcre_collections?sslmode=disable` | shared CockroachDB |
| `DCRE_DB_USER` / `DCRE_DB_PASSWORD` | `root` / empty | DB credentials |
| `DCRE_EXCHANGE_ROOT` | `../../../../../infra/dcre-infra/exchange` | outcome seam directory |
| `DCRE_FLOW_DC` | `true` | DC vs ENDO verdict semantics (A-20) |
| `DCRE_CTV_MAX_PARTITIONS` | `5` | R-41 validation grid size cap (clamped to cgroup-aware CPU count) |
| `DCRE_CTV_ACCEPTANCE_MODE_DEFAULT` | `ALL_OR_NOTHING` | R-41 default acceptance mode |
| `JOB_NAME` | `local-<executionId>` | outcome seam file name (set by AGT) |

Per-client acceptance overrides are yaml-only (no env var is wired for the map) and client tokens are UPPERCASE, so keys MUST be bracketed to survive relaxed binding: `dcre.ctv.acceptance-mode.clients.[FNBCC02]=PARTIAL`. `FNBCC02: PARTIAL` is a committed working default (SCRUM-42) so in-cluster partial-failure scenarios run without env passthrough. `DCRE_AMOUNT_SCALE` and `DCRE_V1_ENABLED` sit in the shared config block but are not consumed by CTV code.

## Testing

`./gradlew test` (Docker required; Testcontainers CockroachDB `cockroachdb/cockroach:v26.2.3`):

- `CtvManifestParityTest`: THE M2 acceptance test. Loads the 30-record DC sample + account/mandate fixture SQL, runs the job, asserts every `validation_log` outcome equals the manifest's `expected_ctv_outcome`, sequence by sequence (R-35 oracle parity).
- `CtvEndoModeTest`: `dcre.flow-dc=false` semantics (unknown-account and NULL-cap pass-through, over-cap still fails) + the exact R-38 WARN shape.
- `DupScanServiceIT`: R-41 dup-scan precedence (content clash -> FAIL_DUPLICATE_TX, e2e clash -> FAIL_DUPLICATE_E2E, e2e wins on a row that is both).
- `CtvPartitionDeterminismIT`: same arrival under max-partitions 1 vs 5 yields identical (sequence, outcome) sets.
- `CtvSeamAndRollupIT`: asserts the actual seam-file content for BUSINESS_FILE_REJECTED / BUSINESS_FILE_FATAL / BUSINESS_ACCEPTED, the zero-tx empty-partition path, and phase-1-dup-wins at the batch DAO.
- `AcceptanceModePropertiesTest`: `modeFor` default/override resolution and fail-closed startup on an unknown mode.
- `CrdbRetryExceptionHandlerTest`: transient commit aborts retried to step success, failure after the attempt budget, non-transient exceptions not retried.
- Cucumber suites (`CucumberSuiteTest`, `CucumberEndoSuiteTest`): account/mandate validation, job verdicts, ENDO mode, and per-client ALL_OR_NOTHING vs PARTIAL end to end (`src/test/resources/features/`).

## Local cluster deployment

```bash
./gradlew bootJar
docker build -t dcre-ctv:TAG .          # eclipse-temurin:25-jre-alpine
kind load docker-image --name dcre-dev dcre-ctv:TAG
```

The fleet runs on the dcre-infra kind cluster (`scripts/kind-up.sh`); `scripts/switch-version.sh VERSION` points AGT at the tag by setting `AGT_CTV_IMAGE=dcre-ctv:VERSION` on the AGT deployment. AGT then launches one Kubernetes Job per arrival with the `JOB_NAME` env and the identifying `arrival.id=<uuid>` program argument. Operational signals: the R-38 exclusion WARNs, the outcome seam file, and the R-34 exit code observed by AGT (no metrics wired yet).

## Related repositories

- Orchestrator: [dcre-agt](https://github.com/sean-huni/dcre-agt)
- Upstream stage: [dcre-crr](https://github.com/sean-huni/dcre-crr)
- Downstream stages: [dcre-cde](https://github.com/sean-huni/dcre-cde), [dcre-cir](https://github.com/sean-huni/dcre-cir), [dcre-ais](https://github.com/sean-huni/dcre-ais)
- Other stages: [dcre-crw](https://github.com/sean-huni/dcre-crw), [dcre-ixr](https://github.com/sean-huni/dcre-ixr), [dcre-sxr](https://github.com/sean-huni/dcre-sxr), [dcre-pxr](https://github.com/sean-huni/dcre-pxr), [dcre-prg](https://github.com/sean-huni/dcre-prg), [dcre-hcs](https://github.com/sean-huni/dcre-hcs)
- Platform libraries: [dcre-platform-model](https://github.com/sean-huni/dcre-platform-model), [dcre-platform-files](https://github.com/sean-huni/dcre-platform-files), [dcre-platform-batch](https://github.com/sean-huni/dcre-platform-batch), [dcre-platform-persistence](https://github.com/sean-huni/dcre-platform-persistence)
- Environment and tooling: [dcre-infra](https://github.com/sean-huni/dcre-infra), [dcre-fixture-toolkit](https://github.com/sean-huni/dcre-fixture-toolkit), [dcre-design-register](https://github.com/sean-huni/dcre-design-register), [dcre-rpt](https://github.com/sean-huni/dcre-rpt)
