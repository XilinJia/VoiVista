/*
* Created by Christian Schabesberger on 02.08.16.
* <p>
* Copyright (C) Christian Schabesberger 2016 <chris.schabesberger@mailbox.org>
* Copyright (C) 2024 Xilin Jia <https://github.com/XilinJia>
* DownloadActivity.kt is part of Vista.
* <p>
* Vista is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
* <p>
* Vista is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
* <p>
* You should have received a copy of the GNU General Public License
* along with Vista.  If not, see <http://www.gnu.org/licenses/>.
*/
package ac.mdiq.vista.ui.activity

import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.view.*
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.drawerlayout.widget.DrawerLayout.SimpleDrawerListener
import androidx.fragment.app.FragmentManager
import androidx.media3.common.util.UnstableApi
import androidx.preference.PreferenceManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetBehavior.BottomSheetCallback
import ac.mdiq.vista.App
import ac.mdiq.vista.BuildConfig
import ac.mdiq.vista.NewVersionWorker.Companion.enqueueNewVersionCheckingWork
import ac.mdiq.vista.R
import ac.mdiq.vista.databinding.*
import ac.mdiq.vista.util.error.ErrorUtil.Companion.showUiErrorSnackbar
import ac.mdiq.vista.extractor.Vista
import ac.mdiq.vista.extractor.StreamingService.LinkType
import ac.mdiq.vista.extractor.exceptions.ExtractionException
import ac.mdiq.vista.ui.util.BackPressable
import ac.mdiq.vista.ui.fragments.MainFragment
import ac.mdiq.vista.ui.fragments.VideoDetailFragment
import ac.mdiq.vista.ui.fragments.CommentRepliesFragment
import ac.mdiq.vista.ui.fragments.SearchFragment
import ac.mdiq.vista.local.feed.notifications.NotificationWorker.Companion.initialize
import ac.mdiq.vista.player.PlayerManager
import ac.mdiq.vista.ui.player.OnKeyDownListener
import ac.mdiq.vista.ui.holder.PlayerHolder
import ac.mdiq.vista.player.playqueue.PlayQueue
import ac.mdiq.vista.util.*
import ac.mdiq.vista.util.DeviceUtils.isTv
import ac.mdiq.vista.util.KioskTranslator.getKioskIcon
import ac.mdiq.vista.util.KioskTranslator.getTranslatedKioskName
import ac.mdiq.vista.util.Localization.assureCorrectAppLanguage
import ac.mdiq.vista.util.Localization.initPrettyTime
import ac.mdiq.vista.util.Localization.resolvePrettyTime
import ac.mdiq.vista.ui.util.NavigationHelper.gotoMainFragment
import ac.mdiq.vista.ui.util.NavigationHelper.openAbout
import ac.mdiq.vista.ui.util.NavigationHelper.openBookmarksFragment
import ac.mdiq.vista.ui.util.NavigationHelper.openChannelFragment
import ac.mdiq.vista.ui.util.NavigationHelper.openDownloads
import ac.mdiq.vista.ui.util.NavigationHelper.openFeedFragment
import ac.mdiq.vista.ui.util.NavigationHelper.openKioskFragment
import ac.mdiq.vista.ui.util.NavigationHelper.openMainActivity
import ac.mdiq.vista.ui.util.NavigationHelper.openMainFragment
import ac.mdiq.vista.ui.util.NavigationHelper.openPlaylistFragment
import ac.mdiq.vista.ui.util.NavigationHelper.openSearchFragment
import ac.mdiq.vista.ui.util.NavigationHelper.openSettings
import ac.mdiq.vista.ui.util.NavigationHelper.openStatisticFragment
import ac.mdiq.vista.ui.util.NavigationHelper.openSubscriptionFragment
import ac.mdiq.vista.ui.util.NavigationHelper.openVideoDetailFragment
import ac.mdiq.vista.ui.util.NavigationHelper.showMiniPlayer
import ac.mdiq.vista.ui.util.NavigationHelper.tryGotoSearchFragment
import ac.mdiq.vista.util.PeertubeHelper.currentInstance
import ac.mdiq.vista.util.PeertubeHelper.getInstanceList
import ac.mdiq.vista.util.PeertubeHelper.selectInstance
import ac.mdiq.vista.util.PermissionHelper.checkPostNotificationsPermission
import ac.mdiq.vista.util.ServiceHelper.getIcon
import ac.mdiq.vista.util.ServiceHelper.getSelectedService
import ac.mdiq.vista.util.ServiceHelper.getSelectedServiceId
import ac.mdiq.vista.util.ServiceHelper.setSelectedServiceId
import ac.mdiq.vista.util.StateSaver.clearStateFiles
import ac.mdiq.vista.ui.util.ThemeHelper.setDayNightMode
import ac.mdiq.vista.ui.util.ThemeHelper.setTheme
import ac.mdiq.vista.ui.views.FocusOverlayView.Companion.setupFocusObserver

