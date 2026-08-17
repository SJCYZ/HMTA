package com.sjcyz.hmta.nfc.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class ArchiveEntryNameTest {
    @Test
    fun stripsDirectoriesAndUnsafeCharacters() {
        assertEquals("photo.jpg", ArchiveEntryName.sanitize("../../DCIM/photo.jpg"))
        assertEquals("photo_.jpg", ArchiveEntryName.sanitize("folder/photo?.jpg"))
        assertEquals("received.bin", ArchiveEntryName.sanitize("../"))
        assertEquals("name.txt", ArchiveEntryName.sanitize("folder\\name.txt"))
    }
}
