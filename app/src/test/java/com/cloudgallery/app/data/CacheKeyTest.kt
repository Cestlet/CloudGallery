package com.cloudgallery.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CacheKeyTest {
    @Test fun cacheKeysRemainCompatibleWithPersistedSha256Names() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", digest(""))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", digest("abc"))
    }
}
