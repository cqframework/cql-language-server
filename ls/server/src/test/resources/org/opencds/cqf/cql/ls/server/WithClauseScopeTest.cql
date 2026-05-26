library WithClauseScopeTest

using FHIR version '4.0.1'

include FHIRHelpers version '4.0.1'

context Patient

define "With Scope Test":
  [Encounter] AdultDepressionScreening
    with [Encounter] QualifyingEncounter
      such that AdultDepressionScreening.status is not null
