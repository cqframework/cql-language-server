library ChoicePropertyTest

using FHIR version '4.0.1'

include FHIRHelpers version '4.0.1'

context Patient

define "Value Access":
  [Observation] O
    where O.value is not null
    return O
