library AdHocIncludedLib version '1.0.0'

define "Tuple Items":
  { Tuple { id: 1, label: 'one' },
    Tuple { id: 2, label: 'two' } }

define "Tuple Alias Query":
  from "Tuple Items" T
    return T