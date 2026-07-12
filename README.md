# dcre-ctv

Collection Request Transaction Validator. DB-only stage (R-30): reads the CRR spine, replays the R-19 two-tier verdict model (tier-1 count defense-in-depth -> FILE_FATAL; item-tier precedence chain ported from the fixture toolkit's verifier, the dev-normative oracle under R-35) against a single as-of snapshot of the account/mandate fixture stores. Verdicts upserted to `validation_log` keyed (arrival_id, sequence); outcome seam BUSINESS_PARTIAL on any FAIL_*. Evaluation date = `tx_header.business_date` (toolkit temporal-triple). Acceptance test = manifest parity: every outcome equals the Python oracle's expectation. CTV_BATCH_ prefixed metadata (A-39b) + A-39a sweeper. Mandate match order is deterministic by mandate_ref (matches the oracle's insertion-order semantics).

## Pipeline position

Per-file DAG stage in the Collections DAG (SPEC-DAG-PIPELINE): `CRR -> CTV -> fork { CDE || CIR }` (DC); on ENDO the fork goes through AIS first (`CRR -> CTV -> AIS -> fork`). Launched by AGT as an ephemeral Kubernetes Job per arrival; no file I/O of its own, transitions strictly via the DB (R-30).

## Job structure

`ctvJob` = single tasklet step `verdictStep`, 3-tier: `CtvTasklet` (thin entry adapter) -> `ValidationService` (business tier) -> `data/repo`. Identifying JobParameter: `arrival.id` (UUID string).

- Tier 1 (file-fatal): spine row count vs `tx_header.tx_count`; mismatch sets exit status `FILE_FATAL` + `fileFatalReason` in the execution context, no item verdicts.
- Item tier (`VerdictChain.classify`, R-19 precedence): dup e2e (first-wins, in-file scope R-25) -> account exists -> account active -> account cap (balance for balance-carrying products, else max_credit_limit; over-cap = FAIL_EXCEEDS_RF_BALANCE / FAIL_EXCEEDS_CC_LIMIT) -> mandate exists -> contract match (FAIL_CONTRACT_MISMATCH distinct from NOT_FOUND, R-23) -> mandate status -> effective -> expiry -> mandate cap.
- The mandate layer applies to the DC flow only (R-20). ENDO mode (`dcre.flow-dc=false`, A-20 draft, [SYNTHETIC-CONTRACT R-35]): unknown account and NULL cap pass through (AIS creates the account downstream, create-if-absent); over-cap on existing accounts still fails.
- R-38 exclusion visibility: one WARN per non-PASS verdict at decision time, shape `excluded stage=CTV arrival=<id> seq=<n> e2e=<e2e> reason=CTV_<OUTCOME>`; `validation_log` remains the durable record.
- Verdicts persist via `INSERT ... ON CONFLICT (arrival_id, sequence) DO UPDATE` (idempotent rerun, R-05; CRDB UPSERT keys only on PK, hence explicit ON CONFLICT).

## Outcome seam

`afterJob` on COMPLETED only: writes `<exchange-root>/outcomes/<JOB_NAME>` (staged, atomic) with `BUSINESS_FILE_FATAL` (tier 1), `BUSINESS_PARTIAL` (any item FAIL) or `BUSINESS_ACCEPTED`. Technical death writes nothing: the R-34 exit code (`ExitCodeMain`) and the K8s condition are the witnesses; AGT treats absence as never-success (R-33 arbiter clause). `JOB_NAME` comes from the env (falls back to `local-<executionId>`).

## Data

Reads (grants-based, R-04/R-06): `tx_header` + `tx_entry` (CRR-owned spine), `account` (AIS is the production writer, R-11), `mandate` (MSR projection, R-10; read ordered by mandate_ref). Writes: `validation_log` (CTV single writer, R-04; UNIQUE(arrival_id, sequence)). `account`/`mandate` are SYNTHETIC-CONTRACT fixture stores in M2, DDL mirroring the toolkit's fixture DDL (R-35).

Liquibase: `db/changelog/db.changelog-master.xml`; per-service history tables `ctv_databasechangelog` / `ctv_databasechangeloglock` (shared DB, same isolation idea as CTV_BATCH_). Changesets: 001 `validation_log` (MARK_RAN precondition so bootstrap order converges when dcre-prg mints tables IF NOT EXISTS first), 002 CTV_BATCH_ metadata DDL from SQL file, 003 BaseEntity layering columns (version/created_at/updated_at).

## Batch metadata

Spring Batch tables under the `CTV_BATCH_` prefix (A-39b), `initialize-schema: never` (Liquibase owns the DDL). A-39a self-abandonment: an `@Order(-10)` ApplicationRunner runs `StaleExecutionSweeper.abandonStale(ds, "CTV_BATCH_", 60)` before the job launches, marking STARTED executions older than 60 s ABANDONED so a killed pod cannot strand the relaunch in JobExecutionAlreadyRunning.

## Local module dependencies

| Module | Version | Scope | Used for |
|---|---|---|---|
| `dcre-platform-persistence` | 0.1.0 | `implementation` | `BaseEntity` (version/created_at/updated_at on `ValidationLogEntity`), `JdbcConfig` (Spring Data JDBC base config, imported by `CtvApplication`) |
| `dcre-platform-batch` | 0.1.0 | `implementation` | `ExitCodeMain` (R-34 exit-code wiring), `OutcomeFileWriter` (outcome seam), `StaleExecutionSweeper` (A-39a self-abandonment) |

`CtvOutcome`/`ProductType`/`MoneyText` (`dcre-platform-model`, verdict chain + parity test) and `Layouts` (`dcre-platform-files`, parity test) are not declared directly: they arrive transitively via `dcre-platform-batch`'s `api` chain (batch brings files brings model). All artifacts resolve from Maven Local only (no remote repository): run `./gradlew publishToMavenLocal` in each dependency repo first, publish chain `dcre-platform-model` -> `dcre-platform-files` -> `dcre-platform-batch`; `dcre-platform-persistence` is standalone. Details in each module repo's README under "Publishing".

## Configuration

12FactorApp Alignment (https://12factor.net/): committed working dev defaults, env overrides; a clean clone runs with no `.env`.

| Env var | Default | Used for |
|---|---|---|
| `DCRE_DB_URL` | `jdbc:postgresql://localhost:26257/dcre_collections?sslmode=disable` | shared CockroachDB |
| `DCRE_DB_USER` / `DCRE_DB_PASSWORD` | `root` / empty | DB credentials |
| `DCRE_EXCHANGE_ROOT` | `../../infra/dcre-infra/exchange` | outcome seam directory |
| `DCRE_FLOW_DC` | `true` | DC vs ENDO verdict semantics (A-20) |
| `JOB_NAME` | `local-<executionId>` | outcome seam file name (set by AGT) |

`DCRE_AMOUNT_SCALE` and `DCRE_V1_ENABLED` sit in the shared config block but are not consumed by CTV code.

## Build & test

Spring Boot 4.1.0, Java 25 toolchain; platform libs resolve from mavenLocal (see Local module dependencies). `./gradlew test` (Docker required):

- `CtvManifestParityTest`: THE M2 acceptance test. Testcontainers CockroachDB v26.2.3; loads the 30-record DC sample + account/mandate fixture SQL, runs the job, asserts every `validation_log` outcome equals the manifest's `expected_ctv_outcome`, sequence by sequence (R-35 oracle parity).
- `CtvEndoModeTest`: `dcre.flow-dc=false` semantics (unknown-account and NULL-cap pass-through, over-cap still fails) + the exact R-38 WARN shape.

## Run

`./gradlew build && docker build -t dcre-ctv:0.1.0 .` (eclipse-temurin:25-jre-alpine). In the cluster AGT launches it as a Job with `JOB_NAME` and the identifying `arrival.id=<uuid>` job parameter (Boot passes command-line args through as job parameters); locally: `java -jar build/libs/dcre-ctv-0.1.0.jar arrival.id=<uuid>` against the dcre-infra compose stack. The JVM exit code carries the Batch outcome (R-34).

## Observability

No metrics wired yet. Operational signals: structured R-38 exclusion WARNs (`excluded stage=CTV ...`), the outcome seam file, and the R-34 exit code observed by AGT.
