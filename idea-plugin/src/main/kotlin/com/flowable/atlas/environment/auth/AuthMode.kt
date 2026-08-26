package com.flowable.atlas.environment.auth

/**
 * How Atlas authenticates to a Flowable server — **the same two modes for Design and for Work**.
 *
 * This was `DesignAuthMode`, and Work had no equivalent: it did basic auth, and a bearer token only
 * reached it by accident, if a pasted cURL happened to carry one. Two servers of the same product,
 * configured by two different vocabularies, is how "why is this page nothing like the other one?"
 * happens. There is one question here now — *which credential* — answered the same way whichever
 * server is being configured.
 *
 * Orthogonal to this, and deliberately **not** a third constant: a captured browser session
 * ([BrowserSessions]). A server behind an identity provider needs the cookie the browser already
 * holds, and it may *also* want basic auth behind that layer — Flowable's security chain uses
 * whichever it honours. Listing "browser" alongside the other two would make them mutually exclusive,
 * and that combination is precisely the one that could then not be configured.
 *
 * Persisted by name, in the IDE-wide catalog and in the project's shared file. `BASIC` is a stable
 * default, which is what makes the name safe to store.
 */
enum class AuthMode(val label: String) {

    /** HTTP Basic with a username and password — the ordinary case, and the default. */
    BASIC("Username / password"),

    /**
     * A personal access token, sent as `Authorization: Bearer …`.
     *
     * The scheme the official Flowable CLI uses, and the one that still works when a server switches
     * Basic off behind an identity provider (`security.type=oauth2`).
     */
    ACCESS_TOKEN("Access token"),
}