@UnstableApi
class MainActivity : AppCompatActivity() {
    private var binding: ActivityMainBinding? = null
    private var dhBinding: DrawerHeaderBinding? = null
    private var dlBinding: DrawerLayoutBinding? = null
    private var tlBinding: ToolbarLayoutBinding? = null
    private var toggle: ActionBarDrawerToggle? = null
    private var servicesShown = false
    private var broadcastReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        Logd(TAG, "onCreate() called with: savedInstanceState = [$savedInstanceState]")
        if (BuildConfig.DEBUG) {
            val builder = StrictMode.ThreadPolicy.Builder()
                .detectAll()  // Enable all detections
                .penaltyLog()  // Log violations to the console
            StrictMode.setThreadPolicy(builder.build())
        }

        setDayNightMode(this)
        setTheme(this, getSelectedServiceId(this))

        assureCorrectAppLanguage(this)
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        dlBinding = binding!!.drawerLayout
        dhBinding = DrawerHeaderBinding.bind(dlBinding!!.navigation.getHeaderView(0))
        tlBinding = binding!!.toolbarLayout
        setContentView(binding!!.root)

        if (supportFragmentManager.backStackEntryCount == 0) initFragments()

        setSupportActionBar(tlBinding!!.toolbar)
        try {
            setupDrawer()
        } catch (e: Exception) {
            showUiErrorSnackbar(this, "Setting up drawer", e)
        }
        if (isTv(this)) setupFocusObserver(this)

        openMiniPlayerUponPlayerStarted()

