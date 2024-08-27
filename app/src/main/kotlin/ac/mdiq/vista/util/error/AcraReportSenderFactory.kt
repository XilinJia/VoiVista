package ac.mdiq.vista.util.error

import android.content.Context
import com.google.auto.service.AutoService
import org.acra.ReportField
import org.acra.config.CoreConfiguration
import org.acra.data.CrashReportData
import org.acra.sender.ReportSender
import org.acra.sender.ReportSenderFactory
import ac.mdiq.vista.R
import ac.mdiq.vista.util.error.ErrorUtil.Companion.openActivity
import ac.mdiq.vista.util.UserAction

/*
* Created by Christian Schabesberger on 13.09.16.
*
* Copyright (C) Christian Schabesberger 2015 <chris.schabesberger@mailbox.org>
* Copyright (C) 2024 Xilin Jia <https://github.com/XilinJia>
* AcraReportSenderFactory.kt is part of Vista.
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
/**
 * Used by ACRA in [App].initAcra() as the factory for report senders.
 */
@AutoService(ReportSenderFactory::class)
class AcraReportSenderFactory : ReportSenderFactory {
    override fun create(context: Context, config: CoreConfiguration): ReportSender {
        return AcraReportSender()
    }

    class AcraReportSender : ReportSender {
        override fun send(context: Context, report: CrashReportData) {
            openActivity(context, ErrorInfo(
                arrayOf(report.getString(ReportField.STACK_TRACE)?:""),
                UserAction.UI_ERROR,
                ErrorInfo.SERVICE_NONE,
                "ACRA report",
                R.string.app_ui_crash))
        }
    }
}
