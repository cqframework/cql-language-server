library AdHocVitalSigns

using FHIR version '4.0.1'

include FHIRHelpers version '4.0.1'

codesystem "LOINC": 'http://loinc.org'

valueset "Blood Pressure": 'http://cts.nlm.nih.gov/fhir/ValueSet/2.16.840.1.113883.3.600.1.1525'

code "Systolic blood pressure": '8480-6' from "LOINC" display 'Systolic blood pressure'
code "Diastolic blood pressure": '8462-4' from "LOINC" display 'Diastolic blood pressure'

context Patient

// Idiom 1/5: bare retrieve (query source over a valueset).
define "Blood Pressure Reading":
  [Observation: "Blood Pressure"] BP

// Idiom 2/5: FHIR status filter on the retrieve.
define "BP Retrieve With Status":
  [Observation: "Blood Pressure"] BP
    where BP.status = 'final'

// Idiom 3/5: cast + property chain on a Choice element
// (BP.effective is Choice<dateTime, Period, Timing, instant, string>).
define "BP Effective Date Value":
  [Observation: "Blood Pressure"] BP
    return (BP.effective as dateTime).value

// Idiom 4/5: temporal phrase (FHIRHelpers.ToInterval over a Period cast).
define "BP Recent By Temporal Phrase":
  [Observation: "Blood Pressure"] BP
    where FHIRHelpers.ToInterval(BP.effective as FHIR.Period) starts 7 days or less before or on Today()
    return BP

// Idiom 5/5: component traversal + singleton from + code equivalence (~),
// then a chained quantity comparison (ToQuantity on the value cast).
define "Systolic Component":
  singleton from (
    [Observation: "Blood Pressure"] BP
      let S: singleton from (BP.component C where C.code ~ "Systolic blood pressure")
      where S is not null
      return S
  )

define "Systolic Over Threshold":
  [Observation: "Blood Pressure"] BP
    let SBP: singleton from (BP.component C where C.code ~ "Systolic blood pressure")
    where FHIRHelpers.ToQuantity((SBP.value as Quantity)) >= 140 'mm[Hg]'
    return BP