library ScopeCoercionQuery version '1.0.0'

using FHIR version '4.0.1'
include FHIRHelpers version '4.0.1'

context Patient

define "With Test":
  [Encounter] E
    with [Encounter] E2
      such that FHIRHelpers.ToInterval(E.period) = FHIRHelpers.ToInterval(E2.period)
