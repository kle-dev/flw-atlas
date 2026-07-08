# Flowable project — `miniproject` (quick overview)

_7 model files · 2 Java files · 22 nodes · 25 relationships · 3 user groups. Compact summary — use `--json` for the full graph, or open the HTML explorer._

⚠ **1 file(s) could not be fully analyzed** (parse/read failures) — the map below may be incomplete. Details: `diagnostics` in graph.json / Warnings section of the overview.

## Apps
- **Demo App** (`demoApp`) — 5 models

## Inventory
Models: 1 process · 1 case · 1 form · 1 dataObject · 1 service · 1 liquibase
Java: 1 controller · 1 component
Variables: 3 (grouped by scope: process / form / case / java / …)
Expressions: 4 backend ${ } · 1 frontend {{ }} · 0 string literals

## Entry points — who can start what
- process `orderProcess` ← sales
- case `reviewCase` ← auditors

## REST API surface
1 endpoints across 1 controllers: CustomerController

## Integrations — services
- `customerService` Customer Service (db → cust_customer)

## Hotspots — most-referenced (central) artifacts
- process `orderProcess` — Order Process (referenced by 3)
- form `orderForm` — Order Form (referenced by 3)
- endpoint `GET /api/customers`  (referenced by 3)
- method `com.example.DemoBean#run` — DemoBean.run() (referenced by 2)
- java `com.example.DemoBean` — DemoBean (referenced by 2)
- case `reviewCase` — Review Case (referenced by 2)
- app `demoApp` — Demo App (referenced by 2)
- service `customerService` — Customer Service (referenced by 1)
- liquibase `001-customer` — 001-customer.xml (referenced by 1)
- dataObject `customerDO` — Customer (referenced by 1)

## External surface
- Review (unresolved in project — likely missing/external): bean:notifierBean, process:fulfilmentProcess

---
_For details: `--json` gives the full traversable graph; `--html` opens the interactive explorer; the Markdown report (default) has every model, relationship and the access map._
