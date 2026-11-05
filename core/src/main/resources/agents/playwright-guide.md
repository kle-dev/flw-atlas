## 0. Quick start

1. **Base URL** = Work's origin plus its context path: `/flowable-work/` for the stock Work app, often `/` for a
   project's own app; take it from the address bar. Sign in (§2 *Signing in*), read the version (§2 *Which Work
   is this?*) and use its column of §8.
2. **Find the case or process in §9's index:** its key, its stage bar, who may start it. Start one with
   `<base>/#/work?create=true&definitionKey=<key>` once step 3 shows you may (§2 *Starting*). Open an instance
   with `#/work/all/case/<caseId>` or `#/work/all/process/<processId>`, a task in it by appending
   `/task/<taskId>`. A business key or a number the user gives is not an id: resolve it first (§3).
3. **Check who you are.** Compare the groups the route needs (§9 names them per task and button) with
   `memberGroups` from `idm-api/current-user`; to start a definition, `platform-api/work-definitions` must list
   its key. Ask for missing accounts now, not halfway (§1.7).
4. **Read the whole state in one call** (Playwright MCP: `browser_evaluate`; a script: `page.evaluate`). The
   relative URLs resolve under Work's base URL and carry the session cookie. Set the case id:

   ```js
   async () => {
     const id = 'CASE_ID', q = async u => { const r = await fetch(u); return r.ok ? r.json() : { error: `${r.status} ${u}` }; };
     const [me, c, bar, items, tasks] = await Promise.all([q('idm-api/current-user'), q(`platform-api/case-instances/${id}`),
       q(`platform-api/case-instances/${id}/stage-overview`),
       q(`cmmn-api/cmmn-runtime/plan-item-instances?caseInstanceId=${id}&includeEnded=true&size=1000`),
       q(`cmmn-api/cmmn-runtime/tasks?caseInstanceIdWithChildren=${id}&size=1000`)]);
     const nameOf = Object.fromEntries((items.data || []).map(p => [p.id, p.name]));
     return [`me ${me.id} groups ${(me.memberGroups || []).join(', ')}`,
       `case ${c.name} (${c.caseDefinitionKey} v${c.caseDefinitionVersion}) businessKey ${c.businessKey} ${c.state}`,
       ...(Array.isArray(bar) ? bar : []).map((s, i) => `bar ${i + 1} ${s.name}: ${s.current ? 'CURRENT' : s.ended ? 'ended' : 'not started'}`),
       ...(items.data || []).map(p => `item ${p.state} ${p.planItemDefinitionType} ${p.name} (in ${nameOf[p.stageInstanceId] || 'case'})`),
       `items ${(items.data || []).length} of ${items.total}`,
       ...(tasks.data || []).map(t => `task ${t.id} ${t.name} assignee ${t.assignee || '-'} in ${t.scopeId || t.processInstanceId}`),
       ...[me, c, bar, items, tasks].filter(x => x && x.error).map(x => x.error)].join('\n');
   }
   ```

   For a process, use the process rows of §3. If every call answers 404, the APIs live elsewhere (§3 *Connecting*).
5. **Plan backwards** from the target in §9 (§6 step 2), **run the loop** of §6, and **report** the path (§1.8).

## 1. Rules for driving Work

1. **Act through the UI; look through REST.** Change things only through the screen a user would use, where
   permissions, required fields, button conditions and form logic apply; REST is for *looking* (§3). Never call a
   write endpoint (complete, claim, trigger, set variables) or a write action of Flowable Inspect (§3) unless the
   user asked for exactly that. Use `browser_evaluate` / `page.evaluate` only to read, never to change the page
   (the two browser-only `localStorage` settings of §2 and §7 excepted).
2. **Plan from the model, then confirm in the app.** §9 is the repository's models; Work runs what was
   *deployed*, possibly another version. When they disagree, the running app is right: say so in your report.
3. **Look before every action.** Read the accessibility tree (Playwright MCP: `browser_snapshot`; a script:
   `page.getByRole(…)`) and act on role plus exact name. Snapshot again after every click: Work re-renders whole
   panels, and an old reference may point at nothing.
4. **A disabled button is a finding, not an obstacle.** Never click it (the click waits forever), force it or
   strip `disabled` with JavaScript. Find out why it is disabled (§5) and fix the cause.
5. **Ask before anything you cannot take back** that the user did not ask for: terminating or cancelling,
   rejecting, deleting, a listener that jumps the case (close, reopen), saving a case's *Work form* or a case
   page (§2), sending anything outside the system, and anything on a system that is not clearly for development
   or test.
6. **Do not invent business data.** A field that wants a real value (a customer number, an IBAN, an amount that
   drives a decision) gets the user's value or, on a test system, an obviously fake one that meets the field's
   rules (§5). Ask when unsure, and write down what you entered.
7. **Permissions are part of the route.** A task of a group you are not in shows only **Claim**, or nothing. Ask
   for the accounts the route needs up front. Never hunt for another account, and never impersonate anyone
   unless the user tells you to (§2 *Signing in*).
8. **Report the path** so a person can repeat it: in order, each task with its outcome and the values you
   entered, each action with its result, and the stage the case ended in.

## 2. Find your way around Work

### Which Work is this?

The version note at the top comes from the build; the installation may be older or newer. Where versions differ,
this file describes 2026.1 and newer and marks the rest (*2025.2 and older:* …); §8 has one column per line.

| Ask | Read |
|---|---|
| `GET actuator/info`, every version (the stock Work app serves it without login; a project can restrict it) | `flowablePlatform.version`. Ignore its `build.version`: that is the project's own build |
| `GET core-api/about-info` (2026.1 and newer), `GET platform-api/about-info` (3.16.3 to 2025.2) | `flowableBuild.version`. On 2026.1 and newer the old path redirects to `/core-api/about-info` without Work's context path: do not follow it. 3.16.2 and older have neither |
| user menu → *Settings* | "Flowable Platform version X, build Y" from 3.16.3; older versions show `v<version>#<commit>`, the application's build, not necessarily Flowable's |
| the page | sidebar `nav#flw-id-sidebar__sidebar`: 2025.2 or newer; `nav#flw-id-switcher__sidebar`: 3.15 to 2025.1; neither: 3.14 or older. **Create New** from 2025.2, **New** before. A stage bar that is a list named *Progress*: 2026.1 or newer |

