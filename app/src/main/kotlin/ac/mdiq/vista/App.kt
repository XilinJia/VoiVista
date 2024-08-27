package ac.mdiq.vista

import android.app.Application
import android.content.Context
import android.os.StrictMode
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import androidx.preference.PreferenceManager
import com.jakewharton.processphoenix.ProcessPhoenix
import io.reactivex.rxjava3.exceptions.CompositeException
import io.reactivex.rxjava3.exceptions.MissingBackpressureException
import io.reactivex.rxjava3.exceptions.OnErrorNotImplementedException
import io.reactivex.rxjava3.exceptions.UndeliverableException
import io.reactivex.rxjava3.functions.Consumer
import io.reactivex.rxjava3.plugins.RxJavaPlugins
import org.acra.ACRA.init
import org.acra.ACRA.isACRASenderServiceProcess
import org.acra.config.CoreConfigurationBuilder
import ac.mdiq.vista.ui.activity.ReCaptchaActivity
import ac.mdiq.vista.extractor.Vista
import ac.mdiq.vista.extractor.downloader.Downloader
import ac.mdiq.vista.giga.DownloaderImpl
import ac.mdiq.vista.util.hasAssignableCause
import ac.mdiq.vista.settings.VistaSettings.initSettings
import ac.mdiq.vista.ui.activity.MainActivity
import ac.mdiq.vista.util.Localization.getPreferredContentCountry
import ac.mdiq.vista.util.Localization.getPreferredLocalization
import ac.mdiq.vista.util.Localization.initPrettyTime
import ac.mdiq.vista.util.Localization.resolvePrettyTime
import ac.mdiq.vista.util.ServiceHelper.initServices
import ac.mdiq.vista.util.StateSaver
import ac.mdiq.vista.util.image.ImageStrategy.setPreferredImageQuality
import ac.mdiq.vista.util.image.PicassoHelper
import ac.mdiq.vista.util.image.PicassoHelper.setIndicatorsEnabled
import ac.mdiq.vista.util.image.PicassoHelper.terminate
import ac.mdiq.vista.util.image.PreferredImageQuality.Companion.fromPreferenceKey
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketException
import java.util.*

