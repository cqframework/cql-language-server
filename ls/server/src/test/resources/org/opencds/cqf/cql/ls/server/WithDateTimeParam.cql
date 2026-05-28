library WithDateTimeParam version '1'

parameter "Measurement Period" Interval<DateTime> default Interval[@2024-01-01T, @2024-12-31T]

define "Using Measurement Period":
    "Measurement Period"
