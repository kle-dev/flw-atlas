package com.flowable.atlas.project

import org.junit.Assert.assertEquals
import org.junit.Test

class AtlasScopedKeysTest {

    @Test
    fun `the whole project and no connection is the bare base key`() {
        assertEquals("base", AtlasScopedKeys.scoped("base", ""))
    }

    @Test
    fun `a sub-project and a connection each add their own suffix`() {
        assertEquals("base.apps/orders", AtlasScopedKeys.scoped("base", "apps/orders"))
        assertEquals("base@dev1-design", AtlasScopedKeys.scoped("base", "", "dev1-design"))
        assertEquals("base.apps/orders@dev1-design", AtlasScopedKeys.scoped("base", "apps/orders", "dev1-design"))
    }

    @Test
    fun `a dotted sub-project path cannot be confused with a connection suffix`() {
        // This is why the connection separator is '@' and why slugs can never contain it: with a dot
        // for both, "base.a.b" would be ambiguous between one path and a path plus a connection.
        assertEquals("base.a.b@qa-work", AtlasScopedKeys.scoped("base", "a.b", "qa-work"))
    }

    @Test
    fun `surrounding slashes on the sub-project path are normalised away`() {
        assertEquals("base.apps/orders", AtlasScopedKeys.scoped("base", "/apps/orders/"))
    }
}
