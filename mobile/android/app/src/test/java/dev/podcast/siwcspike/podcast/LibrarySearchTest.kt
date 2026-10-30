package dev.podcast.siwcspike.podcast

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class LibrarySearchTest {
    @Test fun normalizesRussianCaseAndYo() {
        assertEquals("елка замыкания", LibrarySearch.normalize("Ёлка ЗАМЫКАНИЯ"))
    }
    @Test fun treatsWildcardsAndBackslashLiterally() {
        assertEquals("%100\\%\\_\\\\%", LibrarySearch.pattern(" 100%_\\ "))
    }
    @Test fun doesNotDependOnDeviceLocale() {
        val old = Locale.getDefault()
        try { Locale.setDefault(Locale.forLanguageTag("tr"))
            assertEquals("interview", LibrarySearch.normalize("INTERVIEW"))
        } finally { Locale.setDefault(old) }
    }
}
