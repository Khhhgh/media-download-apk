package com.example

import com.example.service.UpdateSourceResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateSourceResolverTest {

    @Test
    fun githubRepoUrlBecomesCdnBase() {
        assertEquals(
            "https://cdn.jsdelivr.net/gh/Owner/repo@main/downapp",
            UpdateSourceResolver.normalize("https://github.com/Owner/repo\n")
        )
        assertEquals(
            "https://cdn.jsdelivr.net/gh/Owner/repo@dev/downapp",
            UpdateSourceResolver.normalize("\uFEFF# note\nhttps://github.com/Owner/repo/tree/dev")
        )
    }

    @Test
    fun otherHttpsUrlIsUsedAsIs() {
        assertEquals(
            "https://example.org/x/downapp",
            UpdateSourceResolver.normalize("https://example.org/x/downapp/")
        )
    }

    @Test
    fun invalidPointerContentIsRejected() {
        assertNull(UpdateSourceResolver.normalize(""))
        assertNull(UpdateSourceResolver.normalize("not a url"))
        assertNull(UpdateSourceResolver.normalize("http://github.com/Owner/repo"))
        assertNull(UpdateSourceResolver.normalize("https://github.com/OnlyOwner"))
    }
}