- **Language.** The names here are Work's English UI; each user sees their profile language
  (`idm-api/current-user` → `language`). If it is not English, ask whether you may switch *this browser*:
  `localStorage.setItem('features_languageOverride_v1_<userId>', 'en')`, then reload (`removeItem` undoes it).
  Never change the profile language in *Settings*. Otherwise take names from the snapshot; model names can be
  translated too, so match definitions, stages and fields by key, bar position and `data-flw-id`.

### Signing in

- Login: placeholders *Enter your username*, *Enter your password*; button **Sign in**. "Invalid user name or
  password." means stop and ask: credentials come from the user, never guess them. Keep the session (Playwright
  `storageState`; the cookie is `flowable-remember-me`).
- No *Enter your username* on the first page means single sign-on: ask the user to sign in in that browser, or
  for a saved session; never type credentials into an identity provider's page. A deep link opened while signed
  out leads to the login page; 2026.1 and newer return to it after sign-in (*2025.2 and older:* open it again).
- **Several users:** one session per account (one `storageState` each; with Playwright MCP, sign out and in).
  Sign out: the user menu at the bottom of the sidebar (`.flw-userProfile_container`) → **Sign out**
  (`.flw-menu-item--logout`), on every version. **Impersonate user** in that menu (where an administrator allows
  it) only when the user tells you to; leave with **Exit impersonation** and name those steps in your report.

### The shell

- **The sidebar:** **Create New**, **Home**, then *APPS* (the project's apps, §9), then *GENERAL* (built-ins:
  **Work** for cases and processes, **Tasks** the inbox, Contacts, Dashboards, Documents, Reports, …). A project
  app's pages appear beneath it once clicked; a creatable one adds *Create new*. Entries are links in menu items:
  `getByRole('link', { name: 'Work', exact: true })`. Scope any `menuitem` lookup: the sidebar has many.
  *2025.1 and older:* one list in the installation's order; the selected app, Work included, shows its filters
  and *Create new* beneath it; with Engage, *Conversations* comes first. *3.14 and older:* no roles (§8).
- **Create New** (**New** on 2025.1 and older) starts something in the app you are in (*Starting a case …*).
- **Global search** (2025.1 and newer; absent when switched off): the top-bar button whose name starts with
  "Search" and ends with the shortcut hint, `getByRole('button', { name: /^Search/ })`. Its shortcut is Meta+K, or
  Ctrl+K when the user agent contains "Win" (on Linux press `Meta+K`, not `ControlOrMeta+K`). It lags (§7).
- **The lists are filtered.** Work list: *For me* (assigned to you), *Created by me*, *Open*, *Completed*,
  *All*. Inbox: *For me*, *Unassigned*, *Open*, *Completed*, *All*. **Show more filters** reveals all but the
  first three (*2025.1 and older:* all are listed). The active filter has no ARIA state: read the URL. A case you
  just started is rarely under *For me*: use *All*, or open it by URL.

### Routes you can open directly

Hash routes under Work's base URL (§0). Going to the hash you are on does nothing; reload for a clean state (§7).

| What | Route |
|---|---|
| Work list | `#/work/<filter>`: `assignee` (For me), `mine` (Created by me), `open`, `completed`, `all` |
| A case or a process | `#/work/all/case/<caseInstanceId>`, `#/work/all/process/<processInstanceId>` |
| A task inside it | `#/work/all/case/<caseInstanceId>/task/<taskId>` (likewise `process`) |
| One of its tabs | append `/tab/<tabId>`: `task` (Open tasks), `workForm`, `chat`, `comments`, `people`, `subItems`, `documents`, `auditTrail`, `history`, and `diagram` where shown |
| Task inbox | `#/tasks/<mine\|unassigned\|open\|completed\|all>/<taskId>` |
| An app page, or an instance opened from it | `#/<app>/<page>`, `#/<app>/<page>/case/<id>` |
| A case's custom view | `#/case-view/case/<caseInstanceId>`, then `/task/<taskId>` or `/nav/<id>` |
| Dashboards (3.15 and newer) | `#/dashboards`, `#/dashboards/<id>` |
| Start a definition (every version, if you may start it) | `#/work?create=true&definitionKey=<key>` |

### Starting a case or process

1. **Open `#/work?create=true&definitionKey=<key>`** (§9 gives the key): the dialog opens on the definition's
   start form, or its *Continue* confirmation. This holds only for a definition your user may start (§9 *May
   start*; `platform-api/work-definitions` lists yours). Otherwise Work ignores the key without a message: it
   shows the chooser, or, if you may start exactly one definition, that one's form. Before you submit, check
   that the dialog is your definition's: the confirmation names it (*Create New <name>?*; *Create <name>?* on
   2025.1 and older), a start form shows the fields §9 lists for it. If it is not, press **Cancel** and report the
   missing permission (§1.7); never pick a same-named entry instead. **Create New** opens this dialog only inside
   the Work app. In *Tasks* it opens *Create task*, a standalone task: close it, never save it. *Contacts*,
   *Dashboards* and *Conversations* open their own dialogs; anywhere else it shows app tiles first (Work's is
   `#flw-createNewDialogItem--work`). *2025.1 and older:* **New** always shows the tiles.
