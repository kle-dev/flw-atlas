package com.flowable.atlas.hub

import javax.swing.JList

/** The Hub's two lists size themselves to their content, up to a cap. */
internal object HubLists {
    /**
     * How tall a list in this panel may grow before it scrolls. The Hub lives in a side stripe shared by
     * three sections; past this, one of them owns the panel. The ordinary project has exactly one
     * generated explorer, so the floor is one row, not a box.
     */
    const val MAX_VISIBLE_ROWS = 6

    fun sizeToContent(list: JList<*>, count: Int) {
        list.visibleRowCount = count.coerceIn(1, MAX_VISIBLE_ROWS)
    }
}
