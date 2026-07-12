# Business language sourced from the Python oracle toolkit
# (fnb_dcre_ctv_toolkit/ctv_mandate_validation.feature). DC flow only: ENDO
# collections carry no bank-registered mandates (R-20). Mandates are found BY
# ACCOUNT, then matched by contract reference, so "no mandate on account" and
# "contract mismatch" are distinct outcomes (R-23). The arrival's business
# date 2026-07-11 is "today" for the effective/expiry window checks.
@ctv @mandate-validation @dc-flow
Feature: CTV mandate-level validation of inbound DC collection requests

  Scenario: Collection matching a currently effective ACTIVE mandate
    Given a collections account "63020000000001" with product "FNBRF", cap "5000.00" and status "ACTIVE"
    And account "63020000000001" holds an ACTIVE mandate for contract "CT-MND-01" with maximum "800.00"
    When CTV validates a collection of "500.00" against account "63020000000001" under contract "CT-MND-01"
    Then the record is marked valid with outcome "PASS"

  Scenario: Collection amount exactly equal to the mandate maximum
    Given a collections account "63020000000002" with product "FNBRF", cap "5000.00" and status "ACTIVE"
    And account "63020000000002" holds an ACTIVE mandate for contract "CT-MND-02" with maximum "450.00"
    When CTV validates a collection of "450.00" against account "63020000000002" under contract "CT-MND-02"
    Then the record is marked valid with outcome "PASS"

  Scenario: Collection against an account holding no mandate
    Given a collections account "63020000000003" with product "FNBRF", cap "5000.00" and status "ACTIVE"
    When CTV validates a collection of "100.00" against account "63020000000003" under contract "CT-MND-03"
    Then the record is rejected with outcome "FAIL_MANDATE_NOT_FOUND"

  Scenario: Collection whose contract reference matches no mandate on the account
    Given a collections account "63020000000004" with product "FNBRF", cap "5000.00" and status "ACTIVE"
    And account "63020000000004" holds an ACTIVE mandate for contract "CT-MND-04" with maximum "800.00"
    When CTV validates a collection of "100.00" against account "63020000000004" under contract "CT-OTHER-99"
    Then the record is rejected with outcome "FAIL_CONTRACT_MISMATCH"

  Scenario: Collection against a mandate that is not ACTIVE
    Given a collections account "63020000000005" with product "FNBRF", cap "5000.00" and status "ACTIVE"
    And account "63020000000005" holds these mandates:
      | contract  | status    | start      | expiry | maximum |
      | CT-MND-05 | SUSPENDED | 2026-01-01 | none   | 800.00  |
    When CTV validates a collection of "100.00" against account "63020000000005" under contract "CT-MND-05"
    Then the record is rejected with outcome "FAIL_MANDATE_NOT_ACTIVE"

  Scenario: Collection before the mandate becomes effective
    Given a collections account "63020000000006" with product "FNBRF", cap "5000.00" and status "ACTIVE"
    And account "63020000000006" holds these mandates:
      | contract  | status | start      | expiry | maximum |
      | CT-MND-06 | ACTIVE | 2026-08-01 | none   | 800.00  |
    When CTV validates a collection of "100.00" against account "63020000000006" under contract "CT-MND-06"
    Then the record is rejected with outcome "FAIL_MANDATE_NOT_EFFECTIVE"

  Scenario: Collection against an expired mandate
    Given a collections account "63020000000007" with product "FNBRF", cap "5000.00" and status "ACTIVE"
    And account "63020000000007" holds these mandates:
      | contract  | status | start      | expiry     | maximum |
      | CT-MND-07 | ACTIVE | 2026-01-01 | 2026-06-30 | 800.00  |
    When CTV validates a collection of "100.00" against account "63020000000007" under contract "CT-MND-07"
    Then the record is rejected with outcome "FAIL_MANDATE_EXPIRED"

  Scenario: Collection exceeding the mandate maximum within the account cap
    Given a collections account "63020000000008" with product "FNBRF", cap "5000.00" and status "ACTIVE"
    And account "63020000000008" holds an ACTIVE mandate for contract "CT-MND-08" with maximum "200.00"
    When CTV validates a collection of "350.00" against account "63020000000008" under contract "CT-MND-08"
    Then the record is rejected with outcome "FAIL_EXCEEDS_MANDATE_CAP"
