package com.flowable.atlas.design

/**
 * How "Pull from Flowable Design" authenticates. Persisted by name in the VCS-shared project settings;
 * the secret itself always lives in the PasswordSafe ([DesignCredentials]).
 */
enum class DesignAuthMode(val label: String) {

    /** HTTP Basic with a Design username/password — the historical, and still default, mode. */
    BASIC("Username / password"),

    /** A Design personal access token, sent as `Authorization: Bearer …`. */
    ACCESS_TOKEN("Access token"),
}
