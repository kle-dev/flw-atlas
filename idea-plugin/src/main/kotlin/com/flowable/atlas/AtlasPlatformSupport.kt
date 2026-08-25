package com.flowable.atlas

import com.intellij.openapi.application.ApplicationInfo

/**
 * Which IntelliJ Platform versions this build was actually *verified* against, and whether the IDE it
 * is currently running in is one of them.
 *
 * `plugin.xml` deliberately declares a wide `until-build`. Atlas is side-loaded: a ZIP attached to a
 * GitHub release, offered through our own `updatePlugins.xml` rather than a Marketplace listing. That
 * channel can only offer an update that already exists, so a tight `until-build` would not produce the
 * JetBrains-intended "update the plugin" prompt — it would simply make Atlas vanish from every
 * colleague's IDE the day they upgrade, until a new release is cut and installed. Staying loadable is
 * the right trade-off for that distribution model.
 *
 * The honest part is here instead: the verified range is stated as a fact where the claim is actually
 * load-bearing — the docs, and every bug report Atlas submits ([AtlasErrorReportSubmitter]) — so "it
 * works" is never confused with "it was tested". The Atlas Hub used to carry the same line in its footer
 * and no longer does: a permanent "verified on 2026.2 — 2026.1 is untested" is our release process on
 * display in a panel people keep open all day, and nothing they can act on.
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
     * The compatibility line for a submitted bug report: normally just what was verified, but on an
     * unverified IDE it says so plainly — which is exactly the context in which a report needs it.
     */
    fun compatibilityNote(): String =
        if (isUnverifiedPlatform) "verified on ${verifiedRange()} — ${branchName(runningBranch)} is untested"
        else "verified on ${verifiedRange()}"
}
