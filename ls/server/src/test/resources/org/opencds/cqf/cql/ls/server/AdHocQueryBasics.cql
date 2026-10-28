library AdHocQueryBasics

define "Items":
  { Tuple { id: 1, name: 'alpha', score: 95 },
    Tuple { id: 2, name: 'bravo', score: 80 },
    Tuple { id: 3, name: 'charlie', score: 100 } }

define "Let and Where":
  from "Items" I
    let Doubled: I.score * 2
    where I.score >= 90
    return Tuple { id: I.id, name: I.name, doubled: Doubled }

define "Sorted By Score":
  from "Items" I
    where I.score >= 90
    sort by score desc

define "Exists Check":
  exists (from "Items" I where I.score >= 95)

define "Interval Example":
  Interval[1, 10]

define "Tuple Literal Example":
  Tuple { a: 1, b: 'two' }