2. **Without a key**, the chooser is a `dialog` with no accessible name (3.15 and newer; *3.14 and older:* no
   dialog role, so scope to `.flw-modal`): find it by its heading (*Create New*; *Create* on 2025.1 and older)
   and *Search* box: `getByRole('dialog').filter({ hasText: 'Create New' })`. Definitions are grouped by app;
   *Search* matches part of the name, any case. An entry's accessible name also holds its description, so click
   its text: `dialog.getByText('<name>', { exact: true })`. Names can repeat, keys cannot. With one startable
   definition the list is skipped. *2025.1:* each group scrolls in a 260 px box.
3. **The start form** opens in a form dialog (§7 *Dialogs*) titled by its dialog title or *Create case or
   process*. Without outcomes press **Submit**; with outcomes (§9 lists them) the one the user wants. **Cancel**
   creates nothing. Without a start form an alert asks "Do you want to continue?": press **Continue**. Work then
   opens the new instance, on its first task when that is yours. Note the instance id from the URL.

### Inside a case or a process

- **Header.** The name is a level-1 heading (`getByRole('heading', { level: 1 })`, 3.15 and newer; on 2026.1 and
  newer also a textbox *Edit title*), with a type badge, the assignee, due date, who started it and when, and
  *Status* (the business status) when set. The business key is not shown (§3). On 2026.1 and newer an
  unassigned case or process may offer **Claim**: it asks *Claim case* / *Claim process*; press **Yes**.
- **Stage bar** — what "stage N" means to a user; §9 numbers it for this project.
  - **What it lists:** every stage *and every milestone* of the case, nested ones included, unless the model
    sets `includeInStageOverview="false"` or an expression false at runtime (later numbers then shift). It is
    drawn only for two or more entries.
  - **Order:** by `displayOrder`, entries without one first. Entries that share a `displayOrder`, or have none,
    go by when they ended: ended ones first, in that order, then the rest, stages in model order before
    milestones. So without `displayOrder` a reached milestone, or a stage that finished while an earlier one was
    skipped, jumps ahead as the case runs. §9 numbers the bar before anything ends and marks such ties with ⚠:
    count in the live bar before acting on "stage N", and confirm the name when unsure.
  - **Status:** *Current* is an active stage (parallel ones can be current together); *Completed* means ended,
    exited or terminated included; the rest is *Not started*. A milestone is never *Current*: it goes straight to
    *Completed*.
  - **Reading it:** `.flw-timeline__step-container--current .flw-timeline__step-label` on every version (steps
    are `.flw-timeline__step-container` with `--done`, `--current` or `--pending`). On 2026.1 and newer it is
    also a list *Progress*, steps named "Step N of M, <stage>, Completed|Current|Not started":
    `getByRole('list', { name: 'Progress' }).getByRole('listitem', { name: /, Current$/ })`. *2025.2 and older:*
    a snapshot shows the labels, not which is current. `stage-overview` (§3) has the same list and is the authority.
- **Tabs** (`role=tab` from 3.15): *Open tasks*, *Work form*, *Conversation*, *Comments*, *People*, *Sub-items*
  (child cases and processes), *Documents*, *Audit trail*, *History* (diagram, start form, completed tasks,
  decisions, audit, and a milestone kept out of the bar).
- **Work form:** the instance's own data. With edit rights its **Save**, unlike a task's, writes the case (or
  process) variables at once, and the engine re-checks every waiting entry criterion: it can start a stage or an
  item. Use it only when the route needs that field or the user asked, and name it in your report.
- **Case view.** A case whose model has a custom view shows a link *Navigate to case view* in its header
  (`getByText('Navigate to case view', { exact: true })`, every version). It opens
  `#/case-view/case/<caseInstanceId>`: a sidebar (or a bar along the top) lists the case's tasks, its case pages
  and links; click an entry, and the URL ends in `/task/<taskId>` or `/nav/<id>`. A *case page* (§9) lives only
  there: a form with only **Save**, which writes the case variables at once, like the *Work form*'s; it is no
  task and never in a task list. Everything else works in the default view `#/work/all/case/<id>`: prefer it.

**Actions** — a case's *other* moves. The toolbar beside the title shows quick actions as buttons named after the
action (`getByRole('button', { name })`, 3.15 and newer); **More Actions** opens the menu (older versions: §8). A
model can restrict an item to one of the two, so look in both.

| Button | What it is, and what pressing it does |
|---|---|
| a manual-activation item | each *enabled* one (§4), named after the plan item or the name the model gives it (§9); when the model lists groups or users, only they get it. Starts the item at once, without confirmation, unless it has a start form, which opens first. A stage that looks stuck often has its next item waiting here: the step agents miss most often |
| a user event listener | each *available* one, named after the listener. Fires at once, without confirmation, unless it has a form. One that jumps the case (close, reject, reopen) is irreversible: ask first (§1.5) |
| the project's own actions (§9) | with a form, a form dialog opens; others run at once or ask "Do you want to execute the action '<name>'?": press **OK** |
| **Terminate** (case), **Cancel** (process) | toolbar only, for the instance's owner and administrators. Irreversible: never without the user's go. If missing, report it |
| reopen a completed or terminated case | only if its model has a reactivation listener (§9): named after it, or "Reactivate <case name>"; it can ask for a form, be limited to groups and expire after a set time. Only when the user asks |

Success shows "Action '<name>' was correctly executed." (`.flw-funkyNoti--success`, every version): assert the
text, or wait for the response ending in `/execute`. A listener's form started from the toolbar opens in a form
dialog; a *user-event-listener button* inside a form (2025.1 and newer), like an action button there, opens the
panel `.flw__modal-panel__dialog` (§7 *Dialogs*), and on 2025.1 its **Submit** enables late. Toolbar actions load
with the page and after a toolbar action: if the case changed elsewhere, reload before concluding one is missing.

### Inside a task

- **The form** is on the **Task** tab (`/tab/taskForm`); the others are Conversation, Comments, People, Subtasks,
  Documents and History. **Save** stores a draft: it writes no variables and completes nothing (§5).
