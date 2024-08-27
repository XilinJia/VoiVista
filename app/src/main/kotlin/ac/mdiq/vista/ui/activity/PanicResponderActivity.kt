package ac.mdiq.vista.ui.activity

import android.app.Activity
import android.os.Bundle
import ac.mdiq.vista.ui.activity.ExitActivity.Companion.exitAndRemoveFromRecentApps

/*
* Copyright (C) Hans-Christoph Steiner 2016 <hans@eds.org>
* Copyright (C) 2024 Xilin Jia <https://github.com/XilinJia>
* PanicResponderActivity.kt is part of Vista.
*
* Vista is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* Vista is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with Vista.  If not, see <http://www.gnu.org/licenses/>.
*/
class PanicResponderActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = intent
        if (intent != null && PANIC_TRIGGER_ACTION == intent.action) {
            // TODO: Explicitly clear the search results
            //  once they are restored when the app restarts
            //  or if the app reloads the current video after being killed,
            //  that should be cleared also
            exitAndRemoveFromRecentApps(this)
        }

        finishAndRemoveTask()
    }

    companion object {
        const val PANIC_TRIGGER_ACTION: String = "info.guardianproject.panic.action.TRIGGER"
    }
}
