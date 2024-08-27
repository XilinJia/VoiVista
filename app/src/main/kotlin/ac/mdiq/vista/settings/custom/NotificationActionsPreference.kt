package ac.mdiq.vista.settings.custom

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.os.Build
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.widget.TextViewCompat
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import ac.mdiq.vista.App
import ac.mdiq.vista.R
import ac.mdiq.vista.databinding.ListRadioIconItemBinding
import ac.mdiq.vista.databinding.SingleChoiceDialogViewBinding
import ac.mdiq.vista.player.notification.NotificationConstants
import ac.mdiq.vista.ui.util.ThemeHelper.resolveColorFromAttr
import ac.mdiq.vista.ui.views.FocusOverlayView.Companion.setupFocusObserver
import ac.mdiq.vista.util.DeviceUtils.isTv
import java.util.function.BiConsumer
import java.util.stream.IntStream

class NotificationActionsPreference(context: Context?, attrs: AttributeSet?) : Preference(context!!, attrs) {
    private var notificationSlots: Array<NotificationSlot>? = null
    private var compactSlots: MutableList<Int>? = null


    init {
        layoutResource = R.layout.settings_notification
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            (holder.itemView.findViewById<View>(R.id.summary) as TextView).setText(R.string.notification_actions_summary_android13)

        holder.itemView.isClickable = false
        setupActions(holder.itemView)
    }

    override fun onDetached() {
        super.onDetached()
        saveChanges()
        // set package to this app's package to prevent the intent from being seen outside
        context.sendBroadcast(Intent(NotificationConstants.ACTION_RECREATE_NOTIFICATION).setPackage(App.PACKAGE_NAME))
    }

    private fun setupActions(view: View) {
        if (sharedPreferences == null) return
        compactSlots = ArrayList(NotificationConstants.getCompactSlotsFromPreferences(context, sharedPreferences!!))
        notificationSlots = IntStream.range(0, 5)
            .mapToObj { i: Int -> NotificationSlot(context, sharedPreferences!!, i, view,
                compactSlots!!.contains(i)) { j: Int, checkBox: CheckBox -> this.onToggleCompactSlot(j, checkBox) } }
            .toArray { size -> arrayOfNulls<NotificationSlot>(size) }
    }

    private fun onToggleCompactSlot(i: Int, checkBox: CheckBox) {
        when {
            checkBox.isChecked -> compactSlots!!.remove(i)
            compactSlots!!.size < 3 -> compactSlots!!.add(i)
            else -> {
                Toast.makeText(context, R.string.notification_actions_at_most_three, Toast.LENGTH_SHORT).show()
                return
            }
        }
        checkBox.toggle()
    }


    private fun saveChanges() {
        if (compactSlots != null && notificationSlots != null) {
            val editor = sharedPreferences!!.edit()

            for (i in 0..2) {
                editor.putInt(context.getString(NotificationConstants.SLOT_COMPACT_PREF_KEYS[i]), (if (i < compactSlots!!.size) compactSlots!![i] else -1))
            }
            for (i in 0..4) {
                editor.putInt(context.getString(NotificationConstants.SLOT_PREF_KEYS[i]), notificationSlots!![i].selectedAction)
            }
            editor.apply()
        }
    }

    internal class NotificationSlot(
            private val context: Context,
            prefs: SharedPreferences,
            private val i: Int,
            parentView: View,
            isCompactSlotChecked: Boolean,
            private val onToggleCompactSlot: BiConsumer<Int, CheckBox>) {

        @get:NotificationConstants.Action
        @NotificationConstants.Action
        var selectedAction: Int
            private set

        private var icon: ImageView? = null
        private var summary: TextView? = null

        init {
            selectedAction = prefs.getInt(context.getString(NotificationConstants.SLOT_PREF_KEYS[i]), NotificationConstants.SLOT_DEFAULTS[i])
            val view = parentView.findViewById<View>(SLOT_ITEMS[i])

            // only show the last two notification slots on Android 13+
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || i >= 3) {
                setupSelectedAction(view)
                setupTitle(view)
                setupCheckbox(view, isCompactSlotChecked)
            } else view.visibility = View.GONE
        }

        private fun setupTitle(view: View) {
            (view.findViewById<View>(R.id.notificationActionTitle) as TextView).setText(SLOT_TITLES[i])
            view.findViewById<View>(R.id.notificationActionClickableArea).setOnClickListener { openActionChooserDialog() }
        }

        private fun setupCheckbox(view: View, isCompactSlotChecked: Boolean) {
            val compactSlotCheckBox = view.findViewById<CheckBox>(R.id.notificationActionCheckBox)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // there are no compact slots to customize on Android 13+
                compactSlotCheckBox.visibility = View.GONE
                view.findViewById<View>(R.id.notificationActionCheckBoxClickableArea).visibility = View.GONE
                return
            }

            compactSlotCheckBox.isChecked = isCompactSlotChecked
            view.findViewById<View>(R.id.notificationActionCheckBoxClickableArea).setOnClickListener { onToggleCompactSlot.accept(i, compactSlotCheckBox) }
        }

        private fun setupSelectedAction(view: View) {
            icon = view.findViewById(R.id.notificationActionIcon)
            summary = view.findViewById(R.id.notificationActionSummary)
            updateInfo()
        }

        private fun updateInfo() {
            if (NotificationConstants.ACTION_ICONS[selectedAction] == 0) icon!!.setImageDrawable(null)
            else icon!!.setImageDrawable(AppCompatResources.getDrawable(context, NotificationConstants.ACTION_ICONS[selectedAction]))
            summary!!.text = NotificationConstants.getActionName(context, selectedAction)
        }

        private fun openActionChooserDialog() {
            val inflater = LayoutInflater.from(context)
            val binding = SingleChoiceDialogViewBinding.inflate(inflater)

            val alertDialog = AlertDialog.Builder(context)
                .setTitle(SLOT_TITLES[i])
                .setView(binding.root)
                .setCancelable(true)
                .create()

            val radioButtonsClickListener = View.OnClickListener { v: View ->
                selectedAction = NotificationConstants.ALL_ACTIONS[v.id]
                updateInfo()
                alertDialog.dismiss()
            }

            for (id in NotificationConstants.ALL_ACTIONS.indices) {
                val action = NotificationConstants.ALL_ACTIONS[id]
                val radioButton = ListRadioIconItemBinding.inflate(inflater).root

                // if present set action icon with correct color
                val iconId = NotificationConstants.ACTION_ICONS[action]
                if (iconId != 0) {
                    radioButton.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, iconId, 0)
                    val color = ColorStateList.valueOf(resolveColorFromAttr(context, android.R.attr.textColorPrimary))
                    TextViewCompat.setCompoundDrawableTintList(radioButton, color)
                }

                radioButton.text = NotificationConstants.getActionName(context, action)
                radioButton.isChecked = action == selectedAction
                radioButton.id = id
                radioButton.layoutParams = RadioGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                radioButton.setOnClickListener(radioButtonsClickListener)
                binding.list.addView(radioButton)
            }
            alertDialog.show()
            if (isTv(context)) setupFocusObserver(alertDialog)
        }

        companion object {
            private val SLOT_ITEMS = intArrayOf(
                R.id.notificationAction0,
                R.id.notificationAction1,
                R.id.notificationAction2,
                R.id.notificationAction3,
                R.id.notificationAction4,
            )

            private val SLOT_TITLES = intArrayOf(
                R.string.notification_action_0_title,
                R.string.notification_action_1_title,
                R.string.notification_action_2_title,
                R.string.notification_action_3_title,
                R.string.notification_action_4_title,
            )
        }
    }

}
