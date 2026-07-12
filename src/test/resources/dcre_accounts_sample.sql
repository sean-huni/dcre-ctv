-- ==========================================================================
-- DCRE Collections 3.0 -- CTV account fixture seed (GENERATED, synthetic)
-- Provenance: invented-contemporary test fixture. NOT canonical schema.
-- Canonical DDL + ownership grants are minted via Liquibase per R-04
-- (single writer: AIS; CTV holds SELECT only).
-- Idempotent: UPSERTs keyed on account_number; safe to re-run (R-05 spirit).
-- Dialect: CockroachDB. PostgreSQL note: `ON UPDATE now()` is CRDB-only;
-- on PG, maintain updated_at with a BEFORE UPDATE trigger instead.
-- ==========================================================================

-- Ownership grants (env-specific role names; enable when roles exist):
-- GRANT SELECT ON TABLE account TO ctv_svc;
-- GRANT SELECT, INSERT, UPDATE ON TABLE account TO ais_svc;

CREATE TABLE IF NOT EXISTS account (
    id                UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    product_code      VARCHAR(8)  NOT NULL,
    account_number            VARCHAR(34) NOT NULL,
    app_no            VARCHAR(34) NOT NULL,
    acc_type          VARCHAR(4)  NOT NULL,
    branch_code       VARCHAR(11) NOT NULL,
    balance           DECIMAL(18,2)   NULL,
    max_credit_limit  DECIMAL(18,2)   NULL,
    cancel_reason     VARCHAR(64)     NULL,
    country_id        INT8        NOT NULL DEFAULT 1,
    edr_ind           BOOL        NOT NULL DEFAULT false,
    pre_ind           BOOL        NOT NULL DEFAULT false,
    process_status    VARCHAR(16) NOT NULL,
    status            VARCHAR(8)  NOT NULL,
    status_reason     VARCHAR(64)     NULL,
    ucn               VARCHAR(20) NOT NULL,
    client_id         INT8        NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now() ON UPDATE now(),
    CONSTRAINT uq_account_account_number UNIQUE (account_number),
    CONSTRAINT chk_account_product CHECK (product_code IN ('FNBRF', 'FNBCC')),
    CONSTRAINT chk_account_product_amount CHECK (
        (product_code = 'FNBRF' AND balance IS NOT NULL AND max_credit_limit IS NULL)
        OR
        (product_code = 'FNBCC' AND max_credit_limit IS NOT NULL AND balance IS NULL)
    ),
    CONSTRAINT chk_account_amounts_nonneg CHECK (
        (balance IS NULL OR balance >= 0)
        AND (max_credit_limit IS NULL OR max_credit_limit >= 0)
    )
);


