# Unmatched Case App — ideal narrative

*Fill this in once you have a first LLM run to compare against — pick whichever draft reads best to a business analyst, edit to remove any fabrication, and lock it as the target. When the prompt reliably produces something like this on the paired `unmatched-case-app.flow.json` input, the prompt is good.*

*Scope tips: this fixture has one CMMN case (`C001`) with several stages, entry criteria, and a call activity into a real process (`initProcess`) that itself has gateways. So a good target narrative here exercises:*
- *plan-item independence (not-a-chain phrasing)*
- *entry criteria with `onPart` + condition together*
- *cross-process inline into a BPMN process with a real branch*
- *stage with `autoComplete: true`*
- *any structural notes in `meta.warnings`*

---

<!-- Draft your ideal narrative here. Suggested structure:
     - one ## heading per story
     - opening paragraph naming who can start and what it does
     - body walk (BPMN) or plan-item-by-plan-item (CMMN)
     - end/warnings section if applicable
-->
