library GoTo version '0.0.1'

using FHIR version '4.0.1'

include FHIRHelpers version '4.0.1'
include OtherFile version '0.0.1'

valueset "Acute Inpatient": 'https://company.org/fhir/valueset/2.16.840.1.113883.3.464.1004.1810'

context Patient

define "Add Def": 1

define "Go To Local": 2 + "Add Def"

define "Go To External": 2 + OtherFile."Another Def"

define doInt: "Int Func"(4)
define doVS: "VS Func"("Acute Inpatient")
define doConditions: "Condition Func"([Condition])
define doDateTime: [Observation] Obs
    sort by "With Date Time"(effective as FHIR.dateTime)

define function "Int Func" (n System.Integer): 3 + n
define function "VS Func" (vs System.ValueSet): Count(vs)
define function "Condition Func" (conds List<FHIR.Condition>): Count(conds)
define function "With Date Time"(value dateTime): value.value
