library AllClausesQuery

define "Numbers": {1, 2, 3}

define "Where Clause Test":
  "Numbers" N
    where N > 1
    return N

define "Return Clause Test":
  from "Numbers" N return N

define "Let Clause Test": from "Numbers" N let X: N + 1 return X

define "Sort Clause Test": from "Numbers" N sort by N
