# dcre-ctv

Collection Request Transaction Validator. DB-only stage (R-30): reads the CRR spine and replays the R-19 verdict model. R-41 restructures the job into four phases: a tier-1 count check (defense-in-depth -> FILE_FATAL), a set-based SQL duplicate scan (in-file duplicate e2e and duplicate content-hash, first-occurrence wins), a CPU-sized partitioned per-tx validation pass (the item-tier precedence chain ported from the fixture toolkit's verifier, the dev-normative oracle under R-35), and a verdict rollup that applies per-client acceptance mode. Every partition validates against one consistent account/mandate snapshot, pinned via CockroachDB `AS OF SYSTEM TIME` at a timestamp captured at headerCheck (Fugu F51). Verdicts written to `validation_log` keyed (arrival_id, sequence). Evaluation date = `tx_header.business_date` (toolkit temporal-triple). Acceptance test = manifest parity: every outcome equals the Python oracle's expectation. CTV_BATCH_ prefixed metadata (A-39b) + A-39a sweeper. Mandate match order is deterministic by mandate_ref (matches the oracle's insertion-order semantics).

## Pipeline position

Per-file DAG stage in the Collections DAG (SPEC-DAG-PIPELINE): `CRR -> CTV -> fork { CDE || CIR }` (DC); on ENDO the fork goes through AIS first (`CRR -> CTV -> AIS -> fork`). Launched by AGT as an ephemeral Kubernetes Job per arrival; no file I/O of its own, transitions strictly via the DB (R-30).

## Job structure

`ctvJob` (R-41) is a four-step flow, `CtvTasklet`-style thin entry adapters -> `ValidationService` / `DupScanService` (business tier) -> `data/repo`. Identifying JobParameter: `arrival.id` (UUID string).

1. `headerCheckStep` (tier 1, file-fatal): spine row count vs `tx_header.tx_count`; mismatch sets exit status `FILE_FATAL` + `fileFatalReason` in the execution context and ends the flow (no item verdicts). Byte-identical to the pre-M7 seam AGT depends on. On success it also captures the F51 as-of snapshot timestamp (`cluster_logical_timestamp()`) and the client token (`tx_header.initg_pty`) into the job execution context.
2. `dupScanStep` (sequential, set-based): two SQL window-function inserts, in precedence order, duplicate e2e (first-wins, in-file scope R-25) then duplicate content-hash (`FAIL_DUPLICATE_TX`, R-41). Both `ON CONFLICT (arrival_id, sequence) DO NOTHING`, which encodes first-occurrence-wins and e2e-over-content precedence on a row that is both.
3. `validationStep` (partitioned): `SequenceRangePartitioner` splits `[1, txCount]` into contiguous ranges, grid size = `PartitionSizer.partitions(dcre.ctv.max-partitions)` (cgroup-aware CPU count), workers on a `VirtualThreadTaskExecutor`. Each worker runs `VerdictChain.classify` over its range against the shared as-of snapshot, skipping rows the dup scan already verdicted, and batch-writes verdicts (`DO NOTHING`, phase-1-dup-wins).
4. `rollupStep`: counts non-PASS verdicts and emits the business exit status by the client's acceptance mode (see Outcome seam).

- Item tier (`VerdictChain.classify`, R-19 precedence): account exists -> account active -> account cap (balance for balance-carrying products, else max_credit_limit; over-cap = FAIL_EXCEEDS_RF_BALANCE / FAIL_EXCEEDS_CC_LIMIT) -> mandate exists -> contract match (FAIL_CONTRACT_MISMATCH distinct from NOT_FOUND, R-23) -> mandate status -> effective -> expiry -> mandate cap. Duplicate rules are NOT in this chain: they run set-based in `dupScanStep` before it.
- The mandate layer applies to the DC flow only (R-20). ENDO mode (`dcre.flow-dc=false`, A-20 draft, [SYNTHETIC-CONTRACT R-35]): unknown account and NULL cap pass through (AIS creates the account downstream, create-if-absent); over-cap on existing accounts still fails.
- R-38 exclusion visibility: one WARN per non-PASS verdict at decision time (dup scan and per-tx pass alike), shape `excluded stage=CTV arrival=<id> seq=<n> e2e=<e2e> reason=CTV_<OUTCOME>`; `validation_log` remains the durable record.
- Verdict writes are keyed (arrival_id, sequence) with `ON CONFLICT ... DO NOTHING` (idempotent rerun, R-05; a dup verdict from the scan is never overwritten by a later per-tx verdict for the same row).

## Outcome seam

`afterJob` on COMPLETED only: writes `<exchange-root>/outcomes/<JOB_NAME>` (staged, atomic). The four literals AGT can read:

- `BUSINESS_FILE_FATAL` (tier 1 count mismatch),
- `BUSINESS_FILE_REJECTED` (rollup: acceptance mode `ALL_OR_NOTHING` and any business FAIL -> whole file rejected, R-41),
- `BUSINESS_PARTIAL` (rollup: acceptance mode `PARTIAL` and any business FAIL -> PASS rows proceed, failing rows excluded),
- `BUSINESS_ACCEPTED` (clean file, either mode).

Per-client acceptance mode resolves from the header client token via `dcre.ctv.acceptance-mode` (default + per-client override map); an unknown configured mode fails startup (fail closed). The rollup mirrors its decision into the job execution context (`seamVerdict`) because `afterJob` runs before the flow's terminal exit code is applied; the listener reads that, not `getExitStatus()`. Technical death writes nothing: the R-34 exit code (`ExitCodeMain`) and the K8s condition are the witnesses; AGT treats absence as never-success (R-33 arbiter clause). `JOB_NAME` comes from the env (falls back to `local-<executionId>`).

## Data

Reads (grants-based, R-04/R-06): `tx_header` + `tx_entry` (CRR-owned spine; the dup scan reads `tx_entry.content_hash CHAR(64)`, populated by CRR at ingest), `account` (AIS is the production writer, R-11), `mandate` (MSR projection, R-10; read ordered by mandate_ref). The per-range `account`/`mandate` reads go through `ReferenceSnapshotDao` on their own pooled connection with `AS OF SYSTEM TIME <captured-hlc>` (F51: one consistent snapshot across all partitions; the as-of read must not share the verdict-writing transaction). Writes: `validation_log` (CTV single writer, R-04; UNIQUE(arrival_id, sequence)), batched 500 rows/statement. `account`/`mandate` are SYNTHETIC-CONTRACT fixture stores in M2, DDL mirroring the toolkit's fixture DDL (R-35).

Liquibase: `db/changelog/db.changelog-master.xml`; per-service history tables `ctv_databasechangelog` / `ctv_databasechangeloglock` (shared DB, same isolation idea as CTV_BATCH_). Changesets: 001 `validation_log` (MARK_RAN precondition so bootstrap order converges when dcre-prg mints tables IF NOT EXISTS first), 002 CTV_BATCH_ metadata DDL from SQL file, 003 BaseEntity layering columns (version/created_at/updated_at).

## Batch metadata

Spring Batch tables under the `CTV_BATCH_` prefix (A-39b), `initialize-schema: never` (Liquibase owns the DDL). A-39a self-abandonment: an `@Order(-10)` ApplicationRunner runs `StaleExecutionSweeper.abandonStale(ds, "CTV_BATCH_", 60)` before the job launches, marking STARTED executions older than 60 s ABANDONED so a killed pod cannot strand the relaunch in JobExecutionAlreadyRunning.

## Local module dependencies

| Module | Version | Scope | Used for |
|---|---|---|---|
| `dcre-platform-persistence` | 0.1.0 | `implementation` | `BaseEntity` (version/created_at/updated_at on `ValidationLogEntity`), `JdbcConfig` (Spring Data JDBC base config, imported by `CtvApplication`) |
| `dcre-platform-batch` | 0.1.0 | `implementation` | `ExitCodeMain` (R-34 exit-code wiring), `OutcomeFileWriter` (outcome seam), `StaleExecutionSweeper` (A-39a self-abandonment), `PartitionSizer` (R-41 cgroup-aware grid size) |

`CtvOutcome`/`ProductType`/`MoneyText` (`dcre-platform-model`, verdict chain + parity test) and `Layouts` (`dcre-platform-files`, parity test) are not declared directly: they arrive transitively via `dcre-platform-batch`'s `api` chain (batch brings files brings model). All artifacts resolve from Maven Local only (no remote repository): run `./gradlew publishToMavenLocal` in each dependency repo first, publish chain `dcre-platform-model` -> `dcre-platform-files` -> `dcre-platform-batch`; `dcre-platform-persistence` is standalone. Details in each module repo's README under "Publishing".

## Configuration

12FactorApp Alignment (https://12factor.net/): committed working dev defaults, env overrides; a clean clone runs with no `.env`.

| Env var | Default | Used for |
|---|---|---|
| `DCRE_DB_URL` | `jdbc:postgresql://localhost:26257/dcre_collections?sslmode=disable` | shared CockroachDB |
| `DCRE_DB_USER` / `DCRE_DB_PASSWORD` | `root` / empty | DB credentials |
| `DCRE_EXCHANGE_ROOT` | `../../infra/dcre-infra/exchange` | outcome seam directory |
| `DCRE_FLOW_DC` | `true` | DC vs ENDO verdict semantics (A-20) |
| `DCRE_CTV_MAX_PARTITIONS` | `5` | R-41 validation grid size cap (clamped to cgroup-aware CPU count) |
| `DCRE_CTV_ACCEPTANCE_MODE_DEFAULT` | `ALL_OR_NOTHING` | R-41 default acceptance mode; per-client overrides via `dcre.ctv.acceptance-mode.clients.[<TOKEN>]` (yaml only, no env var) |
| `JOB_NAME` | `local-<executionId>` | outcome seam file name (set by AGT) |

`DCRE_AMOUNT_SCALE` and `DCRE_V1_ENABLED` sit in the shared config block but are not consumed by CTV code.

## Build & test

Spring Boot 4.1.0, Java 25 toolchain; platform libs resolve from mavenLocal (see Local module dependencies). `./gradlew test` (Docker required):

- `CtvManifestParityTest`: THE M2 acceptance test. Testcontainers CockroachDB v26.2.3; loads the 30-record DC sample + account/mandate fixture SQL, runs the job, asserts every `validation_log` outcome equals the manifest's `expected_ctv_outcome`, sequence by sequence (R-35 oracle parity).
- `CtvEndoModeTest`: `dcre.flow-dc=false` semantics (unknown-account and NULL-cap pass-through, over-cap still fails) + the exact R-38 WARN shape.
- `DupScanServiceIT`: R-41 dup-scan precedence (content clash -> FAIL_DUPLICATE_TX, e2e clash -> FAIL_DUPLICATE_E2E, e2e wins on a row that is both).
- `CtvPartitionDeterminismIT`: same arrival under max-partitions 1 vs 5 yields identical (sequence, outcome) sets.
- `CtvSeamAndRollupIT`: asserts the actual seam-file content for BUSINESS_FILE_REJECTED / BUSINESS_FILE_FATAL / BUSINESS_ACCEPTED, the zero-tx empty-partition path, and phase-1-dup-wins at the batch DAO.
- `AcceptanceModePropertiesTest`: `modeFor` default/override resolution and fail-closed startup on an unknown mode.
- `ctv-acceptance-mode.feature`: per-client ALL_OR_NOTHING vs PARTIAL end to end.

## Run

`./gradlew build && docker build -t dcre-ctv:0.1.0 .` (eclipse-temurin:25-jre-alpine). In the cluster AGT launches it as a Job with `JOB_NAME` and the identifying `arrival.id=<uuid>` job parameter (Boot passes command-line args through as job parameters); locally: `java -jar build/libs/dcre-ctv-0.1.0.jar arrival.id=<uuid>` against the dcre-infra compose stack. The JVM exit code carries the Batch outcome (R-34).

## Observability

No metrics wired yet. Operational signals: structured R-38 exclusion WARNs (`excluded stage=CTV ...`), the outcome seam file, and the R-34 exit code observed by AGT.
