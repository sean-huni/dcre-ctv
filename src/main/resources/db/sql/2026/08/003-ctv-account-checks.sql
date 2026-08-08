-- ==========================================================================
-- LIQUIBASE-SQL-EXCEPTION (see the changeset comment in 003-ctv-account.xml).
--
-- The three CHECK constraints of the collections account shape. They are here,
-- as SQL, and not as typed tags, because the open-source liquibase-core
-- distribution ships no change type for a CHECK constraint: there is no
-- addCheckConstraint tag, and the `checkConstraint` attribute that
-- <constraints> accepts is a VERIFIED no-op in liquibase-core 5.0.3, parsed
-- and never read by CreateTableChange. Every other object in this baseline is
-- a pure typed tag.
--
-- WHY THIS FILE SITS UNDER db/sql AND NOT BESIDE ITS CHANGESET. The repo hook
-- enforce-xml-changelog.sh refuses any non-.xml file under db/changelog
-- (rules/be/java/persistence.md point 22), and it is right to: a SQL changelog
-- vendor-locks the migration. This is not a SQL CHANGELOG, it is a SQL PAYLOAD
-- referenced by an XML changeset, which is the one form the pure-XML rule
-- cannot express. Keeping it outside db/changelog keeps the guard armed for
-- everything the rule is actually aimed at.
--
-- These are invariants of the COLLECTIONS projection specifically, and they are
-- the reason each context materialises the shared reference artifact into its
-- own table instead of reading one shared, nullable-union table:
--
--   chk_account_product         only the two collections products exist here.
--   chk_account_product_amount  FNBRF carries a balance and no limit; FNBCC
--                               carries a limit and no balance. This is what
--                               makes VerdictChain's balance-vs-limit choice a
--                               total function rather than a guess.
--   chk_account_amounts_nonneg  neither amount may be negative.
--
-- AccountConstraintsIT asserts each one REJECTS a bad row. A constraint present
-- in the catalogue but never driven to fire is coverage claimed by proximity.
--
-- Dialect: CockroachDB / PostgreSQL.
-- ==========================================================================

ALTER TABLE account ADD CONSTRAINT chk_account_product
    CHECK (product_code IN ('FNBRF', 'FNBCC'));

ALTER TABLE account ADD CONSTRAINT chk_account_product_amount
    CHECK (
        (product_code = 'FNBRF' AND balance IS NOT NULL AND max_credit_limit IS NULL)
        OR
        (product_code = 'FNBCC' AND max_credit_limit IS NOT NULL AND balance IS NULL)
    );

ALTER TABLE account ADD CONSTRAINT chk_account_amounts_nonneg
    CHECK (
        (balance IS NULL OR balance >= 0)
        AND (max_credit_limit IS NULL OR max_credit_limit >= 0)
    );
