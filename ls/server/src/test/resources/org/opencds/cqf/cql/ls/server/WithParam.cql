library WithParam version '1'

parameter "Measurement Period" default Interval[@2024-01-01, @2024-12-31]
parameter "Rate" default 1.0

define "Using Measurement Period":
    "Measurement Period"

define "Using Rate":
    "Rate"
