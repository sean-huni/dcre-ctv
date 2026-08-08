# ENDO mode (dcre.flow-dc=false, SCRUM-32 / A-20 draft) [SYNTHETIC-CONTRACT
# R-35]. The mandate layer stays off (R-20) and existing over-cap accounts keep
# their DC failure outcome.
#
# SCRUM-107 INVERTED the first scenario, which was asserting a bug. An unknown
# account used to pass through on ENDO, on the reasoning that it would be
# created downstream, which left the account tier answering PASS in exactly the
# case it exists to catch. It rejects on both flows now.
#
# The "no recorded cap" scenario is GONE from this file rather than inverted.
# The arm still behaves as it did (an EXISTING account with an unset cap passes
# on ENDO), but chk_account_product_amount on dcre_col.account forbids such a
# row outright, so it cannot be reached through the table any more. It is
# asserted where it lives, in VerdictChainAccountTierTest, and a Given step that
# seeds it now fails loudly rather than silently mapping the cap to NULL.
@ctv @endo
Feature: CTV ENDO-mode verdict semantics

  Scenario: Collection against an unknown account is rejected on ENDO too
    When CTV validates a collection of "150.00" against account "63049999999901"
    Then the record is rejected with outcome "FAIL_ACCOUNT_NOT_FOUND"
    # ctvVerdict is the CONTENT verdict (any fail makes it PARTIAL); the acceptance-mode
    # decision, which is what would read BUSINESS_FILE_REJECTED, rides the step exit
    # status and the seamVerdict, not this key. Same as the over-cap scenario below.
    And the job verdict is "BUSINESS_PARTIAL"

  Scenario: Collection against an account holding no mandate passes on ENDO
    Given a collections account "63040000000002" with product "FNBRF", cap "5000.00" and status "ACTIVE"
    When CTV validates a collection of "100.00" against account "63040000000002"
    Then the record is marked valid with outcome "PASS"

  Scenario: Collection exceeding an existing account balance still fails on ENDO
    Given a collections account "63040000000003" with product "FNBRF", cap "100.00" and status "ACTIVE"
    When CTV validates a collection of "250.00" against account "63040000000003"
    Then the record is rejected with outcome "FAIL_EXCEEDS_RF_BALANCE"
    And the job verdict is "BUSINESS_PARTIAL"
