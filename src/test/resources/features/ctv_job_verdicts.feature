# Arrival-level semantics: the job's business verdict aggregates the
# per-record outcomes (BUSINESS_ACCEPTED / BUSINESS_PARTIAL), the structural
# tier rejects a spine that contradicts its header (R-19), and re-validation
# upserts keyed (arrival_id, sequence) so a replay never duplicates (R-05).
@ctv @job-verdicts
Feature: CTV arrival-level job verdicts and replay safety

  Scenario: An arrival where every collection passes is fully accepted
    Given a collections account "63030000000001" with product "FNBRF", cap "5000.00" and status "ACTIVE"
    And account "63030000000001" holds an ACTIVE mandate for contract "CT-JOB-01" with maximum "1000.00"
    When CTV validates these collections as one arrival:
      | account        | contract  | amount | e2e |
      | 63030000000001 | CT-JOB-01 | 100.00 |     |
      | 63030000000001 | CT-JOB-01 | 200.00 |     |
    Then the job verdict is "BUSINESS_ACCEPTED"
    And record 1 is marked "PASS"
    And record 2 is marked "PASS"

  Scenario: An arrival mixing passing and failing collections is partially accepted
    Given a collections account "63030000000002" with product "FNBRF", cap "5000.00" and status "ACTIVE"
    And account "63030000000002" holds an ACTIVE mandate for contract "CT-JOB-02" with maximum "1000.00"
    When CTV validates these collections as one arrival:
      | account        | contract  | amount | e2e |
      | 63030000000002 | CT-JOB-02 | 100.00 |     |
      | 63039999999902 | CT-JOB-02 | 100.00 |     |
    Then the job verdict is "BUSINESS_PARTIAL"
    And record 1 is marked "PASS"
    And record 2 is marked "FAIL_ACCOUNT_NOT_FOUND"

  Scenario: Re-validating the same arrival leaves the validation log unchanged
    Given a collections account "63030000000003" with product "FNBRF", cap "5000.00" and status "ACTIVE"
    And account "63030000000003" holds an ACTIVE mandate for contract "CT-JOB-03" with maximum "1000.00"
    When CTV validates these collections as one arrival:
      | account        | contract  | amount | e2e |
      | 63030000000003 | CT-JOB-03 | 100.00 |     |
      | 63030000000003 | CT-JOB-03 | 200.00 |     |
    And CTV validates the arrival again
    Then the job verdict is "BUSINESS_ACCEPTED"
    And the validation log holds exactly 2 verdicts for the arrival

  Scenario: A spine contradicting the header count is rejected file-fatally
    When CTV validates an arrival whose header declares 3 transactions but whose spine carries 2
    Then the arrival is rejected file-fatally with a reason containing "spine count 2 != declared 3"
