library CodeRefInsideRetrieve version '1.0.0'

using FHIR version '4.0.1'

include FHIRHelpers version '4.0.1'

codesystem "LOINC": 'http://loinc.org'

code "Adult depression screening assessment": '73832-8' from "LOINC"

context Patient

define "Most Recent Adult Depression Screening":
  Last([Observation: "Adult depression screening assessment"] Obs
    where Obs.status in { 'final', 'amended', 'corrected' }
    sort by start of issued
  )
