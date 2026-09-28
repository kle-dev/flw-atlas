# The ER diagram designer

`<project>.erd.html` is a page for explaining a data model to someone who will never read a changelog:
drag the project's tables onto a canvas, show each with its first columns, find a column in a table of
forty, draw the relations between them with a name and a cardinality, colour them, let Atlas arrange
them, and present the result — or export it for a slide.

It is a page of its own, beside the [explorer](../explorer/), and carries only what it draws: the tables,
their columns and the relations the models state. So it stays a few hundred KB however large the project
is — it opens where a large explorer does not (a Remote Development client refuses files above its size
limit), and it can be sent to someone who only wants the data model.

- **CLI:** `--erd` writes it on its own; `--all` writes `<project>.erd.html` beside the explorer whenever
  the project has a table (see the [CLI reference](../cli/#output-format)). Written beside the explorer,
  its tables link into the explorer's pages.
- **IntelliJ:** tick *ER diagram designer (HTML)* in *Settings → Tools → Flowable Atlas → Generation*
  (see the [plugin reference](../plugin/reference/)). The page opens in an editor tab like the explorer,
  with the same toolbar: *Regenerate* rewrites it, and the banner says when the models are newer than it.

## Which tables

The tables are the ones Atlas already reads, not a second copy of the schema:

- every table a **Liquibase changelog** creates — XML or formatted SQL — as it stands once every change set
  has run (renames, dropped columns and changed types applied, see
  [how changelogs are read](../checks/#how-changelogs-are-read)), with each column's type exactly as the
  changelog writes it (`VARCHAR(255)`, `DECIMAL(19,2)`, `${varchar.type}(255)`) and a key mark on the
  primary key, which a card lists first. When two changelogs define one table, the live one wins over an
  orphaned one, and both over a superseded one (see
  [`changelogIssues`](../checks/#changelogissues-liquibase-authority));
- every table a **database service** names that no changelog creates. Its columns are the service's
  column mappings and its types the service's logical ones, and the list says *service model* beside it.

A table's default business name is the name of the data object that reads it, when exactly one does.

## Building a diagram

- **Add a table** by dragging it from the list onto the canvas, or with its **+**. A table is on a
  diagram once; dragging it again, or clicking it in the list, finds its card. The list's filter matches
  table names, business names and column names.
- **A card** shows the business name over the table name, then the first five columns — the primary key
  first, then the changelog's order — each with its type. **+ N more** unfolds the rest and folds them
  again. The width fits every column, shown or not, so unfolding makes a card longer, never wider.
- **Expand all** and **Collapse all** in the toolbar unfold every card to all its columns, and fold them
  back to their first five. Cards an unfolded one would cover move down to make room; folding moves
  nothing, and *Arrange* closes the gaps. Each is one undo step.
- **Reorder columns** with the grip that appears beside a row, or with ↑ ↓ in the card's panel. The order
  is how you choose which five a folded card shows.
- **The card's panel** — `⋯` on the card, or a double click — sets the business name and the colour
  (eight swatches or any colour), lists every column in order, with a filter for a table of more than
  eight, and links to the changelog, services and data objects behind the table: inside the IDE they open
  the file, in a browser the model's page in the explorer written beside this one.
- **Draw a relation** from the dot on a card's edge to another card (or back to the same one). Its panel
  opens with the name field focused: give it a name, a cardinality — `1:1`, `1:n`, `n:1`, `n:m` — and,
  if you like, the columns it joins; a sentence under the cardinality says what it means (*One Customer
  has many Orders*). Dropping onto a column's row joins that column. The line ends in crow's feet with the
  letters beside them, for whoever has never seen the notation.
- **Arrange** lays every table out by its relations, for when there are too many to sort by hand: each
  relation's *one* side in a column left of its *many* side (a customer, then its orders), the tables in a
  column ordered so as few relation lines cross as possible, each table level with the ones it relates to,
  and the columns far enough apart for the relation names between them. Tables that relate to nothing, and
  separate groups, are packed underneath; drawn relations and the proposals on the canvas both count. The
  cards glide to their places, the view fits them, and one `⌘Z` puts everything back. Arranging an
  arrangement moves nothing. Relation names that would land on top of each other — two lines meeting in one
  gap — are moved apart, on the canvas and in an exported picture alike.
- **Proposals.** When both ends of a relation the models already state are on the canvas — a data object
  field that refers to another data object (its one-to-one or one-to-many becomes the cardinality), or a
  service column relation — it shows as a dashed line: click it to take it, `×` to dismiss it for this
  diagram. One proposal per pair of tables, and none once you have drawn one.

**Present** hides the header, the list and the toolbar, goes full screen where the browser allows it and
fits the diagram to the room; `Esc` comes back.

<figure class="fig">
  <div class="body"><img class="only-light" src="../assets/img/erd-page.png" alt="The ER diagram designer: the project's tables in a list on the left, and on the canvas an Order and a Customer table joined by a relation named placed by, n to 1" width="1400" height="820"><img class="only-dark" src="../assets/img/erd-page-dark.png" alt="The ER diagram designer: the project's tables in a list on the left, and on the canvas an Order and a Customer table joined by a relation named placed by, n to 1" width="1400" height="820"></div>
  <figcaption><b>The demo's own diagram</b>, from the <code>docs/orders.atlas-erd.json</code> it keeps —
  the page opens on it without an import. Business names over table names, the primary key first, the
  relation named, with its cardinality.
  <a href="../demo/erd.html" target="_blank" rel="noopener">Open it ↗</a></figcaption>
</figure>

## Search

The search in the toolbar — `⌘F` / `Ctrl+F`, or `/` — looks through every table of the project and every
one of its columns, on the diagram or not, by name, business name, data object and column type
(`varchar(4000)` finds the long text columns). The results list tables and `table.column` rows: what the
diagram already shows first, then an exact name before one that starts with the query before one that
contains it. `↑` `↓` walk them, `Enter` goes to one — the card centred on the column, the table added
first when the diagram does not have it (the row says *add*) — and `Esc` closes the list, then clears the
search.

While a search is on, the canvas answers it too: the cards with a match are outlined and the rest step
back, the matching columns are marked, and a folded card also shows the columns it found beyond its first
five, where they stand in its order — the one column of forty, without unfolding the rest.

## Keys

`⌘F` / `Ctrl+F` or `/` searches, `Delete` removes the selected card or relation, `⌘Z` / `Ctrl+Z` undoes
and `⇧⌘Z` / `Ctrl+Y` redoes (a name typed in one go is one step), `+` `−` `0` zoom and fit, `Esc` closes
a panel or the search list. Drag the empty canvas to pan, scroll to move, `⌘`/`Ctrl`+scroll or pinch to
zoom.

## Keeping and sharing a diagram

What you draw is kept in the browser, per project: every change is saved as you make it, and a project can
have several diagrams — *Diagram → New, Rename, Duplicate, Delete*. A browser keeps it for one person on
one machine, though, so two more ways out:

- **The diagram file.** *Export → Diagram file* downloads `<name>.atlas-erd.json`; *Diagram → Import*,
  dropping the file onto the canvas or (in the IDE) pasting it opens it again, on any project's ER page.
- **Diagrams in the project.** Commit that file anywhere in the project (outside `build/`, `target/`,
  `node_modules/` and the like) and every ER page generated for the project carries it: the page opens on
  it, listed with *— project* after its name, without an import. Changes you make stay in your browser
  (*changed here*) until you export the file again and commit it; *Revert* drops them.

A diagram refers to tables by name and keeps a snapshot of their columns only as a fallback, so it follows
the schema: a column the changelogs dropped disappears from its card, a new one joins at the end, and a
table this project does not have is drawn from the snapshot, dashed, marked *not in this project's schema*.

The file is plain JSON:

```json
{
  "format": "atlas-erd",
  "version": 1,
  "name": "Orders and customers",
  "project": "flowable-demo",
  "tables": [
    {"table": "ord_order", "alias": "Order", "x": 40, "y": 40, "color": "#e8590c", "expanded": false,
     "order": ["id_", "order_no_", "customer_id_"],
     "columns": [{"name": "id_", "type": "VARCHAR(64)", "pk": true}, {"name": "order_no_", "type": "VARCHAR(64)"}]}
  ],
  "relations": [
    {"id": "r1", "from": "ord_order", "to": "cust_customer", "fromColumn": "customer_id_", "toColumn": "id_",
     "cardinality": "n:1", "label": "placed by"}
  ],
  "dismissed": []
}
```

An import is forgiving — a table without a name, a relation to a table the diagram does not hold or an
unknown cardinality is dropped or defaulted rather than failing the file — and strict only about `format`
and `version`: a file from a newer Atlas is refused rather than half-read.

**Pictures.** *Export → SVG image* and *PNG image* write the diagram as it is drawn, in light colours
whatever the page's theme, without handles, proposals or search marks, sized to its content; the SVG
carries its font, so it looks the same wherever it is opened. Inside IntelliJ the embedded browser cannot
download: *Export* copies the diagram file or the SVG to the clipboard instead, and *Open in Browser* has
the rest.

The page follows the explorer's theme preference (the header's button switches *auto*, *light* and
*dark*, and the explorer then agrees), and in the IDE the IDE's theme and colours.
