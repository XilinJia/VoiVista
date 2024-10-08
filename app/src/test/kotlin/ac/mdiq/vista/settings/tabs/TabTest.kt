package ac.mdiq.vista.settings.tabs

import ac.mdiq.vista.ui.util.Tab
import org.junit.Assert
import org.junit.Test

class TabTest {
    @Test
    fun checkIdDuplication() {
        val usedIds: MutableSet<Int> = HashSet()

        for (type in Tab.Type.entries.toTypedArray()) {
            val added = usedIds.add(type.tabId)
            Assert.assertTrue("Id was already used: " + type.tabId, added)
        }
    }
}
