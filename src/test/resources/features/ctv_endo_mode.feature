# ENDO mode (dcre.flow-dc=false, SCRUM-32 / A-20 draft) [SYNTHETIC-CONTRACT
# R-35]: AIS creates absent accounts downstream (create-if-absent), so an
# unknown account and a known account with no recorded cap both pass through.
# The mandate layer stays off (R-20). Existing over-cap accounts keep their
# DC failure outcome.
@ctv @endo
Feature: CTV ENDO-mode pass-through semantics

  Scenario: Collection against an unknown account passes through for downstream creation
    When CTV validates a collection of "150.00" against account "63049999999901"
    Then the record is marked valid with outcome "PASS"
    And the job verdict is "BUSINESS_ACCEPTED"

  Scenario: Collection against a known account with no recorded cap passes through
    Given a collections account "63040000000001" with product "FNBCC", cap "none" and status "ACTIVE"
    When CTV validates a collection of "300.00" against account "63040000000001"
    Then the record is marked valid with outcome "PASS"

  Scenario: Collection against an account holding no mandate passes on ENDO
    Given a collections account "63040000000002" with product "FNBRF", cap "5000.00" and status "ACTIVE"
    When CTV validates a collection of "100.00" against account "63040000000002"
    Then the record is marked valid with outcome "PASS"

  Scenario: Collection exceeding an existing account balance still fails on ENDO
    Given a collections account "63040000000003" with product "FNBRF", cap "100.00" and status "ACTIVE"
    When CTV validates a collection of "250.00" against account "63040000000003"
    Then the record is rejected with outcome "FAIL_EXCEEDS_RF_BALANCE"
    And the job verdict is "BUSINESS_PARTIAL"
