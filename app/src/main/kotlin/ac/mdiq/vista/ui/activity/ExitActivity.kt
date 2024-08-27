package ac.mdiq.vista.ui.activity

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import ac.mdiq.vista.ui.util.NavigationHelper.restartApp

/*
* Copyright (C) Hans-Christoph Steiner 2016 <hans@eds.org>
* Copyright (C) 2024 Xilin Jia <https://github.com/XilinJia>
* ExitActivity.kt is part of Vista.
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
class ExitActivity : Activity() {
    @SuppressLint("NewApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finishAndRemoveTask()
        restartApp(this)
    }

    companion object {
        @JvmStatic
        fun exitAndRemoveFromRecentApps(activity: Activity) {
            val intent = Intent(activity, ExitActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            activity.startActivity(intent)
        }
    }
}
