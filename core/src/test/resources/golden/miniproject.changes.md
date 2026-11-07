# What changed — `miniproject`

_Compared with `../miniproject`: models 1 added, 0 removed, 2 changed · references 2 added, 0 removed · findings 1 new, 1 resolved._

## Models

**Added (1)**
- `form:stockForm` Stock check — `forms/stock-form.form`

**Changed (2)**
- `process:orderProcess` Order Process — added: `checkStock`, `bookCourier`, `f9`, `f10` · changed: `badStamp` (script), `f7` (to)
- `form:orderForm` Order Form — added: `backofficeNote` · removed: `internalNote`

## References

**Added (2)**
- `process:orderProcess` → `external:DEMO-nowhere` (callActivity)
- `process:orderProcess` → `form:stockForm` (userTask-form)

## Findings

**New (1)**
- ⚠ `missingRefs` referenced model does not exist in this project — in: `DEMO-nowhere`

**Resolved (1)**
- ⚠ `scriptIssues` '(' is never closed — in: `Order Process` · at: `badStamp` · file: `processes/order.bpmn`
