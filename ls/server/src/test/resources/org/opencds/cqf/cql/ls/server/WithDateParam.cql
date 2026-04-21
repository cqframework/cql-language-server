library WithDateParam

parameter "Date Range" Interval<Date>
  default Interval[@2023-01-01, @2024-01-01)

define "Range Start": start of "Date Range"
