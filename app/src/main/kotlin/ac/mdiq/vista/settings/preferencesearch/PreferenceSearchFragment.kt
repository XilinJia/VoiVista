package ac.mdiq.vista.settings.preferencesearch

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ac.mdiq.vista.databinding.SettingsPreferencesearchFragmentBinding
import ac.mdiq.vista.databinding.SettingsPreferencesearchListItemResultBinding
import ac.mdiq.vista.util.Logd
import java.util.function.Consumer

/**
 * Displays the search results.
 */
class PreferenceSearchFragment : Fragment() {
    protected val TAG: String = javaClass.simpleName + "@" + Integer.toHexString(hashCode())

    private var searcher: PreferenceSearcher? = null

    private var binding: SettingsPreferencesearchFragmentBinding? = null
    private var adapter: PreferenceSearchAdapter? = null

    fun setSearcher(searcher: PreferenceSearcher?) {
        this.searcher = searcher
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        Logd(TAG, "onCreateView")
        binding = SettingsPreferencesearchFragmentBinding.inflate(inflater, container, false)

        binding!!.searchResults.layoutManager = LinearLayoutManager(context)

        adapter = PreferenceSearchAdapter()
        adapter!!.setOnItemClickListener { item: PreferenceSearchItem? -> this.onItemClicked(item) }
        binding!!.searchResults.adapter = adapter

        return binding!!.root
    }

    fun updateSearchResults(keyword: String) {
        if (adapter == null || searcher == null) return

        val results = searcher!!.searchFor(keyword)
        adapter!!.submitList(results)
        setEmptyViewShown(results.isEmpty())
    }

    private fun setEmptyViewShown(shown: Boolean) {
        binding!!.emptyStateView.visibility = if (shown) View.VISIBLE else View.GONE
        binding!!.searchResults.visibility = if (shown) View.GONE else View.VISIBLE
    }

    fun onItemClicked(item: PreferenceSearchItem?) {
        if (activity !is PreferenceSearchResultListener) throw ClassCastException(activity.toString() + " must implement SearchPreferenceResultListener")
        (activity as PreferenceSearchResultListener?)!!.onSearchResultClicked(item!!)
    }

    internal class PreferenceSearchAdapter
        : ListAdapter<PreferenceSearchItem?, PreferenceSearchAdapter.PreferenceViewHolder?>(PreferenceCallback()) {

        private var onItemClickListener: Consumer<PreferenceSearchItem?>? = null

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PreferenceViewHolder {
            return PreferenceViewHolder(SettingsPreferencesearchListItemResultBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

        override fun onBindViewHolder(holder: PreferenceViewHolder, position: Int) {
            val item = getItem(position)

            holder.binding.title.text = item!!.title

            if (item.summary.isEmpty()) holder.binding.summary.visibility = View.GONE
            else {
                holder.binding.summary.visibility = View.VISIBLE
                holder.binding.summary.text = item.summary
            }
            if (item.breadcrumbs.isEmpty()) holder.binding.breadcrumbs.visibility = View.GONE
            else {
                holder.binding.breadcrumbs.visibility = View.VISIBLE
                holder.binding.breadcrumbs.text = item.breadcrumbs
            }

            holder.itemView.setOnClickListener { v: View? ->
                if (onItemClickListener != null) onItemClickListener!!.accept(item)
            }
        }

        fun setOnItemClickListener(onItemClickListener: Consumer<PreferenceSearchItem?>?) {
            this.onItemClickListener = onItemClickListener
        }

        internal class PreferenceViewHolder(val binding: SettingsPreferencesearchListItemResultBinding) : RecyclerView.ViewHolder(binding.root)

        private class PreferenceCallback : DiffUtil.ItemCallback<PreferenceSearchItem?>() {
            override fun areItemsTheSame(oldItem: PreferenceSearchItem, newItem: PreferenceSearchItem): Boolean {
                return oldItem.key == newItem.key
            }

            override fun areContentsTheSame(oldItem: PreferenceSearchItem, newItem: PreferenceSearchItem): Boolean {
                return oldItem.allRelevantSearchFields == newItem.allRelevantSearchFields
            }
        }
    }

    companion object {
        val NAME: String = PreferenceSearchFragment::class.java.simpleName
    }
}
