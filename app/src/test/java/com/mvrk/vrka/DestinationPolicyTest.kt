package com.mvrk.vrka

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DestinationPolicyTest {

    @Test
    fun api26To28RequireAValidSafTree() {
        for (api in listOf(26, 27, 28)) {
            assertTrue("API $api", DestinationPolicy.requiresSafDestination(api))
            assertFalse("Blank destination on API $api", DestinationPolicy.acceptsDestination(null, api, false))
            assertFalse(DestinationPolicy.acceptsDestination("", api, false))
            assertTrue(
                "A granted tree is valid on API $api",
                DestinationPolicy.acceptsDestination("content://tree/downloads", api, true),
            )
            assertFalse(
                "A revoked tree must be rejected on API $api",
                DestinationPolicy.acceptsDestination("content://tree/downloads", api, false),
            )
        }
    }

    @Test
    fun api29AndAboveKeepTheDefaultDownloadsDestination() {
        for (api in listOf(29, 30, 33, 34, 36)) {
            assertFalse("API $api", DestinationPolicy.requiresSafDestination(api))
            assertTrue(DestinationPolicy.acceptsDestination(null, api, false))
            assertTrue(DestinationPolicy.acceptsDestination("", api, false))
            assertTrue(DestinationPolicy.acceptsDestination("content://tree/downloads", api, true))
            assertFalse(
                "An explicitly selected but revoked tree is still invalid",
                DestinationPolicy.acceptsDestination("content://tree/downloads", api, false),
            )
        }
    }
}
