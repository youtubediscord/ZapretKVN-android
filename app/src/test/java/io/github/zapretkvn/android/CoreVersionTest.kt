package io.github.zapretkvn.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreVersionTest {
    @Test
    fun pinnedCoreIdentityIsComplete() {
        assertEquals("v1.13.14-extended-2.5.2", BuildConfig.CORE_TAG)
        assertEquals(40, BuildConfig.CORE_COMMIT.length)
        assertTrue(BuildConfig.CORE_COMMIT.matches(Regex("[0-9a-f]{40}")))
    }
}
