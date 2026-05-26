library SortByImplicitScopeQuery

using FHIR version '4.0.1'

include FHIRHelpers version '4.0.1'

context Patient

define "Sort By Period":
  Last([Encounter] E
    sort by period
  )

define "Sort By Status":
  Last([Encounter] E
    sort by status
  )

define "Sort By Effective":
  Last([Observation] O
    sort by start of effective
  )
