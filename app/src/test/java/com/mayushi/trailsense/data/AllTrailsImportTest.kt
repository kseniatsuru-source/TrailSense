package com.mayushi.trailsense.data

import org.junit.Assert.*
import org.junit.Test

class AllTrailsImportTest {
    @Test fun `canonicalizes supported shared url and removes tracking query`() {
        val r = requireNotNull(AllTrailsImport.parseSharedText("Посмотри https://alltrails.com/trail/canada/bc/grouse-grind?u=i&utm_source=x"))
        assertEquals("https://www.alltrails.com/trail/canada/bc/grouse-grind", r.canonicalUrl)
        assertEquals("Grouse Grind", r.suggestedTitle)
    }
    @Test fun `rejects lookalike host`() {
        assertNull(AllTrailsImport.parseSharedText("https://alltrails.com.evil.example/trail/a"))
    }
}
