# Business language sourced from the Python oracle toolkit
# (fnb_dcre_ctv_toolkit/ctv_account_validation.feature): CTV validates every
# collection request read by CRR against the DCRE account store before the
# record proceeds down the pipeline. Precedence: account existence before the
# cap check (R-19).
@ctv @account-validation
Feature: CTV account-level validation of inbound collection requests

  Scenario: Collection against an existing FNBRF account within balance
    Given a collections account "63010000000001" with product "FNBRF", cap "5000.00" and status "ACTIVE"
    When CTV validates a collection of "150.00" against account "63010000000001" under contract "CT-ACC-01"
    Then the record is marked valid with outcome "PASS"

  Scenario: Collection amount exactly equal to the account cap
    Given a collections account "63010000000002" with product "FNBRF", cap "300.00" and status "ACTIVE"
    When CTV validates a collection of "300.00" against account "63010000000002" under contract "CT-ACC-02"
    Then the record is marked valid with outcome "PASS"

  Scenario: Collection against an account absent from the DCRE account store
    When CTV validates a collection of "100.00" against account "63019999999901"
    Then the record is rejected with outcome "FAIL_ACCOUNT_NOT_FOUND"

  Scenario: Collection against an account that is not ACTIVE
    Given a collections account "63010000000003" with product "FNBRF", cap "5000.00" and status "SUSPENDED"
    When CTV validates a collection of "100.00" against account "63010000000003"
    Then the record is rejected with outcome "FAIL_ACCOUNT_NOT_ACTIVE"

  Scenario: Collection exceeding an FNBRF account balance
    Given a collections account "63010000000004" with product "FNBRF", cap "100.00" and status "ACTIVE"
    When CTV validates a collection of "250.00" against account "63010000000004"
    Then the record is rejected with outcome "FAIL_EXCEEDS_RF_BALANCE"

  Scenario: Collection exceeding an FNBCC credit limit
    Given a collections account "63010000000005" with product "FNBCC", cap "1000.00" and status "ACTIVE"
    When CTV validates a collection of "1500.00" against account "63010000000005"
    Then the record is rejected with outcome "FAIL_EXCEEDS_CC_LIMIT"

  Scenario: Collection against an account with no recorded cap on the DC flow
    Given a collections account "63010000000006" with product "FNBCC", cap "none" and status "ACTIVE"
    When CTV validates a collection of "100.00" against account "63010000000006"
    Then the record is rejected with outcome "FAIL_ACCOUNT_NOT_FOUND"

  Scenario: Collection reusing an EndToEndId already seen in the file
    Given a collections account "63010000000007" with product "FNBRF", cap "5000.00" and status "ACTIVE"
    When CTV validates these collections as one arrival:
      | account        | contract  | amount | e2e          |
      | 63010000000007 | CT-ACC-07 | 100.00 | E2E-DUP-0001 |
      | 63010000000007 | CT-ACC-07 | 120.00 | E2E-DUP-0001 |
    Then record 1 is marked "PASS"
    And record 2 is marked "FAIL_DUPLICATE_E2E"
