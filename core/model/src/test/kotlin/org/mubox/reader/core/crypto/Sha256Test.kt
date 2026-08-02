package org.mubox.reader.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Test

class Sha256Test {
    @Test
    fun hashesUtf8TextAsLowercaseHex() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            "abc".sha256Hex(),
        )
    }
}
