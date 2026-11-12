package com.flowable.atlas.action

import com.flowable.atlas.findings.AtlasFindingsToolWindowFactory

/** Tools → Flowable Atlas → *Open Atlas Findings*: every defect and advice of the project. */
class OpenAtlasFindingsAction : OpenAtlasToolWindowAction(AtlasFindingsToolWindowFactory.ID)