- **Outcome buttons** sit in the header beside the title, or where the form keeps an outcome group *in the
  form*. A form with outcomes has no **Complete**; a form without gets **Complete**; a task without a form has a
  plain **Complete** in its header.
- **Claim.** An unassigned task shows only **Claim**: outcomes hidden, form read-only. It asks "Claim task":
  press **Yes** (some installations claim at once). Outside the candidate groups you see nothing to do. *2025.1
  and older:* the header's **Claim**, **Complete** and **Save** are links without a role while enabled (§8).
- **After Complete or an outcome**, Work may jump to your next task (always on 2025.2 and older, for cases on
  newer versions), and the URL can keep the old task id. With no next task it shows the case or process again (in
  Tasks: "Task completed successfully."); an outcome or the installation can redirect elsewhere. So check the
  task over REST (§3). **Multi-instance tasks** (§9 marks them) create several tasks of the same name; the flow
  goes on only when all are done or the completion condition holds.

## 3. Read state over REST — never write

**Connecting.**
- **Playwright MCP:** `browser_evaluate` with a relative `fetch` (§0) runs in the page, sends the session cookie
  and resolves under Work's base URL; return only the fields you need. To wait, poll inside one call
  (`await new Promise(r => setTimeout(r, 500))` in a loop, at most 10 s). If relative URLs answer 404, copy the
  base from a request the page makes (`browser_network_requests`). A script: `page.request.get(url)` sends the
  browser's cookies. GET needs no CSRF token.
- **Lists are paged:** every `cmmn-api` / `process-api` list answers `{data, total, start, size}` with 10 rows
  unless you pass `size`; plan items and activities come oldest first, so the current ones are cut off. Always
  add `&size=1000` and compare `total`. **After a case ends** the `cmmn-runtime` endpoints answer 404 or nothing;
  `stage-overview`, `platform-api/case-instances/<id>` and the history row keep working.

| Question | `GET` |
|---|---|
| Who am I, in which groups? | `idm-api/current-user` (`id`, `memberGroups`, `language`) |
| The case header: business key, definition version, state, my permissions | `platform-api/case-instances/<id>` (`businessKey`, `caseDefinitionKey`, `caseDefinitionVersion`, `caseDefinitionId`, `state`, `permissions`); a process: `platform-api/process-instances/<id>` (`processDefinitionId`, `processDefinitionVersion`) |
| Which stage is the case in? | `platform-api/case-instances/<id>/stage-overview`: `[{id, name, current, ended, endTime}]` in bar order |
| Every plan item and its state | `cmmn-api/cmmn-runtime/plan-item-instances?caseInstanceId=<id>&includeEnded=true&size=1000` (`name`, `state`, `planItemDefinitionType`, `elementId`, `stageInstanceId`, `completable`, `entryCriterionId`); `&state=enabled` lists the manual items waiting for a click. Some 3.11/3.12 builds ignore `includeEnded` and return only running items |
| Open tasks of a case, without index lag | `cmmn-api/cmmn-runtime/tasks?caseInstanceIdWithChildren=<id>&size=1000`: all of them, other groups' and child cases' and processes' included (tell them apart by `scopeId` / `processInstanceId`). With plain `caseInstanceId` a non-administrator sees only their own. A process: `process-api/runtime/tasks?processInstanceIdWithChildren=<id>&size=1000` |
| A task: done yet? assignee, form key, *my* permissions | `platform-api/tasks/<taskId>`, also for completed tasks (`endTime` set); `permissions` contains `task.complete` / `task.claim` when you may |
| Who may work on a task | `platform-api/tasks/<taskId>/candidates?size=1000` (the candidate *users*). Candidate groups: `cmmn-api/cmmn-runtime/tasks/<taskId>/identitylinks` for a case task, else `process-api/runtime/tasks/<taskId>/identitylinks` (needs the identity-link view permission) |
| The form as deployed, expressions and rules included | `platform-api/tasks/<taskId>/form` (404: no form). Start forms: `platform-api/case-definitions/<definitionId>/start-form`, `platform-api/process-definitions/<definitionId>/start-form` |
| Values the conditions read | `cmmn-api/cmmn-runtime/case-instances/<id>/variables`; a task's scope `platform-api/tasks/<taskId>/variables`; the Work form `platform-api/case-instances/<id>/work-form/variables` |
| The task Work would open next for me | `platform-api/next-involved-task/<cmmn\|bpmn>/<instanceId>` |
| Which actions, manual items and listeners I can click | `action-api/action-repository/scoped-action-definitions/<cmmn\|bpmn\|task>/<id>?supportsChannels=menu&supportsChannels=quick-actions&supportsChannels=quick-menu`. In `data[]`, key `cmmn-manual-activated-planitem` is an enabled manual item, `cmmn-user-event-listener` an available listener; `name` is the button; `formKey` set: a form opens; `confirmationMessage` set: a confirmation. What you may not start is absent. `menu` alone lists only *More Actions* |
| Where a process is | `process-api/runtime/activity-instances?processInstanceId=<id>&finished=false&size=1000`; variables `process-api/runtime/process-instances/<id>/variables` |
| An instance from a business key | `cmmn-api/cmmn-runtime/case-instances?businessKey=<key>` (closed: `cmmn-api/cmmn-history/historic-case-instances?businessKey=<key>`); a process `process-api/runtime/process-instances?businessKey=<key>` (finished: `process-api/history/historic-process-instances?businessKey=<key>`). The `id` is what §2's routes take; confirm with the user when several match |
| Open tasks of group Z | `cmmn-api/cmmn-runtime/tasks?candidateGroup=Z&ignoreAssignee=true&size=1000` and the same on `process-api/runtime/tasks`, merged by `id` (without `ignoreAssignee` claimed tasks drop out). A non-administrator outside Z gets an empty list, which proves nothing |
| What happened in a case that ended | `cmmn-api/cmmn-history/historic-planitem-instances?caseInstanceId=<id>&size=1000`, `…/historic-task-instances?caseInstanceId=<id>&size=1000` (who completed what), `…/historic-variable-instances?caseInstanceId=<id>&size=1000` |
| The model a case runs, to compare with §9 | administrators: `cmmn-api/cmmn-repository/case-definitions/<caseDefinitionId>/resourcedata` (XML; a process: `process-api/repository/process-definitions/<id>/resourcedata`). Others get 403 there; who may start the case can read `…/case-definitions/<caseDefinitionId>/model` (JSON). `case-definitions?key=<key>&latest=true` shows a non-administrator only what they may start, and the latest version need not be the running one |
| Which definitions can be started | `platform-api/work-definitions` |
| Dashboards I can open (3.15 and newer) | `platform-api/dashboards` (`id`, `name`) → `#/dashboards/<id>` |

