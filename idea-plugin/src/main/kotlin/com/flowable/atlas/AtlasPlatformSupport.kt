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
 * and no longer does: a permanent "verified on 2026.2 — 2026.3 is untested" is our release process on
 * display in a panel people keep open all day, and nothing they can act on.
 *
 * ## When bumping to a new platform branch
 * 1. `./gradlew :idea-plugin:verifyPlugin -Patlas.verifyIdes="/Applications/IntelliJ IDEA.app"` against
 *    an install of the new version, and `runIdeLocal` for a JCEF/tool-window smoke test.
 * 2. Raise [VERIFIED_THROUGH_BRANCH] to that branch — and [VERIFIED_SINCE_BRANCH] too when the *floor*
 *    moves with it (SDK + `sinceBuild` in `idea-plugin/build.gradle.kts`), since that drops every older
 *    IDE and the version claims in the READMEs and on the getting-started page have to move as well.
 * 3. Note it in `CHANGELOG.md` (and the `plugin.xml` change-notes).
 */
object AtlasPlatformSupport {

    /**
     * Oldest platform branch `verifyPlugin` actually checks.
     *
     * Since the 2026.2 move this is also the oldest branch the plugin *installs* on — the SDK,
     * `since-build` and the verified floor are all 262, so an older IDE refuses the plugin outright
     * instead of loading something untested. The gap [isUnverifiedPlatform] reports is therefore
     * one-sided now: only an IDE *newer* than the verified range can still be in it.
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
     * The lower bound cannot normally trigger any more (`since-build` is the verified floor, so an older
     * IDE never loads the plugin), and it stays in the condition on purpose: the day the two drift apart
     * again — a floor lowered for one colleague's IDE, a verification list narrowed — the older side is
     * reported instead of silently passing as covered.
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
