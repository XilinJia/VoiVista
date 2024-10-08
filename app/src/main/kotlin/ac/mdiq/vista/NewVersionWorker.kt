package ac.mdiq.vista

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.preference.PreferenceManager
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonParserException
import ac.mdiq.vista.extractor.downloader.Response
import ac.mdiq.vista.extractor.exceptions.ReCaptchaException
import ac.mdiq.vista.giga.DownloaderImpl
import ac.mdiq.vista.ui.activity.MainActivity
import ac.mdiq.vista.util.ReleaseVersionUtil.coerceUpdateCheckExpiry
import ac.mdiq.vista.util.ReleaseVersionUtil.isLastUpdateCheckExpired
import ac.mdiq.vista.util.ReleaseVersionUtil.isReleaseApk
import java.io.IOException

class NewVersionWorker(context: Context, workerParams: WorkerParameters) : Worker(context, workerParams) {
    /**
     * Method to compare the current and latest available app version.
     * If a newer version is available, we show the update notification.
     *
     * @param versionName    Name of new version
     * @param apkLocationUrl Url with the new apk
     * @param versionCode    Code of new version
     */
    private fun compareAppVersionAndShowNotification(versionName: String, apkLocationUrl: String?, versionCode: Int) {
        if (BuildConfig.VERSION_CODE >= versionCode) {
            if (inputData.getBoolean(IS_MANUAL, false)) {
                // Show toast stating that the app is up-to-date if the update check was manual.
                ContextCompat.getMainExecutor(applicationContext).execute {
                    Toast.makeText(applicationContext, R.string.app_update_unavailable_toast, Toast.LENGTH_SHORT).show()
                }
            }
            return
        }

        // A pending intent to open the apk location url in the browser.
        val intent = Intent(Intent.ACTION_VIEW, apkLocationUrl?.toUri())
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pendingIntent = PendingIntentCompat.getActivity(applicationContext, 0, intent, 0, false)
        val channelId = applicationContext.getString(R.string.app_update_notification_channel_id)
        val notificationBuilder = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(R.drawable.ic_newpipe_update)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setContentTitle(applicationContext.getString(R.string.app_update_available_notification_title))
            .setContentText(applicationContext.getString(R.string.app_update_available_notification_text, versionName))

        val notificationManager = NotificationManagerCompat.from(applicationContext)
        if (ActivityCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return
        }
        notificationManager.notify(2000, notificationBuilder.build())
    }

    @Throws(IOException::class, ReCaptchaException::class)
    private fun checkNewVersion() {
        // Check if the current apk is a github one or not.
        if (!isReleaseApk()) return

        if (!inputData.getBoolean(IS_MANUAL, false)) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
            // Check if the last request has happened a certain time ago
            // to reduce the number of API requests.
            val expiry = prefs.getLong(applicationContext.getString(R.string.update_expiry_key), 0)
            if (!isLastUpdateCheckExpired(expiry)) return
        }

        // Make a network request to get latest Vista data.
        if (DownloaderImpl.instance != null) {
            val response = DownloaderImpl.instance!!.get(VOIVISTA_API_URL)
            handleResponse(response)
        }
    }

    private fun handleResponse(response: Response) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        try {
            // Store a timestamp which needs to be exceeded,
            // before a new request to the API is made.
            val newExpiry = coerceUpdateCheckExpiry(response.getHeader("expires"))
            prefs.edit { putLong(applicationContext.getString(R.string.update_expiry_key), newExpiry) }
        } catch (e: Exception) {
            Log.w(TAG, "Could not extract and save new expiry date", e)
        }

        // Parse the json from the response.
        try {
            val voivistaVersionInfo = JsonParser.`object`().from(response.responseBody()).getObject("flavors").getObject("voivista")

            val versionName = voivistaVersionInfo.getString("version")
            val versionCode = voivistaVersionInfo.getInt("version_code")
            val apkLocationUrl = voivistaVersionInfo.getString("apk")
            compareAppVersionAndShowNotification(versionName, apkLocationUrl, versionCode)
        } catch (e: JsonParserException) {
            // Most likely something is wrong in data received from VOIVISTA_API_URL.
            // Do not alarm user and fail silently.
            Log.w(TAG, "Could not get VoiVista API: invalid json", e)
        }
    }

    override fun doWork(): Result {
        return try {
            checkNewVersion()
            Result.success()
        } catch (e: IOException) {
            Log.w(TAG, "Could not fetch VoiVista API: probably network problem", e)
            Result.failure()
        } catch (e: ReCaptchaException) {
            Log.e(TAG, "ReCaptchaException should never happen here.", e)
            Result.failure()
        }
    }

    companion object {
        private val DEBUG = MainActivity.DEBUG
        private val TAG = NewVersionWorker::class.java.simpleName
        private const val VOIVISTA_API_URL = "https://github.com/XilinJia/VoiVista/api/data.json"
        private const val IS_MANUAL = "isManual"

        /**
         * Start a new worker which checks if all conditions for performing a version check are met,
         * fetches the API endpoint [.VOIVISTA_API_URL] containing info about the latest Vista
         * version and displays a notification about an available update if one is available.
         * <br></br>
         * Following conditions need to be met, before data is requested from the server:
         *
         *  *  The app is signed with the correct signing key (by XilinJia / schabi).
         * If the signing key differs from the one used upstream, the update cannot be installed.
         *  * The user enabled searching for and notifying about updates in the settings.
         *  * The app did not recently check for updates.
         * We do not want to make unnecessary connections and DOS our servers.
         */
        @JvmStatic
        fun enqueueNewVersionCheckingWork(context: Context, isManual: Boolean) {
            val workRequest = OneTimeWorkRequestBuilder<NewVersionWorker>().setInputData(workDataOf(IS_MANUAL to isManual)).build()
            WorkManager.getInstance(context).enqueue(workRequest)
        }
    }
}