- **Not in REST:** which half of a sentry is satisfied; a plan item records only the criterion that fired it
  (`entryCriterionId`). The `platform-api/search/…` lists (inbox, work list, global search) lag, being index-backed.
- **Write endpoints are off limits** without an explicit request: task `complete` and `claim`, plan item
  `trigger` / `start` (administrators only), action `execute`, setting variables. They bypass the form.
- **Flowable Inspect** (user menu → *Show Flowable Inspect*, where an administrator enabled it) shows the
  instance's variables, plan items and timers; *Evaluate expression now* checks a condition on the live case
  (only reading expressions, never a method call). Its write actions (*Edit Variable*, delete, *Trigger Timer*,
  *Trigger signal event*, *Migrate*, test runs) bypass the forms: never unless the user asked for exactly that.

## 4. How a case moves (CMMN at runtime)

**Plan item states:** *available* waits for an entry criterion; *enabled* waits for someone to start it (manual
activation, §2 *Actions*); *active* runs (a human task's task exists); *completed*, *terminated*, *failed* ended;
*unavailable* is a listener whose availability condition is false; *wait_repetition* and *async-active* occur too.

**Entry criteria.**
- Without one, an item starts once its parent (the case, or its stage) is active: a task or stage turns *active*,
  a manual item *enabled*, a listener *available*, a milestone is reached at once. Items of a stage that has not
  started do not exist yet; REST lists them once it starts.
- Several entry criteria are alternatives (**or**); inside one, every on-part must have happened (**and**) and
  the condition, if any, must hold. The engine re-checks waiting criteria whenever it touches the case: after any
  plan item changes state, and after variables are set (a task completed, a *Work form* saved, an action run).
- How long a satisfied half lasts depends on the sentry's trigger mode; §9 marks *(onEvent)*, the rest is default.
  - *Default* (Design: "Event deferred"): an on-part that happened is remembered, and so is a condition once it
    held, even if the variable changes back. A false condition is checked again at every evaluation, so a
    variable set later (for example by an outcome) can still start the item.
  - *onEvent* (Design: "On event"): nothing is remembered. The condition counts only if it is already true when
    the on-part happens (a value written by that same completion, such as its outcome variable, counts), and
    several on-parts must happen together. Otherwise this way in stays shut until the on-part happens again;
    setting the variable afterwards does nothing.

**Listeners.** A user event listener is *available*, with its button, from when its stage is active until someone
triggers it; a repeatable one stays available. With an availability condition it is *unavailable*, without a
button, while that is false; the button goes when its stage ends. On-parts like "listener X occurs" are a model's
deliberate jumps (*close without payout*). Other listeners have no button; §9 names what each waits for: a
variable listener fires when a named case variable is set or changed *after* it became available, a signal
listener on a signal thrown elsewhere, a timer when its time comes, a generic event listener on an inbound event
or a call from code. **Milestones** are reached by themselves when their entry criterion holds.

**Exit criteria** end an item or a stage early, with everything still running or waiting inside. To the sentries
waiting on it, a plain exit counts as *X is exited*: a stage waiting for *X completes* does not start. A stage exit
the model marks `complete` or `forceComplete` (§9) counts as *X completes*; `complete` is refused while the stage
still holds open work, and the user's step that triggered it fails.

**When a stage or the case completes.** Never while anything inside is *active* or a *required* item has not
ended, unless the model relaxes this per item (below).
- **With *autoComplete*** (which can be a condition, §9): that is all.
- **Without *autoComplete*:** every *available* or *enabled* item inside also holds it open, so an optional item
  that never starts keeps it open; the stage becomes *completable* and waits. Find that item's button, or ask.
- **Per item, the model can relax this** (§9): `ignore` never holds its stage open; `ignoreIfAvailable` and
  completion-neutral items not while *available*; `ignoreIfAvailableOrEnabled` not while available or enabled;
  `ignoreAfterFirstCompletion` not after its first completion. Required items count too, unless marked `ignore`,
  or `ignoreAfterFirstCompletion` after their first completion.
- **When a stage completes**, whatever still waits inside is dropped: start an optional manual item before its
  stage's required work is done.

**Repetition.** A repeatable item *with* an entry criterion gets a new instance each time that criterion fires
again while the repetition condition holds. One *without* restarts as soon as an instance completes, as long as
the condition holds (always, if it has none), and its stage cannot complete meanwhile unless the model ignores
the item after its first completion. `maxInstanceCount` caps how many run at once; a collection variable starts
one instance per element.

**The stage bar** — what it lists, its order, why "stage N" can move: §2 *Stage bar*.

## 5. Why can't I go on? Forms, outcomes and buttons

**When an outcome or Complete is disabled, check in this order:**

1. **Is the task yours?** Unassigned shows **Claim**, and the form is read-only until you claim it. Without the
   `task.complete` permission (§3) the outcomes never appear.
2. **Is every required field valid?** Outcomes stay disabled until the form is valid on forms saved by Design 3.8
   or later (every form Design exports today); on older or hand-written forms they stay enabled, and pressing one
   shows the errors. Work's own **Complete** always waits. Find the culprit with the table below.
3. **Does the button have its own `enabled` condition?** §9 lists every conditional button, and each form's
   conditional fields (past six, a count and the query that lists them). `{{…}}` is evaluated in the browser
   against the form's values. A component is disabled or hidden only when its expression is exactly `false`; one
   that fails or resolves to nothing counts as `false`; `0` or an empty string does **not**. An outcome's own
   `enabled` expression (or `true`) replaces the form-valid rule, and `ignoreValidation` lifts it. A plain
   `enabled: false` does not: that outcome still waits for a valid form.
4. **Is something still loading?** Service, action, agent, create-instance and listener buttons show a disabled
   "Loading..." (`.flw__loading-button`); an upload keeps **Save** and the outcomes disabled until it ends (§7).
5. **Did a server rule reject it?** With server-side validation on, a submit can fail with HTTP 400 and a message
   like "variable X is required". Read it from the toast or the response.

| Where to look for the invalid field | What it tells you |
|---|---|
| the label's asterisk | marks a required field and is part of its accessible name: `getByRole('textbox', { name: 'Amount*', exact: true })`. 2025.1 and newer, and 3.17.3+, also set `aria-required` |
| `[aria-invalid="true"]` (2026.1 and newer) | every rendered input whose rules fail, touched or not; `aria-describedby` points at its messages: `() => [...document.querySelectorAll('[aria-invalid="true"]')].map(e => ({ field: e.closest('[data-flw-id]')?.getAttribute('data-flw-id'), error: document.getElementById(e.getAttribute('aria-describedby') ?? '')?.textContent }))`. *2025.2, 2025.1 and 3.17.15+:* only text fields carry it; older 3.x has none, so read the error list |
| `.flw__error-list__error-list-item` (every version) | the visible messages ("Field must not be empty", "The typed text is invalid", …), shown once the field was touched or an outcome was pressed; 2025.1 and newer, and 3.17.15+, group them as alerts under *Error messages* |
| form tabs, wizard pages, collapsed panels | their fields are not on the page, and a required one blocks with no error in sight. Open every tab inside the form, step through the wizard (**Next** / **Previous**), expand every panel whose toggle has `aria-expanded="false"`. On 2026.1 and newer a tab with errors is named "Some parts of <tab> are incomplete or invalid": match form tabs with a regex |
| a ⚠ in §9 | a required field that can be hidden still counts, also inside a hidden panel or subform, and no error shows anywhere: make it visible (fill what its condition reads), or fill what it depends on. A disabled or ignored field does not count, nor one inside a disabled or ignored container |
| *rules:* in §9 | a validation pattern matches the whole value; minimum and maximum values and lengths, and date formats, apply too; a custom validation fails when its expression is falsy. Missing in §9: read `validationRegExp` and `extraSettings` in the deployed form (§3) |

**What the components do:**
- **Outcomes** complete the task and store their value in the outcome variable, `form_<formKey>_outcome` unless
  the form names another or switches it off (`doNotCreateOutcomeVariable`); §9 names it per form. A sentry or
  gateway reading it is how an outcome steers the case.
- **Complete** (a form without outcomes) completes the task *without* an outcome: no outcome variable is written.
  Work does the same for an outcome whose value is literally `COMPLETE`.
- **Save** on a task completes nothing, writes no variables and is enabled even on an invalid form. The *Work
  form*'s Save is different (§2).
- **Other buttons do not complete the task.** *Service*, *REST*, *agent* and *script* buttons fetch or compute
  values into the form; *action* buttons run an action; *create-instance* buttons start another case or process;
  *user-event-listener* buttons trigger a listener; a button with *autoExecute* runs when its input changes.
- **Visibility and ids.** `visible` false removes a component from the DOM; `enabled` false sets `disabled`.
  Every component root carries `data-flw-id`: the id §9 gives. Where Work renames it at deployment (the action,
  service and agent button components, uploads and attachments, not a plain button that calls a service), §9
  prints the page id beside it, `id` (on the page `<formKey>_<id>`): use that one. Click the input or button
  inside the root, not the root.

## 6. The loop — and how to bring a case to stage N

1. **Pin down the goal.** Which instance (a business key or a number resolves over §3)? What is done? "Stage 5"
   is the fifth entry of the live stage bar (§2 *Stage bar*). Name it back to the user if anything is ambiguous.
2. **Plan the route backwards from §9.** The target's *starts when* (*reached when* for a milestone) names what
   must happen first: an item completes, a listener is triggered, a condition holds. Find where each lives and what
   it needs in turn: a task and its form's required fields and rules, an outcome that writes the variable a
   condition reads, a manual item to start. Keep going until you reach what is current, noting the groups each
   step needs (§0 step 3). Where the route branches on a business decision (approve or reject, pay or not), ask
   unless the instruction decides it. A user event listener that starts the target directly is a shortcut: offer
   it, never take it silently.
3. **Read the present** in one call (§0 step 4). Items of stages that have not started are absent: expected.
4. **Take the next step**, the earliest unmet requirement on the route that you can act on now:
   - an *active* task → open it (*Open tasks*, or `next-involved-task`), claim it if needed, fill the required
     fields, press the outcome the route needs;
   - an *enabled* item, or an *available* listener on the route → its button (§2 *Actions*);
   - an *available* item waiting for a condition → §9 marks with ← the outcome or field that sets each variable:
     press or fill that. Otherwise search §9 for the variable name, then §10; a *Work form* field can set it too,
     at once (§2). If the criterion is *(onEvent)*, the value must hold *when* its on-part happens, so the writing
     step comes first; if the on-part already happened with the condition false, this way in is shut: look for
     another criterion, or report it.
5. **Verify** over REST that the state moved (the item *completed*, the next stage `current`), then go back to
   step 3. Asynchronous steps take a few seconds: poll, do not sleep.
6. **When nothing moves**, report what you found rather than forcing it:
   - a task *active* in the plan items but not on your screen belongs to a user or group you are not (§1.7);
   - a condition is false (read the variable), or an *(onEvent)* criterion missed its moment (§4);
   - an optional item holds a stage without *autoComplete* open (§4);
   - an automatic step failed (*failed* items, the case's *History*), or a timer has not fired (wait; never
     trigger it); or the deployed model differs from §9 (compare it, §3).
7. **Done** means the target shows *Current* in the bar and `current: true` in `stage-overview`. A milestone is
   never current: it is done when it shows *Completed* and `ended: true`. Report the path (§1.8).

**When the map is silent, explore.** Open every tab and **More Actions**; read the task's form over REST
(`platform-api/tasks/<id>/form`) for what this repository lacks. An icon-only button's name is in the snapshot
(from its title). Note what you learn in your report, so it can go back into the models.

## 7. Playwright: locators, waits and traps

| In a script | With Playwright MCP |
|---|---|
| `getByRole(…, { exact: true })` | the ref from `browser_snapshot`; refs go stale after every action |
| `page.request.get(…)` | `browser_evaluate` with a relative `fetch` (§3) |
| `page.waitForResponse(…)`, `expect(…).toPass()` | a polling `browser_evaluate` (§3), or `browser_wait_for` with `text` / `textGone` |
| `page.waitForTimeout(400)` | `browser_wait_for` with `time: 0.4` |
| a CSS locator (stage bar on 2025.2 and older; 3.14 and older) | read it with `browser_evaluate`, then click the snapshot ref of the element with that text |
| `locator.pressSequentially(…)` | `browser_type` with `slowly: true` |
| `locator.setInputFiles(…)` | click the drop zone, then `browser_file_upload` |
| `page.reload()` | `browser_navigate` to the base URL, then to the route |
| `page.screenshot()` | `browser_take_screenshot` |

| Trap | What to do |
|---|---|
| names match substrings | pass `{ exact: true }` to `getByRole` and `getByLabel`: "Complete" would also hit "Completed tasks". Where a name carries extra text (global search, create-dialog entries), §2 says what to match |
| quiet is not done | after a click, wait for the request it causes (`/complete`, `save-form`, `/execute`, `stage-overview`); `networkidle` misses re-fetches that start late |
| toasts | not `getByRole('alert')`, which matches an empty live region: assert a notification by its text with `.first()`, as some render twice. They vanish in seconds; a response you waited for is firmer proof |
| search-index lag | the inbox, the work list and global search lag behind the database: reload or poll, or read REST (§3). To find something you just created, search a standalone word made only of letters: the index splits "Case123" into two words |
| debounced typing | a text, text-area, number or password field with *Use delay* (`extraSettings.useDelay`, on by default for fields added in Design 3.16 and later) commits about 200 ms after the last keystroke: pause about 400 ms before **Save**, an outcome, **Cancel** or Escape, or the value is lost |
| throttled "touched" | a form records at most one touched field per 300 ms (500 ms on 3.16 and older and on 3.17.0–3.17.5): to see several fields' messages, pause about 350 ms (550 ms there) after leaving each |
| Save redraws the form | Work fetches the task (then the instance) again and redraws the form from the saved values, undoing an edit made meanwhile: wait for the `save-form` response and about a second before you edit or press an outcome, then check your value is still there |
| overlays, onboarding | a key pressed right after an overlay opens can be lost: wait for its content. Close a tutorial bubble or "New here?" prompt, or set `localStorage.setItem('flowable-tutorial-usage', 'true')` before loading the page |
| capitalised text | CSS can change the case of displayed text: `innerText` may differ from what `toHaveText` compares |

- **Dialogs.** No Work dialog has an accessible name, except a confirmation from 2026.1, which is named after its
  title (`getByRole('alertdialog', { name: 'Claim task' })`). *3.14 and older:* the chooser and other modals are
  `.flw-modal` without a dialog role. A form dialog (start, action or listener form) has no dialog role:
  `.flw-formDialog__content` on every version (`[data-testid="modal-content"]` from 2025.2), titled by a level-1
  heading. A confirmation is `role="alertdialog"` from 2026.1, `.flw-alertDialog` before. A button
  inside a form opens the panel `.flw__modal-panel__dialog`, whose body also has the class `flw-alertDialog`:
  scope to the panel and read its title with `getByRole('heading', { level: 2 })`. From 2026.1 everything behind
  a dialog is inert; scope to the dialog anyway. A create form nested in a modal has its own **Save**.
- **Menus.** The sidebar's entries are menu items too: scope *More Actions* to `.flw-actionMenu__menu` (every
  version; from 2026.1 also `#flw-id-menu-container`), a data-table row menu to
  `.flw__data-react-table__cell-menu__items`.
