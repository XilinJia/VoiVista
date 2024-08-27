package ac.mdiq.vista.ui.fragments

import ac.mdiq.vista.R
import ac.mdiq.vista.util.error.ErrorInfo
import ac.mdiq.vista.util.error.ErrorUtil.Companion.showSnackbar
import ac.mdiq.vista.util.UserAction
import ac.mdiq.vista.extractor.Vista
import ac.mdiq.vista.extractor.exceptions.ExtractionException
import ac.mdiq.vista.extractor.subscription.SubscriptionExtractor.ContentSource
import ac.mdiq.vista.giga.io.NoFileManagerSafeGuard.launchSafe
import ac.mdiq.vista.giga.io.StoredFileHelper.Companion.getPicker
import ac.mdiq.vista.local.subscription.services.SubscriptionsImportService
import ac.mdiq.vista.ui.dialog.ImportConfirmationDialog.Companion.show
import ac.mdiq.vista.util.KEY_SERVICE_ID
import ac.mdiq.vista.util.Logd
import ac.mdiq.vista.util.NO_SERVICE_ID
import ac.mdiq.vista.util.ServiceHelper.getImportInstructions
import ac.mdiq.vista.util.ServiceHelper.getImportInstructionsHint
import ac.mdiq.vista.util.ServiceHelper.getNameOfServiceById
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.util.Linkify
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.core.text.util.LinkifyCompat
import icepick.State

class SubscriptionsImportFragment : BaseFragment() {
    @JvmField
    @State
    var currentServiceId: Int = NO_SERVICE_ID

    private var supportedSources: List<ContentSource>? = null
    private var relatedUrl: String? = null

    @StringRes
    private var instructionsString = 0

    private var infoTextView: TextView? = null
    private var inputText: EditText? = null
    private var inputButton: Button? = null

    private val requestImportFileLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
            this.requestImportFileResult(result)
        }

    private fun setInitialData(serviceId: Int) {
        this.currentServiceId = serviceId
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setupServiceVariables()
        if (supportedSources!!.isEmpty() && currentServiceId != NO_SERVICE_ID) {
            showSnackbar(requireActivity(), ErrorInfo(arrayOf(), UserAction.SUBSCRIPTION_IMPORT_EXPORT, getNameOfServiceById(currentServiceId),
                    "Service does not support importing subscriptions", R.string.general_error))
            requireActivity().finish()
        }
    }

    override fun onResume() {
        super.onResume()
        setTitle(getString(R.string.import_title))
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        Logd(TAG, "onCreateView")
        return inflater.inflate(R.layout.fragment_import, container, false)
    }

    override fun initViews(rootView: View, savedInstanceState: Bundle?) {
        super.initViews(rootView, savedInstanceState)

        inputButton = rootView.findViewById(R.id.input_button)
        inputText = rootView.findViewById(R.id.input_text)
        infoTextView = rootView.findViewById(R.id.info_text_view)

        // TODO: Support services that can import from more than one source
        //  (show the option to the user)
        if (supportedSources!!.contains(ContentSource.CHANNEL_URL)) {
            inputButton?.setText(R.string.import_title)
            inputText?.visibility = View.VISIBLE
            inputText?.setHint(getImportInstructionsHint(currentServiceId))
        } else inputButton?.setText(R.string.import_file_title)

        if (instructionsString != 0) {
            if (relatedUrl.isNullOrEmpty()) setInfoText(getString(instructionsString))
            else setInfoText(getString(instructionsString, relatedUrl))
        } else setInfoText("")

        val supportActionBar = activity?.supportActionBar
        if (supportActionBar != null) {
            supportActionBar.setDisplayShowTitleEnabled(true)
            setTitle(getString(R.string.import_title))
        }
    }

    override fun initListeners() {
        super.initListeners()
        inputButton!!.setOnClickListener { onImportClicked() }
    }

    private fun onImportClicked() {
        if (inputText!!.visibility == View.VISIBLE) {
            val value = inputText!!.text.toString()
            if (value.isNotEmpty()) onImportUrl(value)
        } else onImportFile()
    }

    private fun onImportUrl(value: String?) {
        show(this, Intent(activity, SubscriptionsImportService::class.java)
            .putExtra(SubscriptionsImportService.KEY_MODE, SubscriptionsImportService.CHANNEL_URL_MODE)
            .putExtra(SubscriptionsImportService.KEY_VALUE, value)
            .putExtra(KEY_SERVICE_ID, currentServiceId))
    }

    private fun onImportFile() {
        // leave */* mime type to support all services
        // with different mime types and file extensions
        launchSafe(requestImportFileLauncher, getPicker(requireActivity(), "*/*"), TAG, context)
    }

    private fun requestImportFileResult(result: ActivityResult) {
        if (result.data == null) return

        if (result.resultCode == Activity.RESULT_OK && result.data!!.data != null) {
            show(this, Intent(activity, SubscriptionsImportService::class.java)
                .putExtra(SubscriptionsImportService.KEY_MODE, SubscriptionsImportService.INPUT_STREAM_MODE)
                .putExtra(SubscriptionsImportService.KEY_VALUE, result.data!!.data)
                .putExtra(KEY_SERVICE_ID, currentServiceId))
        }
    }

    private fun setupServiceVariables() {
        if (currentServiceId != NO_SERVICE_ID) {
            try {
                val extractor = Vista.getService(currentServiceId).getSubscriptionExtractor()
                supportedSources = extractor?.supportedSources
                relatedUrl = extractor?.relatedUrl
                instructionsString = getImportInstructions(currentServiceId)
                return
            } catch (_: ExtractionException) { }
        }

        supportedSources = emptyList()
        relatedUrl = null
        instructionsString = 0
    }

    private fun setInfoText(infoString: String) {
        infoTextView!!.text = infoString
        LinkifyCompat.addLinks(infoTextView!!, Linkify.WEB_URLS)
    }

    companion object {
        fun getInstance(serviceId: Int): SubscriptionsImportFragment {
            val instance = SubscriptionsImportFragment()
            instance.setInitialData(serviceId)
            return instance
        }
    }
}
