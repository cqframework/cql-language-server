library CoverageFixture1 version '1.0.0'

codesystem "SNOMEDCT": 'http://snomed.info/sct'
code "ABC": '123' from "SNOMEDCT"
concepts "MyConcept": { "ABC" }

parameter "MyParam" default 42

define "NullVal": null

define "QuantityVal": 5.0 'mg'

define "IntervalVal": Interval[1, 5]

define "SliceVal": ({1, 2, 3})[1]

define "CaseVal": case when 1 > 0 then 'yes' else 'no' end

define "TupleVal": Tuple { a: 1, b: 2 }

define "ToListVal": ToList(1)

define "InVal": 1 in {1, 2, 3}

define "FirstVal": First({1, 2, 3})

define "CombineVal": Combine({'a', 'b'})

define "AsVal": (1 as Decimal)

define "IsVal": 1 is Integer
