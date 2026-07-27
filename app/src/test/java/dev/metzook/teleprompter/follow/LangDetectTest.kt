package dev.metzook.teleprompter.follow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LangDetectTest {

    @Test
    fun `english prose detects as english`() {
        assertEquals(
            MatchLang.EN,
            LangDetect.detect(
                "Welcome to the channel. Today we are going to talk about the ancient city of Rome.",
            ),
        )
    }

    @Test
    fun `french prose detects as french`() {
        assertEquals(
            MatchLang.FR,
            LangDetect.detect(
                "Nous avons parlé de littérature française pendant que les enfants étaient à l'école.",
            ),
        )
    }

    @Test
    fun `elision-heavy french without diacritics still detects`() {
        assertEquals(
            MatchLang.FR,
            LangDetect.detect("j'ai vu l'ecole et j'aime bien ce quartier avec ses arbres"),
        )
    }

    @Test
    fun `empty or ambiguous text returns null`() {
        assertNull(LangDetect.detect(""))
        assertNull(LangDetect.detect("Colosseum gladiators arena"))
    }
}
