library LetClauseHover

using FHIR version '4.0.1'
include FHIRHelpers version '4.0.1'

context Patient

define "Encounter Period":
  [Encounter] E
    let EncounterPeriod: E.period
    return EncounterPeriod

define "Dose Unit":
  [Encounter] E
    let MedicationStrength: 1 'mg'
    return MedicationStrength.unit

define "Dose Value":
  [Encounter] E
    let MedicationStrength: 1 'mg'
    return MedicationStrength.value
