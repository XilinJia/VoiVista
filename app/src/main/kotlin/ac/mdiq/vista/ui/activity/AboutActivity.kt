package ac.mdiq.vista.ui.activity

import android.content.Context
import android.os.Bundle
import android.os.Parcelable
import android.util.Base64
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayoutMediator
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import kotlinx.parcelize.Parcelize
import ac.mdiq.vista.BuildConfig
import ac.mdiq.vista.R
import ac.mdiq.vista.databinding.ActivityAboutBinding
import ac.mdiq.vista.databinding.FragmentAboutBinding
import ac.mdiq.vista.databinding.FragmentLicensesBinding
import ac.mdiq.vista.databinding.ItemSoftwareComponentBinding
import ac.mdiq.vista.ui.util.ktx.parcelableArrayList
import ac.mdiq.vista.util.Localization
import ac.mdiq.vista.ui.util.ThemeHelper
import ac.mdiq.vista.ui.util.ShareUtils
import java.io.IOException
import java.io.Serializable

class AboutActivity : AppCompatActivity() {
    protected val TAG: String = javaClass.simpleName + "@" + Integer.toHexString(hashCode())

    override fun onCreate(savedInstanceState: Bundle?) {
        Localization.assureCorrectAppLanguage(this)
        super.onCreate(savedInstanceState)
        ThemeHelper.setTheme(this)
        title = getString(R.string.title_activity_about)

        val aboutBinding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(aboutBinding.root)
        setSupportActionBar(aboutBinding.aboutToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Create the adapter that will return a fragment for each of the three
        // primary sections of the activity.
        val mAboutStateAdapter = AboutStateAdapter(this)
        // Set up the ViewPager with the sections adapter.
        aboutBinding.aboutViewPager2.adapter = mAboutStateAdapter
        TabLayoutMediator(
            aboutBinding.aboutTabLayout,
            aboutBinding.aboutViewPager2,
        ) { tab, position ->
            tab.setText(mAboutStateAdapter.getPageTitle(position))
        }.attach()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    /**
     * A placeholder fragment containing a simple view.
     */
    class AboutFragment : Fragment() {
        private fun Button.openLink(@StringRes url: Int) {
            setOnClickListener {
                ShareUtils.openUrlInApp(context, requireContext().getString(url))
            }
        }

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
            FragmentAboutBinding.inflate(inflater, container, false).apply {
                aboutAppVersion.text = BuildConfig.VERSION_NAME
                aboutGithubLink.openLink(R.string.github_url)
                aboutDonationLink.openLink(R.string.donation_url)
                aboutWebsiteLink.openLink(R.string.website_url)
                aboutPrivacyPolicyLink.openLink(R.string.privacy_policy_url)
                faqLink.openLink(R.string.faq_url)
                return root
            }
        }
    }

    /**
     * A [FragmentStateAdapter] that returns a fragment corresponding to
     * one of the sections/tabs/pages.
     */
    private class AboutStateAdapter(fa: FragmentActivity) : FragmentStateAdapter(fa) {
        private val posAbout = 0
        private val posLicense = 1
        private val totalCount = 2

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                posAbout -> AboutFragment()
                posLicense -> LicenseFragment.newInstance(SOFTWARE_COMPONENTS)
                else -> throw IllegalArgumentException("Unknown position for ViewPager2")
            }
        }

        override fun getItemCount(): Int {
            // Show 2 total pages.
            return totalCount
        }

