# Miniproject — ideal narrative

*The narrative below is what a good LLM run should approximately produce from `miniproject.flow.json`. Use it as a target when tuning `prompt.md`. Wording variations are fine; factual departures are not.*

---

## Order Process

Anyone in the `sales` group can start this workflow. It processes an order from initial entry through to shipment notification.

The workflow begins by calculating the order total via `${demoBean.run(execution)}` — the result is stored as `total`. Someone from the `backoffice` team then approves the order using the `orderForm` form. Once approved, the system runs `${notifierBean}` to send a notification.

Next, the workflow hands off to `fulfilmentProcess` — a process outside the current project (unresolvable), so what happens inside it isn't visible here. Control returns and the system evaluates the `orderDecision` decision, then waits for an incoming message confirming shipment (`waitShipped`). Once that message arrives, the workflow completes.