        // Schedule worker for checking for new streams and creating corresponding notifications
        // if this is enabled by the user.
        if (checkPostNotificationsPermission(this, PermissionHelper.POST_NOTIFICATIONS_REQUEST_CODE)) initialize(this)
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)

        val app = App.getApp()
        val prefs = PreferenceManager.getDefaultSharedPreferences(app)

        // Start the worker which is checking all conditions
        // and eventually searching for a new version.
        if (prefs.getBoolean(app.getString(R.string.update_app_key), true)) enqueueNewVersionCheckingWork(app, false)
    }

    @Throws(ExtractionException::class)
    private fun setupDrawer() {
        addDrawerMenuForCurrentService()

        toggle = ActionBarDrawerToggle(this, binding!!.root, tlBinding!!.toolbar,
            R.string.drawer_open,
            R.string.drawer_close)
        toggle!!.syncState()
        binding!!.root.addDrawerListener(toggle!!)
        binding!!.root.addDrawerListener(object : SimpleDrawerListener() {
            private var lastService = 0

            override fun onDrawerOpened(drawerView: View) {
                lastService = getSelectedServiceId(this@MainActivity)
            }
            override fun onDrawerClosed(drawerView: View) {
                if (servicesShown) toggleServices()
                if (lastService != getSelectedServiceId(this@MainActivity)) ActivityCompat.recreate(this@MainActivity)
            }
        })

        dlBinding!!.navigation.setNavigationItemSelectedListener { item: MenuItem ->
            this.drawerItemSelected(item)
        }
        setupDrawerHeader()
    }

    /**
     * Builds the drawer menu for the current service.
     *
     * @throws ExtractionException if the service didn't provide available kiosks
     */
    @Throws(ExtractionException::class)
    private fun addDrawerMenuForCurrentService() {
        //Tabs
        val currentServiceId = getSelectedServiceId(this)
        val service = Vista.getService(currentServiceId)

        for ((kioskMenuItemId, ks) in service.getKioskList().availableKiosks.withIndex()) {
            if (ks == null) continue
            dlBinding!!.navigation.menu.add(R.id.menu_tabs_group, kioskMenuItemId, 0, getTranslatedKioskName(ks, this)).setIcon(getKioskIcon(ks))
        }

        dlBinding!!.navigation.menu.add(R.id.menu_tabs_group, ITEM_ID_SUBSCRIPTIONS, ORDER, R.string.tab_subscriptions).setIcon(R.drawable.ic_tv)
        dlBinding!!.navigation.menu.add(R.id.menu_tabs_group, ITEM_ID_FEED, ORDER, R.string.fragment_feed_title).setIcon(R.drawable.ic_subscriptions)
        dlBinding!!.navigation.menu.add(R.id.menu_tabs_group, ITEM_ID_BOOKMARKS, ORDER, R.string.tab_bookmarks).setIcon(R.drawable.ic_bookmark)
        dlBinding!!.navigation.menu.add(R.id.menu_tabs_group, ITEM_ID_DOWNLOADS, ORDER,
            R.string.downloads).setIcon(R.drawable.ic_file_download)
        dlBinding!!.navigation.menu.add(R.id.menu_tabs_group, ITEM_ID_HISTORY, ORDER,
            R.string.action_history).setIcon(R.drawable.ic_history)

        //Settings and About
        dlBinding!!.navigation.menu.add(R.id.menu_options_about_group, ITEM_ID_SETTINGS, ORDER, R.string.settings).setIcon(R.drawable.ic_settings)
        dlBinding!!.navigation.menu.add(R.id.menu_options_about_group, ITEM_ID_ABOUT, ORDER, R.string.tab_about).setIcon(R.drawable.ic_info_outline)
    }

    private fun drawerItemSelected(item: MenuItem): Boolean {
        when (item.groupId) {
            R.id.menu_services_group -> changeService(item)
            R.id.menu_tabs_group -> try {
                tabSelected(item)
            } catch (e: Exception) {
                showUiErrorSnackbar(this, "Selecting main page tab", e)
            }
            R.id.menu_options_about_group -> optionsAboutSelected(item)
            else -> return false
        }
        binding!!.root.closeDrawers()
        return true
    }

    private fun changeService(item: MenuItem) {
        dlBinding!!.navigation.menu.getItem(getSelectedServiceId(this)).setChecked(false)
        setSelectedServiceId(this, item.itemId)
        dlBinding!!.navigation.menu.getItem(getSelectedServiceId(this)).setChecked(true)
    }

    @Throws(ExtractionException::class)
    private fun tabSelected(item: MenuItem) {
        when (item.itemId) {
            ITEM_ID_SUBSCRIPTIONS -> openSubscriptionFragment(supportFragmentManager)
            ITEM_ID_FEED -> openFeedFragment(supportFragmentManager)
            ITEM_ID_BOOKMARKS -> openBookmarksFragment(supportFragmentManager)
            ITEM_ID_DOWNLOADS -> openDownloads(this)
            ITEM_ID_HISTORY -> openStatisticFragment(supportFragmentManager)
            else -> {
                val currentService = getSelectedService(this)
                var kioskMenuItemId = 0
                for (kioskId in currentService!!.getKioskList().availableKiosks) {
                    if (kioskMenuItemId == item.itemId) {
                        openKioskFragment(supportFragmentManager, currentService.serviceId, kioskId)
                        break
                    }
                    kioskMenuItemId++
                }
            }
        }
    }

    private fun optionsAboutSelected(item: MenuItem) {
        when (item.itemId) {
            ITEM_ID_SETTINGS -> openSettings(this)
            ITEM_ID_ABOUT -> openAbout(this)
        }
    }

    private fun setupDrawerHeader() {
        dhBinding!!.drawerHeaderActionButton.setOnClickListener { toggleServices() }

        // If the current app name is bigger than the default "Vista" (7 chars),
        // let the text view grow a little more as well.
        if (getString(R.string.app_name).length > "VoiVista".length) {
            val layoutParams = dhBinding!!.drawerHeaderVoivistaTitle.layoutParams
            layoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
            dhBinding!!.drawerHeaderVoivistaTitle.layoutParams = layoutParams
            dhBinding!!.drawerHeaderVoivistaTitle.maxLines = 2
            dhBinding!!.drawerHeaderVoivistaTitle.minWidth = resources
                .getDimensionPixelSize(R.dimen.drawer_header_voivista_title_default_width)
            dhBinding!!.drawerHeaderVoivistaTitle.maxWidth = resources
                .getDimensionPixelSize(R.dimen.drawer_header_voivista_title_max_width)
        }
    }

    private fun toggleServices() {
        servicesShown = !servicesShown
        dlBinding!!.navigation.menu.removeGroup(R.id.menu_services_group)
        dlBinding!!.navigation.menu.removeGroup(R.id.menu_tabs_group)
        dlBinding!!.navigation.menu.removeGroup(R.id.menu_options_about_group)

        // Show up or down arrow
        dhBinding!!.drawerArrow.setImageResource(if (servicesShown) R.drawable.ic_arrow_drop_up else R.drawable.ic_arrow_drop_down)

        if (servicesShown) showServices()
        else {
            try {
                addDrawerMenuForCurrentService()
            } catch (e: Exception) {
                showUiErrorSnackbar(this, "Showing main page tabs", e)
            }
        }
    }

    private fun showServices() {
        for (s in Vista.services) {
            val title = s.serviceInfo.name
            val menuItem = dlBinding!!.navigation.menu.add(R.id.menu_services_group, s.serviceId, ORDER, title).setIcon(getIcon(s.serviceId))
            // peertube specifics
            if (s.serviceId == 3) enhancePeertubeMenu(menuItem)
        }
        dlBinding!!.navigation.menu.getItem(getSelectedServiceId(this)).setChecked(true)
    }

    private fun enhancePeertubeMenu(menuItem: MenuItem) {
        val currentInstance = currentInstance
        menuItem.setTitle(currentInstance.name)
        val spinner = InstanceSpinnerLayoutBinding.inflate(LayoutInflater.from(this)).root
        val instances = getInstanceList(this)
        val items: MutableList<String> = ArrayList()
        var defaultSelect = 0
        for (instance in instances) {
            items.add(instance.name)
            if (instance.url == currentInstance.url) defaultSelect = items.size - 1
        }
        val adapter = ArrayAdapter(this, R.layout.instance_spinner_item, items)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setSelection(defaultSelect, false)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View, position: Int, id: Long) {
                val newInstance = instances[position]
                if (newInstance.url == PeertubeHelper.currentInstance.url) return

                selectInstance(newInstance, applicationContext)
                changeService(menuItem)
                binding!!.root.closeDrawers()
                Handler(Looper.getMainLooper()).postDelayed({
                    supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
                    ActivityCompat.recreate(this@MainActivity)
                }, 300)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        menuItem.setActionView(spinner)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!isChangingConfigurations) clearStateFiles()
        if (broadcastReceiver != null) unregisterReceiver(broadcastReceiver)
    }

    override fun onResume() {
        assureCorrectAppLanguage(this)
        // Change the date format to match the selected language on resume
        initPrettyTime(resolvePrettyTime(applicationContext))
        super.onResume()

        // Close drawer on return, and don't show animation,
        // so it looks like the drawer isn't open when the user returns to MainActivity
        binding!!.root.closeDrawer(GravityCompat.START, false)
        try {
            val selectedServiceId = getSelectedServiceId(this)
            val selectedServiceName = Vista.getService(selectedServiceId).serviceInfo.name
            dhBinding!!.drawerHeaderServiceView.text = selectedServiceName
            dhBinding!!.drawerHeaderServiceIcon.setImageResource(getIcon(selectedServiceId))
            dhBinding!!.drawerHeaderServiceView.post {
                dhBinding!!.drawerHeaderServiceView.isSelected = true
            }
            dhBinding!!.drawerHeaderActionButton.contentDescription = getString(R.string.drawer_header_description) + selectedServiceName
        } catch (e: Exception) {
            showUiErrorSnackbar(this, "Setting up service toggle", e)
        }

        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        if (sharedPreferences.getBoolean(KEY_THEME_CHANGE, false)) {
            Logd(TAG, "Theme has changed, recreating activity...")
            sharedPreferences.edit().putBoolean(KEY_THEME_CHANGE, false).apply()
            ActivityCompat.recreate(this)
        }

        if (sharedPreferences.getBoolean(KEY_MAIN_PAGE_CHANGE, false)) {
            Logd(TAG, "main page has changed, recreating main fragment...")
            sharedPreferences.edit().putBoolean(KEY_MAIN_PAGE_CHANGE, false).apply()
            openMainActivity(this)
        }

        val isHistoryEnabled = sharedPreferences.getBoolean(getString(R.string.enable_watch_history_key), true)
        dlBinding!!.navigation.menu.findItem(ITEM_ID_HISTORY).setVisible(isHistoryEnabled)
    }

    override fun onNewIntent(intent: Intent) {
        Logd(TAG, "onNewIntent() called with: intent = [$intent]")
        // Return if launched from a launcher (e.g. Nova Launcher, Pixel Launcher ...)
        // to not destroy the already created backstack
        val action = intent.action
        if (action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER)) return

        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val fragment = supportFragmentManager.findFragmentById(R.id.fragment_player_holder)
        // Provide keyDown event to fragment which then sends this event
        // to the main player service
        if (fragment is OnKeyDownListener && !bottomSheetHiddenOrCollapsed())
            return ((fragment as OnKeyDownListener).onKeyDown(keyCode) || super.onKeyDown(keyCode, event))
        return super.onKeyDown(keyCode, event)
    }

    override fun onBackPressed() {
        Logd(TAG, "onBackPressed() called")
        if (isTv(this)) {
            if (binding!!.root.isDrawerOpen(dlBinding!!.navigation)) {
                binding!!.root.closeDrawers()
                return
            }
        }

        // In case bottomSheet is not visible on the screen or collapsed we can assume that the user
        // interacts with a fragment inside fragment_holder so all back presses should be
        // handled by it
        if (bottomSheetHiddenOrCollapsed()) {
            val fm = supportFragmentManager
            val fragment = fm.findFragmentById(R.id.fragment_holder)
            // If current fragment implements BackPressable (i.e. can/wanna handle back press)
            // delegate the back press to it
            if (fragment is BackPressable) {
                if ((fragment as BackPressable).onBackPressed()) return
            } else if (fragment is CommentRepliesFragment) {
                // expand DetailsFragment if CommentRepliesFragment was opened
                // to show the top level comments again
                // Expand DetailsFragment if CommentRepliesFragment was opened
                // and no other CommentRepliesFragments are on top of the back stack
                // to show the top level comments again.
                openDetailFragmentFromCommentReplies(fm, false)
            }
        } else {
            val fragmentPlayer = supportFragmentManager.findFragmentById(R.id.fragment_player_holder)
            // If current fragment implements BackPressable (i.e. can/wanna handle back press)
            // delegate the back press to it
            if (fragmentPlayer is BackPressable) {
                if (!(fragmentPlayer as BackPressable).onBackPressed())
                    BottomSheetBehavior.from(binding!!.fragmentPlayerHolder).state = BottomSheetBehavior.STATE_COLLAPSED
                return
            }
        }
        if (supportFragmentManager.backStackEntryCount <= 1) finish()
        else super.onBackPressed()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        for (i in grantResults) {
            if (i == PackageManager.PERMISSION_DENIED) return
        }
        when (requestCode) {
            PermissionHelper.DOWNLOADS_REQUEST_CODE -> openDownloads(this)
            PermissionHelper.DOWNLOAD_DIALOG_REQUEST_CODE -> {
                val fragment = supportFragmentManager.findFragmentById(R.id.fragment_player_holder)
                if (fragment is VideoDetailFragment) fragment.openDownloadDialog()
            }
            PermissionHelper.POST_NOTIFICATIONS_REQUEST_CODE -> initialize(this)
        }
    }

    /**
     * Implement the following diagram behavior for the up button:
     * <pre>
     * +---------------+
     * |  Main Screen  +----+
     * +-------+-------+    |
     * |            |
     * ▲ Up         | Search Button
     * |            |
     * +----+-----+      |
     * +------------+  Search  |◄-----+
     * |            +----+-----+
     * |   Open          |
     * |  something      ▲ Up
     * |                 |
     * |    +------------+-------------+
     * |    |                          |
     * |    |  Video    <->  Channel   |
     * +---►|  Channel  <->  Playlist  |
     * |  Video    <->  ....      |
     * |                          |
     * +--------------------------+
    </pre> *
     */
    private fun onHomeButtonPressed() {
        val fm = supportFragmentManager
        val fragment = fm.findFragmentById(R.id.fragment_holder)

        // Expand DetailsFragment if CommentRepliesFragment was opened
        // and no other CommentRepliesFragments are on top of the back stack
        // to show the top level comments again.
        if (fragment is CommentRepliesFragment) openDetailFragmentFromCommentReplies(fm, true)
        // If search fragment wasn't found in the backstack go to the main fragment
        else if (!tryGotoSearchFragment(fm)) gotoMainFragment(fm)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
//        Logd(TAG, "onCreateOptionsMenu() called with: menu = [$menu]")
        super.onCreateOptionsMenu(menu)

        val fragment = supportFragmentManager.findFragmentById(R.id.fragment_holder)
        if (fragment !is SearchFragment) tlBinding!!.toolbarSearchContainer.root.visibility = View.GONE

        val actionBar = supportActionBar
        actionBar?.setDisplayHomeAsUpEnabled(false)

        updateDrawerNavigation()

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        Logd(TAG, "onOptionsItemSelected() called with: item = [$item]")
        if (item.itemId == android.R.id.home) {
            onHomeButtonPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun initFragments() {
        Logd(TAG, "initFragments() called")
        clearStateFiles()
        if (intent != null && intent.hasExtra(KEY_LINK_TYPE)) {
            // When user watch a video inside popup and then tries to open the video in main player
            // while the app is closed he will see a blank fragment on place of kiosk.
            // Let's open it first
            if (supportFragmentManager.backStackEntryCount == 0) openMainFragment(supportFragmentManager)
            handleIntent(intent)
        } else gotoMainFragment(supportFragmentManager)
    }

    private fun updateDrawerNavigation() {
        if (supportActionBar == null) return

        val fragment = supportFragmentManager.findFragmentById(R.id.fragment_holder)
        if (fragment is MainFragment) {
            supportActionBar!!.setDisplayHomeAsUpEnabled(false)
            if (toggle != null) {
                toggle!!.syncState()
                tlBinding!!.toolbar.setNavigationOnClickListener { binding!!.root.open() }
                binding!!.root.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNDEFINED)
            }
        } else {
            binding!!.root.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
            supportActionBar!!.setDisplayHomeAsUpEnabled(true)
            tlBinding!!.toolbar.setNavigationOnClickListener { onHomeButtonPressed() }
        }
    }

    private fun handleIntent(intent: Intent) {
        try {
            Logd(TAG, "handleIntent() called with: intent = [$intent]")
            when {
                intent.hasExtra(KEY_LINK_TYPE) -> {
                    val url = intent.getStringExtra(KEY_URL)
                    val serviceId = intent.getIntExtra(KEY_SERVICE_ID, 0)
                    val title = intent.getStringExtra(KEY_TITLE) ?: ""

                    val linkType = intent.getSerializableExtra(KEY_LINK_TYPE) as? LinkType
                    when (linkType) {
                        LinkType.STREAM -> {
                            val intentCacheKey = intent.getStringExtra(PlayerManager.PLAY_QUEUE_KEY)
                            val playQueue: PlayQueue? =
                                if (intentCacheKey != null) SerializedCache.instance.take(intentCacheKey, PlayQueue::class.java)
                                else null
                            val switchingPlayers = intent.getBooleanExtra(VideoDetailFragment.KEY_SWITCHING_PLAYERS, false)
                            openVideoDetailFragment(applicationContext, supportFragmentManager, serviceId, url, title, playQueue, switchingPlayers)
                        }
                        LinkType.CHANNEL -> openChannelFragment(supportFragmentManager, serviceId, url, title)
                        LinkType.PLAYLIST -> openPlaylistFragment(supportFragmentManager, serviceId, url, title)
                        else -> {}
                    }
                }
                intent.hasExtra(KEY_OPEN_SEARCH) -> {
                    val searchString = intent.getStringExtra(KEY_SEARCH_STRING) ?: ""
                    val serviceId = intent.getIntExtra(KEY_SERVICE_ID, 0)
                    openSearchFragment(supportFragmentManager, serviceId, searchString)
                }
                else -> gotoMainFragment(supportFragmentManager)
            }
        } catch (e: Exception) {
            showUiErrorSnackbar(this, "Handling intent", e)
        }
    }

    private fun openMiniPlayerIfMissing() {
        val fragmentPlayer = supportFragmentManager.findFragmentById(R.id.fragment_player_holder)
        // We still don't have a fragment attached to the activity. It can happen when a user
        // started popup or background players without opening a stream inside the fragment.
        // Adding it in a collapsed state (only mini player will be visible).
        if (fragmentPlayer == null) showMiniPlayer(supportFragmentManager)
    }

    private fun openMiniPlayerUponPlayerStarted() {
        // handleIntent() already takes care of opening video detail fragment
        // due to an intent containing a STREAM link
        if (intent.getSerializableExtra(KEY_LINK_TYPE) === LinkType.STREAM) return

        // if the player is already open, no need for a broadcast receiver
        if (PlayerHolder.instance?.isPlayerOpen == true) openMiniPlayerIfMissing()
        else {
            // listen for player start intent being sent around
            broadcastReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == VideoDetailFragment.ACTION_PLAYER_STARTED) {
                        openMiniPlayerIfMissing()
                        // At this point the player is added 100%, we can unregister. Other actions
                        // are useless since the fragment will not be removed after that.
                        unregisterReceiver(broadcastReceiver)
                        broadcastReceiver = null
                    }
                }
            }
            val intentFilter = IntentFilter()
            intentFilter.addAction(VideoDetailFragment.ACTION_PLAYER_STARTED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerReceiver(broadcastReceiver, intentFilter, RECEIVER_NOT_EXPORTED)
            else registerReceiver(broadcastReceiver, intentFilter)

        }
    }

    private fun openDetailFragmentFromCommentReplies(fm: FragmentManager, popBackStack: Boolean) {
        // obtain the name of the fragment under the replies fragment that's going to be popped
        val fragmentUnderEntryName = if (fm.backStackEntryCount < 2) null else fm.getBackStackEntryAt(fm.backStackEntryCount - 2).name

        // the root comment is the comment for which the user opened the replies page
        val repliesFragment = fm.findFragmentByTag(CommentRepliesFragment.TAG) as CommentRepliesFragment?
        val rootComment = repliesFragment?.commentsInfoItem

        // sometimes this function pops the backstack, other times it's handled by the system
        if (popBackStack) fm.popBackStackImmediate()

        // only expand the bottom sheet back if there are no more nested comment replies fragments
        // stacked under the one that is currently being popped
        if (CommentRepliesFragment.TAG == fragmentUnderEntryName) return

        val behavior = BottomSheetBehavior.from(binding!!.fragmentPlayerHolder)
        // do not return to the comment if the details fragment was closed
        if (behavior.state == BottomSheetBehavior.STATE_HIDDEN) return

        // scroll to the root comment once the bottom sheet expansion animation is finished
        behavior.addBottomSheetCallback(object : BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                if (newState == BottomSheetBehavior.STATE_EXPANDED) {
                    val detailFragment = fm.findFragmentById(R.id.fragment_player_holder)
                    // should always be the case
                    if (detailFragment is VideoDetailFragment && rootComment != null) detailFragment.scrollToComment(rootComment)
                    behavior.removeBottomSheetCallback(this)
                }
            }
            override fun onSlide(bottomSheet: View, slideOffset: Float) {
                // not needed, listener is removed once the sheet is expanded
            }
        })

        behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    private fun bottomSheetHiddenOrCollapsed(): Boolean {
        val bottomSheetBehavior = BottomSheetBehavior.from<FrameLayout>(binding!!.fragmentPlayerHolder)
        val sheetState = bottomSheetBehavior.state
        return (sheetState == BottomSheetBehavior.STATE_HIDDEN || sheetState == BottomSheetBehavior.STATE_COLLAPSED)
    }

    companion object {
        private const val TAG = "MainActivity"
        const val DEBUG: Boolean = BuildConfig.BUILD_TYPE != "release"

        private const val ITEM_ID_SUBSCRIPTIONS = -1
        private const val ITEM_ID_FEED = -2
        private const val ITEM_ID_BOOKMARKS = -3
        private const val ITEM_ID_DOWNLOADS = -4
        private const val ITEM_ID_HISTORY = -5
        private const val ITEM_ID_SETTINGS = 0
        private const val ITEM_ID_ABOUT = 1

        private const val ORDER = 0
    }
}
