package ac.mdiq.vista.settings.fragment

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import ac.mdiq.vista.R
import ac.mdiq.vista.database.subscription.NotificationMode
import ac.mdiq.vista.database.subscription.SubscriptionEntity
import ac.mdiq.vista.databinding.FragmentChannelsNotificationsBinding
import ac.mdiq.vista.databinding.ItemNotificationConfigBinding
import ac.mdiq.vista.manager.SubscriptionManager
import ac.mdiq.vista.util.Logd

/**
 * [NotificationModeConfigFragment] is a settings fragment
 * which allows changing the [NotificationMode] of all subscribed channels.
 * The [NotificationMode] can either be changed one by one or toggled for all channels.
 */
class NotificationModeConfigFragment : Fragment() {
    protected val TAG: String = javaClass.simpleName + "@" + Integer.toHexString(hashCode())

    private var _binding: FragmentChannelsNotificationsBinding? = null
    private val binding get() = _binding!!

    private val disposables = CompositeDisposable()
    private var loader: Disposable? = null
    private lateinit var adapter: NotificationModeConfigAdapter
    private lateinit var subscriptionManager: SubscriptionManager

    override fun onAttach(context: Context) {
        super.onAttach(context)
        subscriptionManager = SubscriptionManager(context)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        Logd(TAG, "onCreateView")
        _binding = FragmentChannelsNotificationsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = NotificationModeConfigAdapter { position, mode ->
            // Notification mode has been changed via the UI.
            // Now change it in the database.
            updateNotificationMode(adapter.currentList[position], mode)
        }
        binding.recyclerView.adapter = adapter
        loader?.dispose()
        loader = subscriptionManager.subscriptions()
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(adapter::update)
    }

    override fun onDestroyView() {
        loader?.dispose()
        loader = null
        _binding = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        disposables.dispose()
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.menu_notifications_channels, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_toggle_all -> {
                toggleAll()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun toggleAll() {
        val mode = adapter.currentList.firstOrNull()?.notificationMode ?: return
        val newMode = when (mode) {
            NotificationMode.DISABLED -> NotificationMode.ENABLED
            else -> NotificationMode.DISABLED
        }
        adapter.currentList.forEach { updateNotificationMode(it, newMode) }
    }

    private fun updateNotificationMode(item: SubscriptionItem, @NotificationMode mode: Int) {
        disposables.add(subscriptionManager.updateNotificationMode(item.serviceId, item.url, mode).subscribeOn(Schedulers.io()).subscribe())
    }

    /**
     * This [RecyclerView.Adapter] is used in the [NotificationModeConfigFragment].
     * The adapter holds all subscribed channels and their [NotificationMode]s
     * and provides the needed data structures and methods for this task.
     */
    class NotificationModeConfigAdapter(private val listener: ModeToggleListener, ) : ListAdapter<SubscriptionItem, NotificationModeConfigAdapter.SubscriptionHolder>(
        DiffCallback) {
        override fun onCreateViewHolder(parent: ViewGroup, i: Int): SubscriptionHolder {
            return SubscriptionHolder(ItemNotificationConfigBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

        override fun onBindViewHolder(holder: SubscriptionHolder, position: Int) {
            holder.bind(currentList[position])
        }

        fun update(newData: List<SubscriptionEntity>) {
            val items = newData.map {
                SubscriptionItem(it.uid, it.name?:"", it.notificationMode, it.serviceId, it.url?:"")
            }
            submitList(items)
        }

        inner class SubscriptionHolder(private val itemBinding: ItemNotificationConfigBinding) : RecyclerView.ViewHolder(itemBinding.root) {
            init {
                itemView.setOnClickListener {
                    val mode = if (itemBinding.root.isChecked) NotificationMode.DISABLED else NotificationMode.ENABLED
                    listener.onModeChange(bindingAdapterPosition, mode)
                }
            }

            fun bind(data: SubscriptionItem) {
                itemBinding.root.text = data.title
                itemBinding.root.isChecked = data.notificationMode != NotificationMode.DISABLED
            }
        }

        private object DiffCallback : DiffUtil.ItemCallback<SubscriptionItem>() {
            override fun areItemsTheSame(oldItem: SubscriptionItem, newItem: SubscriptionItem): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(oldItem: SubscriptionItem, newItem: SubscriptionItem): Boolean {
                return oldItem == newItem
            }

            override fun getChangePayload(oldItem: SubscriptionItem, newItem: SubscriptionItem): Any? {
                return if (oldItem.notificationMode != newItem.notificationMode) newItem.notificationMode else { super.getChangePayload(oldItem, newItem) }
            }
        }

        fun interface ModeToggleListener {
            /**
             * Triggered when the UI representation of a notification mode is changed.
             */
            fun onModeChange(position: Int, @NotificationMode mode: Int)
        }
    }

    data class SubscriptionItem(
            val id: Long,
            val title: String,
            @NotificationMode
            val notificationMode: Int,
            val serviceId: Int,
            val url: String,
    )

}
