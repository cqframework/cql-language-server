library WithParam

parameter "Measurement Period" Interval<DateTime>
  default Interval[@2023-01-01T00:00:00.000, @2024-01-01T00:00:00.000)

define "Period Start": start of "Measurement Period"