- **Screenshots** at every decision point and on every error; the stage bar before and after reports best.

**Form widgets.**

| Widget | How |
|---|---|
| Select (not a native `<select>`: `browser_select_option` and `browser_fill_form` fail on it) | scroll it into view, wait a moment, click `.flw__select__control`, type part of the option with real key presses (never `fill()`), then click the option with the exact text (`.flw__select__option`; role `option` only from 2026.1). Options load asynchronously. After 2026.1 the menu closes when anything scrolls and on a fill; this order works on every version |
| Date | a `combobox` named after its label from 2026.1 (*2025.2 and older:* a textbox). Type the date in the format the field displays: its own, else the form's default, else the installation's or the user's locale (see a date on the page or `extraSettings.format` in the deployed form, §3; not the placeholder). Then press Tab: Escape closes the calendar but keeps the focus, so the field is not touched. If the value does not stick, click the day |
| Checkbox, switch | click the input itself (the snapshot's checkbox or switch), not the field's root |
| Repeating subform | **Add item** adds an entry, whatever the button shows (2025.1 and newer; *3.x:* the button is named by its text, by default **Add**). **Delete item** removes one, after a confirmation if the form asks for one. With several subforms, scope by `[data-flw-id="<subformId>"]` |
| Upload | the file input is hidden but labelled: `getByLabel('<label>').setInputFiles(path)`; the drop zone says "Click here and paste from Clipboard" from 2025.1 on. While a file uploads, **Save** and the outcomes stay disabled ("Files are being uploaded..."): wait for the file card |
| Rich text | 2026.1 and newer (Lexical): click `[data-lexical-editor="true"]` before typing, as the toolbar's Undo comes first in tab order. *2025.2 and older:* Draft.js |
| Data table | from 2025.1 rows are `tr.flw__data-react-table__row` (a bare `tbody tr` also hits a zero-height spacer row). 3.x has only the legacy table, which 2025.1 can switch back on: `.ReactTable`, rows `.rt-tbody .rt-tr-group` (role `row` on `.rt-tr`), headers `.rt-thead.-header .rt-th`. Match a row by its text: short pages are padded with empty `.-padRow` rows |

## 8. Version notes

One column per Work line; the version note at the top names yours. 3.15 to 3.17 behave like 2025.1 unless a cell
says otherwise. **3.14 and older have almost no ARIA roles in Work's own screens** (they arrived in 3.15.0): use
the classes below for sidebar entries, toolbar actions, the record title, tabs and create-dialog entries. Form
fields, outcomes and the form's own buttons are real inputs and buttons everywhere. Prefer visible text,
`data-flw-id`, the `flw-` classes, and REST for anything that decides a step. These notes come from each line's
Work frontend and end-to-end test helpers, not from driving a running Work: when a locator finds nothing,
fall back to role and visible text, and say in your report which one failed.

| | 2026.1 and newer | 2025.2 | 2025.1 and 3.15–3.17 | 3.14 and older |
|---|---|---|---|---|
| Read the version | `actuator/info` → `flowablePlatform.version`, or `core-api/about-info` | `actuator/info`, or `platform-api/about-info` | `actuator/info`; `platform-api/about-info` from 3.16.3 | `actuator/info` |
| Sidebar | `nav#flw-id-sidebar__sidebar`: *APPS*, then *GENERAL* | same | `nav#flw-id-switcher__sidebar`: one list, filters under the selected app | no roles: `.flw-switcheritem--work:not(.flw-switcheritem--sub)` (likewise `--tasks`) |
| Create entry | **Create New**; what it opens depends on the app you are in | same | **New**, always the app tiles | **New** (`.flw-switcheritem--create`), always the app tiles |
| Create dialog | heading *Create New*, no accessible name | same | heading *Create*, no accessible name | `.flw-modal`, heading *Create*, no dialog role; entries `.flw-createDialogItem`, no role |
| Global search | yes | yes | 2025.1 yes; 3.x no | no |
| List filters | first three, then **Show more filters** | same | all, under the selected app | same |
| Record title | heading, plus textbox *Edit title* | heading | heading (editable in place) | `.flw-header-detail__details-title .flw-editableBlock__content` |
| Claim a case or process | yes | no | no | no |
| Stage bar | list *Progress*, steps "Step N of M, …" | no list role; hidden status text per step | no roles; read `.flw-timeline__step-container--current` | same |
| Tabs | `role=tab` | `role=tab` | `role=tab` | `.flw-tabs__head__item--tabId-<tabId>`, or the `/tab/` route |
| Toolbar actions | `div[role=button].flw-scopedToolbar__item` | same | same | `page.locator('.flw-scopedToolbar__item', { hasText: /^\s*<name>\s*$/ })`, no role; icon-only: name in `title` |
| *More Actions* | button "More Actions" | `.flw-scopedToolbar__item--showMore` (title "Show actions") | same as 2025.2 | same as 2025.2 |
| Action menu entries | `role=menuitem` | `role=menuitem` | `.flw-menu-item[title="<name>"]`, no role | same |
| Confirmation dialogs | `role="alertdialog"` | `.flw-alertDialog` | `.flw-alertDialog` (`[data-cy=bare-alert-dialog]`) | `.flw-alertDialog` |
| Task header buttons (Claim; Complete without a form; Save) | button | button | no role while enabled: `page.locator('a.flw-headerToolbar__btn', { hasText: /^\s*Claim\s*$/ })` | same |
| A form's user-event-listener button | `.flw__modal-panel__dialog` | same | 2025.1 same, wait for **Submit**; 3.x has none | none |
| Assignee link | "Assignee: <name>" | "<name>" | "<name>" | "<name>" |
| After Complete | the next task opens for cases | the next task opens | the next task opens | the next task opens |
| Form errors | group *Error messages* > alerts; `aria-invalid` on every input | group *Error messages* > alerts; `aria-invalid` on text fields | `.flw__error-list__error-list-item`; on 2025.1 and 3.17.15+ also the group and `aria-invalid` on text fields | `.flw__error-list__error-list-item`, no roles |
| `aria-required` on inputs | yes | yes | 2025.1 and 3.17.3+ | no |
| *Use delay* on text fields | per field | per field | per field, from 3.16 | no |
| "Touched" throttle | 300 ms | 300 ms | 2025.1 and 3.17.6+ 300 ms; older 500 ms | 500 ms |
| Subform's add button | **Add item** | same | 2025.1 **Add item**; 3.x named by its text (default **Add**) | named by its text (default **Add**) |
| Form data tables | `tr.flw__data-react-table__row` | same | 2025.1 same (`.ReactTable` if switched back); 3.x `.ReactTable` | `.ReactTable` |
| Select options, date fields | options have a role; date is a combobox | options have no role; date is a textbox | same as 2025.2 | same as 2025.2 |
| Rich text editor | Lexical | Draft.js | Draft.js | Draft.js |
| Test hooks | `data-testid` | `data-testid` | `data-cy` (some `data-testid`) | `data-cy` |
| Modal footer order | Save, Cancel | Save, Cancel | Cancel, Save | Cancel, Save |
| Dashboards | yes | yes | yes | no |
