package com.flowable.atlas

import com.intellij.openapi.application.ApplicationInfo

/**
 * Which IntelliJ Platform versions this build was actually *verified* against, and whether the IDE it
 * is currently running in is one of them.
 *
 * `plugin.xml` deliberately declares a wide `until-build`. Atlas ships as a ZIP committed to the repo
 * (`idea-plugin/dist/`) with no Marketplace update channel, so a tight `until-build` would not produce
 * the JetBrains-intended "update the plugin" prompt — it would simply make Atlas vanish from every
 * colleague's IDE the day they upgrade, with no way to get it back until a new ZIP is built and pulled.
 * Staying loadable is the right trade-off for that distribution model.
 *
 * The honest part is here instead: the verified range is stated as a fact, and the Atlas Hub shows when
 * the running IDE is outside it, so "it works" is never confused with "it was tested".
 *
 * ## When bumping to a new platform branch
 * 1. `./gradlew :idea-plugin:verifyPlugin -Patlas.verifyIdes="/Applications/IntelliJ IDEA.app"` against
 *    an install of the new version, and `runIdeLocal` for a JCEF/tool-window smoke test.
 * 2. Raise [VERIFIED_THROUGH_BRANCH] to that branch.
 * 3. Note it in `CHANGELOG.md` (and the `plugin.xml` change-notes).
 */
object AtlasPlatformSupport {

    /**
     * Oldest platform branch `verifyPlugin` actually checks.
     *
     * NOT the same as the oldest branch the plugin *installs* on: `since-build` is 261 and the SDK is
     * still 2026.1, so Atlas remains loadable on 2026.1 — it is simply no longer verified there. The
     * supported range is therefore wider than the verified range, which is exactly the gap
     * [isUnverifiedPlatform] exists to make visible rather than paper over.
     */
    const val VERIFIED_SINCE_BRANCH = 262

    /**
     * Newest platform branch this build was run and verified on. Bump only together with an actual
     * `verifyPlugin` run — the value is a claim shown to users, not a guess.
     */
    const val VERIFIED_THROUGH_BRANCH = 262

    /** The running IDE's platform branch (261 for 2026.1, 262 for 2026.2, …). */
    val runningBranch: Int get() = ApplicationInfo.getInstance().build.baselineVersion

    /**
     * True when the running IDE lies outside the verified range — in **either** direction.
     *
     * Older counts too, now that verification starts above `since-build`: a 2026.1 install is a perfectly
     * ordinary thing to have and is no more tested than a future 2026.3 would be. Only flagging newer
     * builds would have quietly presented 2026.1 as covered.
     */
    val isUnverifiedPlatform: Boolean
        get() = runningBranch < VERIFIED_SINCE_BRANCH || runningBranch > VERIFIED_THROUGH_BRANCH

    /** `261` → `"2026.1"`. The platform branch numbering has encoded year/release since 2020. */
    fun branchName(branch: Int): String = "${2000 + branch / 10}.${branch % 10}"

    /** `"2026.1–2026.2"`, or a single version when the range collapsed. */
    fun verifiedRange(): String =
        if (VERIFIED_SINCE_BRANCH == VERIFIED_THROUGH_BRANCH) branchName(VERIFIED_SINCE_BRANCH)
        else "${branchName(VERIFIED_SINCE_BRANCH)}–${branchName(VERIFIED_THROUGH_BRANCH)}"

    /**
     * The compatibility line for the Atlas Hub footer: normally just what was verified, but on a newer
     * IDE it says so plainly, because that is the case where a bug report is worth filing.
     */
    fun compatibilityNote(): String =
        if (isUnverifiedPlatform) "verified on ${verifiedRange()} — ${branchName(runningBranch)} is untested"
        else "verified on ${verifiedRange()}"
}
