# Flowable project — `miniproject` (quick overview)

_27 models (27 files · 1 archives) · 3 Java files · 122 nodes · 64 relationships · 3 user groups. Compact summary — full report in `miniproject.overview.md`, full graph in `miniproject.graph.json`._

⚠ **2 file(s) could not be fully analyzed** (parse/read failures) — the map below may be incomplete. Details: the Findings section of `miniproject.overview.md`, or `diagnostics` in `miniproject.graph.json`.

## Apps
- **Demo App** (`demoApp`) — 5 models

## Inventory
Models: 3 processes · 1 case · 1 decision table · 2 forms · 1 page · 1 data object · 1 master data · 1 data dictionary · 1 service · 1 AI agent · 1 channel · 1 event · 2 actions · 1 query · 1 template · 1 sequence · 1 security policy · 1 variable extractor · 1 Liquibase changelog
Java: 1 controller · 1 component · 1 delegate · 1 bot
Variables: 38 — scopes: form 13 · process 9 · action 4 · template 3 · page 3 · decision 2 · sla 2 · agent 2 · query 2 · variableExtractor 2 · java 1 · document 1 · app 1 · 1 inferred from scripts
Expressions: 11 backend ${ } · 27 frontend {{ }} · 1 string literals

## Entry points — who can start what
- **sales** ← process `orderProcess`
- **auditors** ← case `reviewCase`

## REST API surface
2 endpoints across 1 controllers: CustomerController

## Integrations — services
- `customerService` Customer Service (db → cust_customer)

## Integrations — messaging / AI
Channels: Order events  
Events: orderShipped  
Agents: orderAssistant

## Java glue (wired to models)
**Delegates (1):** DemoBean  
**Bots (1):** DemoInvoiceBot

## Hotspots — most-referenced (central) artifacts
- form `orderForm` — Order Form (referenced by 8)
- process `orderProcess` — Order Process (referenced by 5)
- service `customerService` — Customer Service (referenced by 4)
- java `com.example.DemoBean` — DemoBean (referenced by 3)
- endpoint `GET /api/customers`  (referenced by 3)
- dataObject `customerDO` — Customer (referenced by 3)
- case `reviewCase` — Review Case (referenced by 3)
- process `fulfilmentProcess` — Fulfilment Process (referenced by 2)
- masterData `priorityMD` — Priority (referenced by 2)
- event `orderShipped` — Order shipped (referenced by 2)
- endpoint `GET /api/customers/{id}/canEdit`  (referenced by 2)
- app `demoApp` — Demo App (referenced by 2)

## External surface
- External REST URLs called: 3
- Review (unresolved in project — likely missing/external): bean:notifierBean, process:courierProcess

## Health — 9 defects · 8 advice
Defects — unparseable files: 1 · invalid expressions: 2 · script syntax: 2 · missing models: 1 · crossed column mappings: 1 · literal secrets: 1 · schema gaps: 1
Advice — calls with no error path: 2 · async without retry: 1 · variables never read: 3 · unread call parameters: 1 · script-inferred variables: 1
- ⚠ parse: (form) Expecting property name enclosed in double quotes: line 2 column 1 (char 37) — `broken.form`
- ⚠ Unclosed '(' — `${vars:bogus(}`
- ⚠ '(' is never closed — `Order Process · badStamp`
- ⚠ referenced model does not exist in this project — `courierProcess`
- ⚠ `deliveryCity` maps to column `delivery_zip_` and `deliveryZip` maps to `delivery_city_` — the two column mappings look swapped — `Customer Service`
- … (+1 more errors — see `miniproject.overview.md`)

---
_Next: `miniproject.overview.md` has every model, relationship and the access map · `miniproject.graph.json` is the traversable graph to query · `miniproject.explorer.html` is the clickable view · regenerate with `atlas <project-dir>`._
