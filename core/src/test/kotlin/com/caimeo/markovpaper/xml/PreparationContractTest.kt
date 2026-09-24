package com.caimeo.markovpaper.xml

import kotlin.test.Test
import kotlin.test.assertFailsWith

class PreparationContractTest {
    @Test
    fun `preparation rejects invalid ordinary rules without creating a runtime`() {
        assertFailsWith<IllegalArgumentException> {
            MarkovXmlCompiler.prepare("""<one values="BW" in="B" out="Q"/>""", 1, 1, 1)
        }
        assertFailsWith<IllegalArgumentException> {
            MarkovXmlCompiler.prepare("""<all values="BW" file="definitely-missing-rule" legend="BW"/>""", 1, 1, 1)
        }
    }
}