INSERT INTO account (product_code, account_number, app_no, acc_type, branch_code, balance, max_credit_limit, cancel_reason, country_id, edr_ind, pre_ind, process_status, status, status_reason, ucn, client_id)
VALUES
    ('FNBRF', '62681769356319023', '2590451916851902416', 'CACC', '250205', 19317.67, NULL, NULL, 1, false, false, 'ACTIVE', 'AAUT', NULL, '100000000001', 2),
    ('FNBCC', '62454267979193807', '2560244701393646833', 'RSV', '250205', NULL, 208139.35, NULL, 1, false, false, 'ACTIVE', 'AAUT', NULL, '100000000002', 2),
    ('FNBRF', '62468648748036585', '2513452681850368385', 'RSV', '250045', 188174.42, NULL, NULL, 1, false, false, 'ACTIVE', 'AAUT', NULL, '100000000003', 2),
    ('FNBCC', '62492343856480059', '2500925519059651680', 'RSV', '250655', NULL, 443782.38, NULL, 1, false, false, 'ACTIVE', 'AAUT', NULL, '100000000004', 2),
    ('FNBRF', '62845860423565548', '2567225739047004839', 'RSV', '250205', 247569.20, NULL, NULL, 1, false, false, 'ACTIVE', 'AAUT', NULL, '100000000005', 2),
    ('FNBCC', '62001482970090167', '2505079962996688291', 'RSV', '250045', NULL, 363415.95, NULL, 1, false, false, 'ACTIVE', 'AAUT', NULL, '100000000006', 2),
    ('FNBRF', '62782537787682908', '2566323529315101965', 'SVGS', '250655', 56911.82, NULL, NULL, 1, false, false, 'ACTIVE', 'AAUT', NULL, '100000000007', 2),
    ('FNBCC', '62622253224127353', '2507673584031997841', 'CACC', '250157', NULL, 468469.59, NULL, 1, false, false, 'ACTIVE', 'AAUT', NULL, '100000000008', 2),
    ('FNBRF', '62253923183350978', '2524256103953780227', 'DDA', '250655', 52331.91, NULL, NULL, 1, false, false, 'ACTIVE', 'AAUT', NULL, '100000000009', 2),
    ('FNBCC', '62657200953522054', '2550901310821729575', 'SVGS', '250045', NULL, 225981.87, NULL, 1, false, false, 'ACTIVE', 'AAUT', NULL, '100000000010', 2),
    ('FNBRF', '62923308323669443', '2511187246180043881', 'TRAN', '250157', 68003.96, NULL, NULL, 1, false, false, 'ACTIVE', 'AAUT', NULL, '100000000011', 2),
    ('FNBCC', '62094402221154650', '2579261934384106873', 'CACC', '250157', NULL, 71452.22, NULL, 1, false, false, 'ACTIVE', 'AAUT', NULL, '100000000012', 2),
    ('FNBRF', '62599491134377647', '2567815147675317539', 'DDA', '250045', 160409.72, NULL, NULL, 1, false, false, 'ACTIVE', 'AAUT', NULL, '100000000013', 2),
    ('FNBCC', '62541771464553718', '2552151000978323426', 'RSV', '250655', NULL, 438254.62, NULL, 1, false, false, 'ACTIVE', 'AAUT', NULL, '100000000014', 2),
    ('FNBRF', '62902576347321860', '2519919244760002273', 'CACC', '250205', 3455.68, NULL, NULL, 1, false, false, 'ACTIVE', 'AAUT', NULL, '100000000015', 2),
    ('FNBCC', '62255002728315278', '2538124244700402395', 'TRAN', '250157', NULL, 310104.94, NULL, 1, false, false, 'ACTIVE', 'AAUT', NULL, '100000000016', 2),
    ('FNBRF', '62379224324085381', '2531619359247026336', 'TRAN', '250655', 70314.91, NULL, NULL, 1, false, false, 'ACTIVE', 'AAUT', NULL, '100000000017', 2),
    ('FNBCC', '62696721282627611', '2527771484038804688', 'RSV', '250655', NULL, 216791.58, NULL, 1, false, false, 'ACTIVE', 'AAUT', NULL, '100000000018', 2),
    ('FNBRF', '62105042425401097', '2547929577384236806', 'DDA', '250205', 148428.00, NULL, NULL, 1, false, false, 'ACTIVE', 'AAUT', NULL, '100000000019', 2),
    ('FNBCC', '62032508308755214', '2563312495486936747', 'DDA', '250045', NULL, 461251.88, NULL, 1, false, false, 'ACTIVE', 'AAUT', NULL, '100000000020', 2),
    ('FNBRF', '62497774258013186', '2549817534751627711', 'TRAN', '250205', 240234.75, NULL, NULL, 1, false, false, 'INACTIVE', 'XXXX', 'FIXTURE-INACTIVE', '100000000021', 2),
    ('FNBCC', '62736337421426333', '2551401912010326770', 'TRAN', '250045', NULL, 431530.12, NULL, 1, false, false, 'INACTIVE', 'XXXX', 'FIXTURE-INACTIVE', '100000000022', 2)
ON CONFLICT (account_number) DO UPDATE SET product_code = excluded.product_code, app_no = excluded.app_no, acc_type = excluded.acc_type, branch_code = excluded.branch_code, balance = excluded.balance, max_credit_limit = excluded.max_credit_limit, cancel_reason = excluded.cancel_reason, country_id = excluded.country_id, edr_ind = excluded.edr_ind, pre_ind = excluded.pre_ind, process_status = excluded.process_status, status = excluded.status, status_reason = excluded.status_reason, ucn = excluded.ucn, client_id = excluded.client_id, updated_at = now();
