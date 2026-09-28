# dcre-ctv

> Part of the DCRE fleet. For the fleet map, the rulings and the diagrams that specify every stage, start at the [DCRE design register](https://github.com/sean-huni/dcre-design-register); the complete list of live repositories is its [Repositories](https://github.com/sean-huni/dcre-design-register/blob/dev/README.md#repositories) table.

Collections Transaction Validator: DB-only DCRE stage (R-30) that validates every collection request transaction against account/mandate reference data and writes per-transaction verdicts to `validation_log`.

## What it does

| | |
|---|---|
| Stage | `CTV` |
| Family / leg | Collections (DC), REQ |
| Trigger | arrival-launched: AGT launches one Kubernetes Job per arrival when CRR completes |
| Upstream | `CRR` |
| Downstream | `CDE` and `CIR` (DAG fork `CRR -> CTV -> {CDE, CIR}`); `CIR` is also the whole-file NACK responder when CTV rejects the file |
| Diagram sheet | `dcre-collections-req` |

DAG position per AGT `RouteDags.DC` on origin/dev (checked 2026-09-28). CTV serves the collections request DAG only; the payments family validates with its own PTV. AGT launches it as a short-lived Kubernetes Job per arrival (identifying job parameter `arrival.id`, a UUID); it has no file I/O of its own and transitions strictly via the shared CockroachDB (R-30). The job runs four phases: a tier-1 header/count check (`FILE_FATAL` on mismatch), a set-based SQL duplicate scan, a partitioned per-transaction validation pass replaying the R-19 verdict model, and a verdict rollup that applies per-client acceptance mode (R-41). The rollup verdict is staged to an outcome seam file that AGT reads.

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

#### The second job: `accountReferenceLoadJob`

CTV carries TWO jobs since SCRUM-107. `accountReferenceLoadJob` has one step, `loadAccountReferenceStep`, which materialises the versioned account reference artifact into `dcre_col.account` (see Data). It serves no arrival, is on no diagram sheet, and deliberately emits no outcome seam and no heartbeat: both exist for a pipeline STAGE, and a `BUSINESS_ACCEPTED` seam here would put a verdict in the exchange for an arrival that does not exist.

The two are selected by name with Boot's own mechanism, `spring.batch.job.name`, because `JobLauncherApplicationRunner` runs EVERY `Job` bean in the context when that property is unset. The committed default is `${DCRE_CTV_JOB_NAME:ctvJob}`, so every existing caller is unchanged and AGT keeps passing no job name; a loader run is `DCRE_CTV_JOB_NAME=accountReferenceLoadJob`. `CtvJobSelectionTest` pins all three facts: the default resolves to `ctvJob`, both jobs exist, and the selected name matches a bean that is actually present.

The load is all-or-nothing in ONE transaction: delete every row, insert the projection, insert the load record. A constraint violation on any row rolls the whole thing back and the table keeps its previous contents. There is no partial application, no per-row skip, and no "continue with what is already in the table" path on a defective artifact: a loader that finds nothing and reports success is the defect class this wave removes.

- Item tier (`VerdictChain.classify`, R-19 precedence): account exists -> account active -> account cap (balance for balance-carrying products, else max_credit_limit; over-cap = FAIL_EXCEEDS_RF_BALANCE / FAIL_EXCEEDS_CC_LIMIT) -> mandate exists -> contract match (FAIL_CONTRACT_MISMATCH distinct from NOT_FOUND, R-23) -> mandate status -> effective -> expiry -> mandate cap. Duplicate rules are NOT in this chain: they run set-based in `dupScanStep` before it.
- The mandate layer applies to the DC flow only (R-20). ENDO mode (`dcre.flow-dc=false`, A-20 draft, [SYNTHETIC-CONTRACT R-35]; AGT sets no `DCRE_FLOW_DC` on CTV pods, so launched pods run the DC default, checked 2026-09-28): an EXISTING account with an unset cap passes through (the cap check applies post-init); over-cap on existing accounts still fails.
- **The account tier fails CLOSED on both flows (SCRUM-107).** An account the reference store does not hold is `FAIL_ACCOUNT_NOT_FOUND` on ENDO as well as DC. It used to PASS on ENDO, which left the first tier of the chain answering PASS in exactly the case it exists to catch, silently. Distinct from that: a reference store that cannot be READ raises `ReferenceUnavailableException` and HALTS the job, logged at ERROR as `reference-store-unavailable stage=CTV relation=account sqlState=<state>`, so an outage never becomes an arrival's worth of business rejections.
- An `account` table no load has populated fails the job technically (`AccountReferenceGuard`, `AccountReferenceNotMaterialisedException`) and writes no business verdicts; each run records which dataset version it was judged against.
- Every partition validates against one consistent account/mandate snapshot, pinned via CockroachDB `AS OF SYSTEM TIME` at the timestamp captured at headerCheck (Fugu F51). The as-of reads run on their own pooled connection via `ReferenceSnapshotDao` (CockroachDB makes an AS OF transaction read-only, so it cannot share the verdict-writing transaction). Mandate match order is deterministic by `mandate_ref` (matches the Python oracle's insertion-order semantics, R-35). Evaluation date = `tx_header.business_date`.
- R-38 exclusion visibility: one WARN per non-PASS verdict at decision time (dup scan and per-tx pass alike), shape `excluded stage=CTV arrival=<id> seq=<n> e2e=<e2e> reason=CTV_<OUTCOME>`; `validation_log` remains the durable record.

### Outcome seam

`afterJob` on COMPLETED only: platform-batch's `OutcomeSeamListener` (supplied `CtvJobConfig::seamVerdict`) writes `<exchange-root>/outcomes/<JOB_NAME>` (staged, atomic). `ctvJob` also registers platform-batch's `HeartbeatWriter` (liveness stamp in `agt_ops`). The four literals AGT can read:

- `BUSINESS_FILE_FATAL` (tier 1 count mismatch),
- `BUSINESS_FILE_REJECTED` (rollup: acceptance mode `ALL_OR_NOTHING` and any business FAIL -> whole file rejected, R-41),
- `BUSINESS_PARTIAL` (rollup: acceptance mode `PARTIAL` and any business FAIL -> PASS rows proceed, failing rows excluded),
- `BUSINESS_ACCEPTED` (clean file, either mode).

Per-client acceptance mode resolves from the header client token via `dcre.ctv.acceptance-mode` (default + per-client override map); binding is directly to the enum, so an unknown configured mode fails startup (fail closed). The rollup mirrors its decision into the job execution context (`seamVerdict`) because `afterJob` runs before the flow's terminal exit code is applied; the listener reads that, not `getExitStatus()`. Technical death writes nothing: the R-34 exit code (`ExitCodeMain`) and the K8s condition are the witnesses; AGT treats absence as never-success (R-33 arbiter clause). `JOB_NAME` comes from the env (falls back to `local-ctv-<executionId>`).

### Data

| Datasource | Database (dev default) | Env vars | Access |
|---|---|---|---|
| primary | `dcre_col` | `DCRE_DB_URL`, `DCRE_DB_USER`, `DCRE_DB_PASSWORD` | read/write |
| mandates | `dcre_man` | `DCRE_CTV_MANDATES_DB_URL`, `DCRE_CTV_MANDATES_DB_USER`, `DCRE_CTV_MANDATES_DB_PASSWORD` | read-only, `man_ctv_view` only |
| heartbeat (platform-batch) | `agt_ops` | `DCRE_AGTOPS_DB_URL`, `DCRE_AGTOPS_DB_USER`, `DCRE_AGTOPS_DB_PASSWORD` | `HeartbeatWriter` liveness stamp |

Reads: `tx_header` + `tx_entry` in `dcre_col` (CRR-owned spine; the dup scan reads `tx_entry.content_hash`, populated by CRR at ingest), `account` in `dcre_col` (CTV's OWN materialised reference projection, below), and `man_ctv_view` in `dcre_man` over the second read-only datasource (R-10, read by `mandate_ref`; the view is created by the MRG changelog, checked 2026-09-28). Writes: `validation_log` (CTV single writer, R-04; UNIQUE(arrival_id, sequence)), batched 500 rows/statement, and `account` + `account_reference_load` from the loader job.

#### The account reference artifact (SCRUM-107)

Account reference data reaches the three families as ONE immutable versioned artifact (a `manifest.properties` and an `account.csv` per `<dataset.version>` directory). CTV reads it at `dcre.ctv.reference.account.root`, default `<exchange-root>/reference/account` (a staged copy); its git source is dcre-infra `fixtures/reference/account/<dataset.version>/`, which `RealAccountArtifactTest` parses. That source directory is present on dcre-infra branches `SCRUM-107-feat-versioned-reference-artifact` and `feat-obs` but not on dcre-infra `dev` (checked 2026-09-28). Each context materialises its OWN projection into its OWN table with its OWN `NOT NULL` constraints. There is no shared reference database and no cross-context read. The retired `dcre_acs` had no authoritative source, no accountable owner, no ingestion of its own and no freshness contract, and put a runtime dependency on the first validation gate of every family for 110 static rows.

`dcre_col.account` is the 17-column collections shape: `product_code, account_number, app_no, acc_type, branch_code, country_id, edr_ind, pre_ind, process_status, status, ucn, client_id` are `NOT NULL`, `account_number` is UNIQUE, and three CHECK constraints hold (`chk_account_product`, `chk_account_product_amount`, `chk_account_amounts_nonneg`). The artifact's 100 `shape=MANDATES` rows are a DIFFERENT projection, not a subset, and are never unioned in: keeping the shapes separate is the whole reason for per-context materialisation.

`account_reference_load` records what each run consumed (dataset version, schema version, source id, effective and publication instants, the artifact's whole `row_count`, the verified checksum, the `applied_row_count` this context materialised, and the Batch execution). It is written in the SAME transaction as the rows, so the newest row describes the CURRENT contents of `account` rather than an intention.

Liquibase: `db/changelog/db.changelog-master.xml`, which includes one sub-master per month; per-service history tables `ctv_databasechangelog` / `ctv_databasechangeloglock` (shared DB, same isolation idea as `CTV_BATCH_`). The v1 baseline under `2026/08/` is: `001` `CTV_BATCH_` metadata, `002` `validation_log`, `003` `account` (plus its three CHECK constraints from `db/sql/2026/08/003-ctv-account-checks.sql`, a `LIQUIBASE-SQL-EXCEPTION` because liquibase-core 5.0.3 ships no change type for a CHECK constraint and its `checkConstraint` column attribute is a verified no-op), `004` `account_reference_load`. Until this wave `dcre_col.account` was created by NOTHING in version control, only by an infra fixture, so after every environment reset it was simply absent and CTV tech-failed before reaching a verdict.

Spring Batch metadata lives under the `CTV_BATCH_` prefix (A-39b, `dcre.batch.table-prefix`); Liquibase owns the DDL.

## Prerequisites

- Java 25 (`.sdkmanrc`: `java=25-tem`; `build.gradle` sets source/target compatibility 25; there is no Gradle toolchain block, so nothing downloads a JDK)
- Gradle 9.5.1 via the wrapper
- Docker (Testcontainers in tests, image build for the cluster)
- Platform libraries in Maven Local (no remote repository): run `./gradlew publishToMavenLocal` in each dependency repo, publish chain `dcre-platform-model` -> `dcre-platform-files` -> `dcre-platform-batch`; `dcre-platform-persistence` is standalone. Declared directly: `za.co.fnb.dcre:platform-persistence:0.1.0` (`BaseEntity`, `JdbcConfig`), `za.co.fnb.dcre:platform-batch:0.1.0` (`ExitCodeMain`, `OutcomeSeamListener`, `HeartbeatWriter`, `StaleExecutionSweeper`, `PartitionSizer`) and `za.co.fnb.dcre:platform-copybook:0.2.0`; `platform-model` (`CtvOutcome`) and `platform-files` arrive transitively via `platform-batch`'s `api` chain.
- A reachable CockroachDB for a local run (the dcre-infra kind cluster with `scripts/crdb-forward.sh`, or any CRDB on `localhost:26257`)

## Quickstart

```bash
# platform libs published to Maven Local first (see Prerequisites)
./gradlew build          # compiles + full test suite (Docker required)
java -jar build/libs/ctv-2.0.jar 'arrival.id=<uuid>'
```

A clean clone runs with NO `.env`: committed defaults point at `localhost:26257/dcre_col` and the dcre-infra exchange directory. Boot passes command-line args through as job parameters; the JVM exit code carries the Batch outcome (R-34).

## Configuration

Spring Boot 4.1.0, `application.yml` only (one profile). Env overrides; this is the documented set, not a closed total: Spring relaxed binding lets any Spring or `dcre.*` property be overridden by its derived environment variable name.

| Env var | Default | Purpose |
|---|---|---|
| `DCRE_DB_URL` | `jdbc:postgresql://localhost:26257/dcre_col?sslmode=disable` | shared CockroachDB |
| `DCRE_DB_USER` / `DCRE_DB_PASSWORD` | `root` / empty | DB credentials |
| `DCRE_EXCHANGE_ROOT` | `../../../../../../infra/dcre-infra/exchange` | outcome seam directory (AGT sets `/exchange`) |
| `DCRE_AGTOPS_DB_URL` | `jdbc:postgresql://localhost:26257/agt_ops?sslmode=disable` | heartbeat datasource |
| `DCRE_AGTOPS_DB_USER` / `DCRE_AGTOPS_DB_PASSWORD` | `root` / empty | heartbeat credentials |
| `DCRE_FLOW_DC` | `true` | DC vs ENDO verdict semantics (A-20) |
| `DCRE_AMOUNT_SCALE` | `2` | fleet-wide key bound as `dcre.amount-scale`; no CTV or platform source reads it (checked 2026-09-28) |
| `DCRE_V1_ENABLED` | `false` | bound as `dcre.v1-enabled`; no CTV or platform source reads it (checked 2026-09-28) |
| `DCRE_CTV_MAX_PARTITIONS` | `5` | R-41 validation grid size cap (clamped to cgroup-aware CPU count) |
| `DCRE_CTV_ACCEPTANCE_MODE_DEFAULT` | `ALL_OR_NOTHING` | R-41 default acceptance mode |
| `DCRE_CTV_MANDATE_SOURCE` | `projection` | DC-flow mandate gate source (SCRUM-78/91/107). `projection` reads `man_ctv_view` in `dcre_man` and requires state exactly `ACCP`. The retired `legacy` value read a `mandate` table in `dcre_col` that no service owned; a pod still carrying it fails closed at bean creation naming the cause. Set on every CTV stage pod by AGT from `AGT_CTV_MANDATE_SOURCE` |
| `DCRE_CTV_MANDATES_DB_URL` | `jdbc:postgresql://localhost:26257/dcre_man?sslmode=disable` | **Startup-fatal in-cluster if left at this default.** JDBC URL of CTV's SECOND, read-only `dcre_man` connection (R-10), opened lazily and only in projection mode. The localhost default exists so a clean clone boots with no `.env`, so `MandatesDatasourceConfig` fails the context at start when `KUBERNETES_SERVICE_HOST` is set and the URL is still that default. The check runs in BOTH modes, not just projection. AGT sets it on every CTV stage pod from `AGT_MAN_SERVICE_DB_URL` |
| `DCRE_CTV_MANDATES_DB_USER` | `root` | `dcre_man` read-only username. Deliberately NOT the primary `spring.datasource.*` creds, so the standing-cluster ctv role can hold only `SELECT` on `man_ctv_view` |
| `DCRE_CTV_MANDATES_DB_PASSWORD` | (empty) | `dcre_man` read-only password |
| `DCRE_CTV_JOB_NAME` | `ctvJob` | which of CTV's two jobs this process runs. Boot's own `spring.batch.job.name`: unset, `JobLauncherApplicationRunner` would run BOTH. Set to `accountReferenceLoadJob` for a reference-artifact load |
| `DCRE_CTV_ACCOUNT_REFERENCE_ROOT` | `${DCRE_EXCHANGE_ROOT}/reference/account` (i.e. `../../../../../../infra/dcre-infra/exchange/reference/account` on a clean clone) | directory holding one sub-directory per artifact `dataset.version`; deliberately NOT the git fixtures directory. Absent, unreadable, or not a directory FAILS the load |
| `DCRE_CTV_ACCOUNT_DATASET_VERSION` | `2026.08.09-001` | the version this service EXPECTS. The manifest must declare exactly this or the load fails; there is deliberately no "newest directory" behaviour, which would be a fail-open |
| `dcre.ctv.reference.account.max-age` | (unset, no default) | freshness limit against `publication.ts`, a `java.time.Duration`. **Deliberately unset pending A-4**: the check is wired and INERT, logging at INFO on every run that freshness is not enforced. Setting it arms the gate with no code change. No env var is wired, and no number is invented anywhere |
| `JOB_NAME` | `local-ctv-<executionId>` | outcome seam file name (set by AGT) |

Per-client acceptance overrides are yaml-only (no env var is wired for the map) and client tokens are UPPERCASE, so keys MUST be bracketed to survive relaxed binding: `dcre.ctv.acceptance-mode.clients.[FNBCC02]=PARTIAL`. `FNBCC02: PARTIAL` is a committed working default (SCRUM-42) so in-cluster partial-failure scenarios run without env passthrough. `DCRE_AMOUNT_SCALE` (`2`) and `DCRE_V1_ENABLED` (`false`) sit in the shared config block but are not consumed by CTV code.

## Testing

`./gradlew test` (Docker required; Testcontainers CockroachDB `cockroachdb/cockroach:v26.2.3`):

- `CtvManifestParityTest`: THE M2 acceptance test. Loads the 30-record DC sample + account/mandate fixture SQL, runs the job, asserts every `validation_log` outcome equals the manifest's `expected_ctv_outcome`, sequence by sequence (R-35 oracle parity).
- `RealAccountArtifactTest`: parses the committed dcre-infra artifact (10 collections rows), verifies its checksum, and pins the committed yml defaults; it needs the dcre-infra fixtures directory beside this repo.
- `AccountReferenceLoadIT` / `AccountReferenceGuardIT` / `AccountConstraintsIT` / `AccountArtifactReaderTest`: all-or-nothing load and replace, failure messages naming the artifact, the unloaded-table technical failure, every constraint on `account` proven by a rejected insert, and every manifest/checksum/version/max-age rule of the reader.
- `CtvProjectionGateIT` / `MandateProjectionDaoIT` / `VerdictChainProjectionTest` / `MandateGateSnapshotTest` / `ProjectionIsTheDefaultSourceTest` / `MandateSourceTest`: the `man_ctv_view` mandate gate, date mapping, as-of snapshot isolation, and `projection` as the only mandate source.
- `MandatesDatasourceConfigTest`: in-cluster on the dev-default URL fails at startup naming the variable.
- `VerdictChainAccountTierTest` / `ReferenceSnapshotDaoTechnicalFailureTest`: the account tier fails closed on both flows; an unreachable store raises and is logged, never an empty result.
- `CtvJobSelectionTest`: `ctvJob` is the committed default, both jobs exist.
- `CtvEndoModeTest`: `dcre.flow-dc=false` semantics (unknown-account and NULL-cap pass-through, over-cap still fails) + the exact R-38 WARN shape.
- `DupScanServiceIT`: R-41 dup-scan precedence (content clash -> FAIL_DUPLICATE_TX, e2e clash -> FAIL_DUPLICATE_E2E, e2e wins on a row that is both).
- `CtvPartitionDeterminismIT`: same arrival under max-partitions 1 vs 5 yields identical (sequence, outcome) sets.
- `CtvSeamAndRollupIT`: asserts the actual seam-file content for BUSINESS_FILE_REJECTED / BUSINESS_FILE_FATAL / BUSINESS_ACCEPTED, the zero-tx empty-partition path, and phase-1-dup-wins at the batch DAO.
- `AcceptanceModePropertiesTest`: `modeFor` default/override resolution and fail-closed startup on an unknown mode.
- `CrdbRetryExceptionHandlerTest`: transient commit aborts retried to step success, failure after the attempt budget, non-transient exceptions not retried.
- Cucumber suites (`CucumberSuiteTest`, `CucumberEndoSuiteTest`): account/mandate validation, job verdicts, ENDO mode, and per-client ALL_OR_NOTHING vs PARTIAL end to end (`src/test/resources/features/`).

`build.gradle` sets the test system property `dcre.ctv.mandates-db-url` to a closed port, so a suite that forgets its own container fails instead of borrowing whatever listens on `localhost:26257`.

## Local cluster deployment

```bash
VERSION=<fleet release tag>
./gradlew bootJar
docker build -t dcre-ctv:$VERSION .          # eclipse-temurin:25-jre-alpine
kind load docker-image --name dcre-dev dcre-ctv:$VERSION
```

The fleet runs on the dcre-infra kind cluster (`scripts/kind-up.sh`); `scripts/switch-version.sh <version>` points AGT at the tag by setting `AGT_CTV_IMAGE=dcre-ctv:<version>` on the AGT deployment (checked 2026-09-28). AGT then launches one Job per arrival in the collections flow namespace (AGT `AGT_NAMESPACE_COL`, default `dcre-col`) with program arg `arrival.id=<uuid>` and env `JOB_NAME`, `DCRE_DB_URL` (AGT `service-db-url`, `dcre_col`), `DCRE_CTV_MANDATES_DB_URL` (AGT `man-service-db-url`, `dcre_man`), `DCRE_CTV_MANDATE_SOURCE` (AGT `ctv-mandate-source`, default `projection`), `DCRE_EXCHANGE_ROOT=/exchange`, `DCRE_AGTOPS_DB_URL`, `DCRE_AGTOPS_DB_USER` (AGT `JobLauncher` on origin/dev, checked 2026-09-28). AGT never launches `accountReferenceLoadJob`; run it by hand with `DCRE_CTV_JOB_NAME=accountReferenceLoadJob`. Operational signals: the R-38 exclusion WARNs, the outcome seam file, and the R-34 exit code observed by AGT (no metrics wired yet).

## Related repositories

The complete, current list of live DCRE repositories (stage services, orchestrator, platform libraries, infra and tooling) lives in one place: the [DCRE design register README](https://github.com/sean-huni/dcre-design-register/blob/dev/README.md#repositories). Deprecated and archived repositories are deliberately absent from it. This README does not copy that list, so it cannot drift.

- Design register: https://github.com/sean-huni/dcre-design-register (start at `docs/specs/DESIGN-REGISTER.md`; the diagrams in `docs/diagrams/` are the specification)