/*
* Copyright (C) Hans-Christoph Steiner 2016 <hans@eds.org>
* Copyright (C) 2024 Xilin Jia <https://github.com/XilinJia>
* App.kt is part of Vista.
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
open class App : Application() {
    protected open val downloader: Downloader
        get() {
            val downloader = DownloaderImpl.init(null)
            setCookiesToDownloader(downloader)
            return downloader
        }

    protected open val isDisposedRxExceptionsReported: Boolean
        get() = false

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        initACRA()
    }

    override fun onCreate() {
        super.onCreate()
        app = this

        if (ProcessPhoenix.isPhoenixProcess(this)) {
            Log.i(TAG, "This is a phoenix process! Aborting initialization of App[onCreate]")
            return
        }

        if (BuildConfig.DEBUG) {
            val builder: StrictMode.VmPolicy.Builder = StrictMode.VmPolicy.Builder()
                .detectLeakedSqlLiteObjects()
                .penaltyLog()
                .penaltyDropBox()
                .detectActivityLeaks()
                .detectLeakedClosableObjects()
                .detectLeakedRegistrationObjects()
            StrictMode.setVmPolicy(builder.build())
        }

        // Initialize settings first because others inits can use its values
        initSettings(this)

        Vista.init(downloader, getPreferredLocalization(this), getPreferredContentCountry(this))
        initPrettyTime(resolvePrettyTime(applicationContext))

        StateSaver.init(this)
        initNotificationChannels()

        initServices(this)

        // Initialize image loader
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        PicassoHelper.init(this)
        setPreferredImageQuality(fromPreferenceKey(this, prefs.getString(getString(R.string.image_quality_key), getString(R.string.image_quality_default))!!))
        setIndicatorsEnabled(MainActivity.DEBUG && prefs.getBoolean(getString(R.string.show_image_indicators_key), false))

        configureRxJavaErrorHandler()
    }

    override fun onTerminate() {
        super.onTerminate()
        terminate()
    }

    protected fun setCookiesToDownloader(downloader: DownloaderImpl?) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        val key = applicationContext.getString(R.string.recaptcha_cookies_key)
        downloader?.setCookie(ReCaptchaActivity.RECAPTCHA_COOKIES_KEY, prefs.getString(key, null)?:"")
        downloader?.updateYoutubeRestrictedModeCookies(applicationContext)
    }

    private fun configureRxJavaErrorHandler() {
        // https://github.com/ReactiveX/RxJava/wiki/What's-different-in-2.0#error-handling
        RxJavaPlugins.setErrorHandler(object : Consumer<Throwable> {
            override fun accept(throwable: Throwable) {
                Log.e(TAG, "RxJavaPlugins.ErrorHandler called with -> : throwable = [${throwable.javaClass.name}]")
                // As UndeliverableException is a wrapper,
                // get the cause of it to get the "real" exception
                val actualThrowable = if (throwable is UndeliverableException) Objects.requireNonNull(throwable.cause) else throwable
                val errors = if (actualThrowable is CompositeException) actualThrowable.exceptions else listOf(actualThrowable)

                for (error in errors) {
                    if (isThrowableIgnored(error)) return
                    if (isThrowableCritical(error)) {
                        reportException(error)
                        return
                    }
                }

                // Out-of-lifecycle exceptions should only be reported if a debug user wishes so,
                // When exception is not reported, log it
                if (isDisposedRxExceptionsReported) {
                    if (actualThrowable != null) reportException(actualThrowable)
                } else Log.e(TAG, "RxJavaPlugin: Undeliverable Exception received: ", actualThrowable)
            }

            private fun isThrowableIgnored(throwable: Throwable): Boolean {
                // Don't crash the application over a simple network problem
                // network api cancellation
                // blocking code disposed
                return throwable
                    .hasAssignableCause(IOException::class.java, SocketException::class.java, InterruptedException::class.java, InterruptedIOException::class.java)
            }

            private fun isThrowableCritical(throwable: Throwable): Boolean {
                // Though these exceptions cannot be ignored
                return throwable
                    .hasAssignableCause(NullPointerException::class.java,
                        IllegalArgumentException::class.java,  // bug in app
                        OnErrorNotImplementedException::class.java,
                        MissingBackpressureException::class.java,
                        IllegalStateException::class.java) // bug in operator
            }

            private fun reportException(throwable: Throwable) {
                // Throw uncaught exception that will trigger the report system
                Thread.currentThread().uncaughtExceptionHandler?.uncaughtException(Thread.currentThread(), throwable)
            }
        })
    }

    /**
     * Called in [.attachBaseContext] after calling the `super` method.
     * Should be overridden if MultiDex is enabled, since it has to be initialized before ACRA.
     */
    protected fun initACRA() {
        if (isACRASenderServiceProcess()) return
        val acraConfig = CoreConfigurationBuilder().withBuildConfigClass(BuildConfig::class.java)
        init(this, acraConfig)
    }

    private fun initNotificationChannels() {
        // Keep the importance below DEFAULT to avoid making noise on every notification update for
        // the main and update channels
        val notificationChannelCompats = listOf(
            NotificationChannelCompat.Builder(getString(R.string.notification_channel_id), NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.notification_channel_name))
                .setDescription(getString(R.string.notification_channel_description))
                .build(),
            NotificationChannelCompat.Builder(getString(R.string.app_update_notification_channel_id), NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.app_update_notification_channel_name))
                .setDescription(getString(R.string.app_update_notification_channel_description))
                .build(),
            NotificationChannelCompat.Builder(getString(R.string.hash_channel_id), NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(getString(R.string.hash_channel_name))
                .setDescription(getString(R.string.hash_channel_description))
                .build(),
            NotificationChannelCompat.Builder(getString(R.string.error_report_channel_id), NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.error_report_channel_name))
                .setDescription(getString(R.string.error_report_channel_description))
                .build(),
            NotificationChannelCompat.Builder(getString(R.string.streams_notification_channel_id), NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(getString(R.string.streams_notification_channel_name))
                .setDescription(getString(R.string.streams_notification_channel_description))
                .build()
        )

        val notificationManager = NotificationManagerCompat.from(this)
        notificationManager.createNotificationChannelsCompat(notificationChannelCompats)
    }


    companion object {
        const val PACKAGE_NAME: String = BuildConfig.APPLICATION_ID
        private val TAG = App::class.java.toString()
        private var app: App? = null

        @JvmStatic
        fun getApp(): App {
            return app!!
        }
    }
}