        fun getPageTitle(position: Int): Int {
            return when (position) {
                posAbout -> R.string.tab_about
                posLicense -> R.string.tab_licenses
                else -> throw IllegalArgumentException("Unknown position for ViewPager2")
            }
        }
    }

    /**
     * Fragment containing the software licenses.
     */
    class LicenseFragment : Fragment() {
        private lateinit var softwareComponents: List<SoftwareComponent>
        private var activeSoftwareComponent: SoftwareComponent? = null
        private val compositeDisposable = CompositeDisposable()

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            softwareComponents =
                arguments?.parcelableArrayList<SoftwareComponent>(ARG_COMPONENTS)!!
                    .sortedBy { it.name } // Sort components by name
            activeSoftwareComponent = savedInstanceState?.getSerializable(SOFTWARE_COMPONENT_KEY) as? SoftwareComponent
        }

        override fun onDestroy() {
            compositeDisposable.dispose()
            super.onDestroy()
        }

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
            val binding = FragmentLicensesBinding.inflate(inflater, container, false)
            binding.licensesAppReadLicense.setOnClickListener {
                compositeDisposable.add(
                    showLicense(VOIVISTA_SOFTWARE_COMPONENT),
                )
            }
            for (component in softwareComponents) {
                val componentBinding =
                    ItemSoftwareComponentBinding
                        .inflate(inflater, container, false)
                componentBinding.name.text = component.name
                componentBinding.copyright.text =
                    getString(
                        R.string.copyright,
                        component.years,
                        component.copyrightOwner,
                        component.license.abbreviation,
                    )
                val root: View = componentBinding.root
                root.tag = component
                root.setOnClickListener {
                    compositeDisposable.add(
                        showLicense(component),
                    )
                }
                binding.licensesSoftwareComponents.addView(root)
                registerForContextMenu(root)
            }
            activeSoftwareComponent?.let { compositeDisposable.add(showLicense(it)) }
            return binding.root
        }

        override fun onSaveInstanceState(savedInstanceState: Bundle) {
            super.onSaveInstanceState(savedInstanceState)
            activeSoftwareComponent?.let { savedInstanceState.putSerializable(SOFTWARE_COMPONENT_KEY, it) }
        }

        private fun showLicense(softwareComponent: SoftwareComponent): Disposable {
            return if (context == null) {
                Disposable.empty()
            } else {
                val context = requireContext()
                activeSoftwareComponent = softwareComponent
                Observable.fromCallable { getFormattedLicense(context, softwareComponent.license) }
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe { formattedLicense ->
                        val webViewData =
                            Base64.encodeToString(
                                formattedLicense.toByteArray(),
                                Base64.NO_PADDING,
                            )
                        val webView = WebView(context)
                        webView.loadData(webViewData, "text/html; charset=UTF-8", "base64")

                        Localization.assureCorrectAppLanguage(context)
                        val builder =
                            AlertDialog.Builder(requireContext())
                                .setTitle(softwareComponent.name)
                                .setView(webView)
                                .setOnCancelListener { activeSoftwareComponent = null }
                                .setOnDismissListener { activeSoftwareComponent = null }
                                .setPositiveButton(R.string.done) { dialog, _ -> dialog.dismiss() }

                        if (softwareComponent != VOIVISTA_SOFTWARE_COMPONENT) {
                            builder.setNeutralButton(R.string.open_website_license) { _, _ ->
                                ShareUtils.openUrlInApp(requireContext(), softwareComponent.link)
                            }
                        }

                        builder.show()
                    }
            }
        }

        /**
         * @param context the context to use
         * @param license the license
         * @return String which contains a HTML formatted license page
         * styled according to the context's theme
         */
        fun getFormattedLicense(context: Context, license: License): String {
            try {
                return context.assets.open(license.filename).bufferedReader().use { it.readText() }
                    // split the HTML file and insert the stylesheet into the HEAD of the file
                    .replace("</head>", "<style>${getLicenseStylesheet(context)}</style></head>")
            } catch (e: IOException) {
                throw IllegalArgumentException("Could not get license file: ${license.filename}", e)
            }
        }

        /**
         * @param context the Android context
         * @return String which is a CSS stylesheet according to the context's theme
         */
        fun getLicenseStylesheet(context: Context): String {
            val isLightTheme = ThemeHelper.isLightThemeSelected(context)
            val licenseBackgroundColor = getHexRGBColor(context, if (isLightTheme) R.color.light_license_background_color else R.color.dark_license_background_color)
            val licenseTextColor = getHexRGBColor(context, if (isLightTheme) R.color.light_license_text_color else R.color.dark_license_text_color)
            val youtubePrimaryColor = getHexRGBColor(context, if (isLightTheme) R.color.light_youtube_primary_color else R.color.dark_youtube_primary_color)
            return "body{padding:12px 15px;margin:0;background:#$licenseBackgroundColor;color:#$licenseTextColor}" +
                    "a[href]{color:#$youtubePrimaryColor}pre{white-space:pre-wrap}"
        }

        /**
         * Cast R.color to a hexadecimal color value.
         *
         * @param context the context to use
         * @param color   the color number from R.color
         * @return a six characters long String with hexadecimal RGB values
         */
        fun getHexRGBColor(context: Context, color: Int): String {
            return context.getString(color).substring(3)
        }

        companion object {
            private const val ARG_COMPONENTS = "components"
            private const val SOFTWARE_COMPONENT_KEY = "ACTIVE_SOFTWARE_COMPONENT"
            private val VOIVISTA_SOFTWARE_COMPONENT =
                SoftwareComponent(
                    "VoiVista",
                    "2014-2023",
                    "Team VoiVista",
                    "https://github.com/XilinJia/VoiVista/",
                    StandardLicenses.GPL3,
                    BuildConfig.VERSION_NAME,
                )

            fun newInstance(softwareComponents: ArrayList<SoftwareComponent>): LicenseFragment {
                val fragment = LicenseFragment()
                fragment.arguments = bundleOf(ARG_COMPONENTS to softwareComponents)
                return fragment
            }
        }
    }

    @Parcelize
    class SoftwareComponent
    @JvmOverloads
    constructor(val name: String, val years: String, val copyrightOwner: String, val link: String, val license: License, val version: String? = null)
        : Parcelable, Serializable

    /**
     * Class containing information about standard software licenses.
     */
    object StandardLicenses {
        @JvmField
        val GPL3 = License("GNU General Public License, Version 3.0", "GPLv3", "gpl_3.html")

        @JvmField
        val APACHE2 = License("Apache License, Version 2.0", "ALv2", "apache2.html")

        @JvmField
        val MPL2 = License("Mozilla Public License, Version 2.0", "MPL 2.0", "mpl2.html")

        @JvmField
        val MIT = License("MIT License", "MIT", "mit.html")

        @JvmField
        val EPL1 = License("Eclipse Public License, Version 1.0", "EPL 1.0", "epl1.html")
    }

    /**
     * Class for storing information about a software license.
     */
    @Parcelize
    class License(val name: String, val abbreviation: String, val filename: String) : Parcelable, Serializable

    companion object {
        /**
         * List of all software components.
         */
        private val SOFTWARE_COMPONENTS =
            arrayListOf(
                SoftwareComponent(
                    "ACRA",
                    "2013",
                    "Kevin Gaudin",
                    "https://github.com/ACRA/acra",
                    StandardLicenses.APACHE2,
                ),
                SoftwareComponent(
                    "AndroidX",
                    "2005 - 2011",
                    "The Android Open Source Project",
                    "https://developer.android.com/jetpack",
                    StandardLicenses.APACHE2,
                ),
                SoftwareComponent(
                    "ExoPlayer",
                    "2014 - 2020",
                    "Google, Inc.",
                    "https://github.com/google/ExoPlayer",
                    StandardLicenses.APACHE2,
                ),
                SoftwareComponent(
                    "GigaGet",
                    "2014 - 2015",
                    "Peter Cai",
                    "https://github.com/PaperAirplane-Dev-Team/GigaGet",
                    StandardLicenses.GPL3,
                ),
                SoftwareComponent(
                    "Groupie",
                    "2016",
                    "Lisa Wray",
                    "https://github.com/lisawray/groupie",
                    StandardLicenses.MIT,
                ),
                SoftwareComponent(
                    "Icepick",
                    "2015",
                    "Frankie Sardo",
                    "https://github.com/frankiesardo/icepick",
                    StandardLicenses.EPL1,
                ),
                SoftwareComponent(
                    "Jsoup",
                    "2009 - 2020",
                    "Jonathan Hedley",
                    "https://github.com/jhy/jsoup",
                    StandardLicenses.MIT,
                ),
                SoftwareComponent(
                    "Markwon",
                    "2019",
                    "Dimitry Ivanov",
                    "https://github.com/noties/Markwon",
                    StandardLicenses.APACHE2,
                ),
                SoftwareComponent(
                    "Material Components for Android",
                    "2016 - 2020",
                    "Google, Inc.",
                    "https://github.com/material-components/material-components-android",
                    StandardLicenses.APACHE2,
                ),
                SoftwareComponent(
                    "VoiVista Extractor",
                    "2017 - 2020",
                    "Christian Schabesberger",
                    "https://github.com/XilinJia/VoiVistaGuide",
                    StandardLicenses.GPL3,
                ),
                SoftwareComponent(
                    "NoNonsense-FilePicker",
                    "2016",
                    "Jonas Kalderstam",
                    "https://github.com/spacecowboy/NoNonsense-FilePicker",
                    StandardLicenses.MPL2,
                ),
                SoftwareComponent(
                    "OkHttp",
                    "2019",
                    "Square, Inc.",
                    "https://square.github.io/okhttp/",
                    StandardLicenses.APACHE2,
                ),
                SoftwareComponent(
                    "Picasso",
                    "2013",
                    "Square, Inc.",
                    "https://square.github.io/picasso/",
                    StandardLicenses.APACHE2,
                ),
                SoftwareComponent(
                    "PrettyTime",
                    "2012 - 2020",
                    "Lincoln Baxter, III",
                    "https://github.com/ocpsoft/prettytime",
                    StandardLicenses.APACHE2,
                ),
                SoftwareComponent(
                    "ProcessPhoenix",
                    "2015",
                    "Jake Wharton",
                    "https://github.com/JakeWharton/ProcessPhoenix",
                    StandardLicenses.APACHE2,
                ),
                SoftwareComponent(
                    "RxAndroid",
                    "2015",
                    "The RxAndroid authors",
                    "https://github.com/ReactiveX/RxAndroid",
                    StandardLicenses.APACHE2,
                ),
                SoftwareComponent(
                    "RxBinding",
                    "2015",
                    "Jake Wharton",
                    "https://github.com/JakeWharton/RxBinding",
                    StandardLicenses.APACHE2,
                ),
                SoftwareComponent(
                    "RxJava",
                    "2016 - 2020",
                    "RxJava Contributors",
                    "https://github.com/ReactiveX/RxJava",
                    StandardLicenses.APACHE2,
                ),
                SoftwareComponent(
                    "SearchPreference",
                    "2018",
                    "ByteHamster",
                    "https://github.com/ByteHamster/SearchPreference",
                    StandardLicenses.MIT,
                ),
            )
    }
}
