package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import android.view.MenuItem
import android.view.View
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.widget.TextView
import android.widget.PopupWindow
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.Toolbar
import androidx.core.view.*
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.databinding.LayoutGroupItemBinding
import io.nekohasekai.sagernet.fmt.toUniversalLink
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.widget.ListListener
import io.nekohasekai.sagernet.widget.QRCodeDialog
import io.nekohasekai.sagernet.widget.UndoSnackbarManager
import kotlinx.coroutines.delay
import moe.matsuri.nb4a.utils.Util
import moe.matsuri.nb4a.utils.toBytesString
import java.lang.NumberFormatException
import java.util.*

class GroupFragment : ToolbarFragment(R.layout.layout_group),
    Toolbar.OnMenuItemClickListener {

    lateinit var activity: MainActivity
    lateinit var groupListView: RecyclerView
    lateinit var layoutManager: LinearLayoutManager
    lateinit var groupAdapter: GroupAdapter
    lateinit var undoManager: UndoSnackbarManager<ProxyGroup>

    private lateinit var itemTouchHelper: ItemTouchHelper
    private lateinit var filterDefaultCell: TextView
    private lateinit var filterSortCell: TextView
    private var filterPopup: PopupWindow? = null
    private var filterSubscription: Boolean? = null
    private var sortMode = SORT_DEFAULT

    companion object {
        private const val TOPBAR_SEGMENT_LIGHTEN = 0.30f

        private const val SORT_DEFAULT = 0
        private const val SORT_ASC = 1
        private const val SORT_DESC = 2
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity = requireActivity() as MainActivity

        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        toolbar.setTitle(R.string.menu_group)
        toolbar.inflateMenu(R.menu.add_group_menu)
        toolbar.setOnMenuItemClickListener(this)

        groupListView = view.findViewById(R.id.group_list)
        layoutManager = FixedLinearLayoutManager(groupListView)
        groupListView.layoutManager = layoutManager
        groupAdapter = GroupAdapter()
        GroupManager.addListener(groupAdapter)
        groupListView.adapter = groupAdapter

        undoManager = UndoSnackbarManager(activity, groupAdapter)
        setupFilterBar()

        itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.START
        ) {
            override fun getSwipeDirs(
                recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder
            ): Int {
                val proxyGroup = (viewHolder as GroupHolder).proxyGroup
                if (proxyGroup.ungrouped || proxyGroup.id in GroupUpdater.updating) {
                    return 0
                }
                return super.getSwipeDirs(recyclerView, viewHolder)
            }

            override fun getDragDirs(
                recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder
            ): Int {
                val proxyGroup = (viewHolder as GroupHolder).proxyGroup
                if (proxyGroup.ungrouped || proxyGroup.id in GroupUpdater.updating) {
                    return 0
                }
                return super.getDragDirs(recyclerView, viewHolder)
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val index = viewHolder.bindingAdapterPosition
                groupAdapter.remove(index)
                undoManager.remove(index to (viewHolder as GroupHolder).proxyGroup)
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder,
            ): Boolean {
                groupAdapter.move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }

            override fun clearView(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ) {
                super.clearView(recyclerView, viewHolder)
                groupAdapter.commitMove()
            }
        })
        itemTouchHelper.attachToRecyclerView(groupListView)

    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_new_group -> {
                startActivity(Intent(context, GroupSettingsActivity::class.java))
            }

            R.id.action_update_all -> {
                MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                    .setMessage(R.string.update_all_subscription)
                    .setPositiveButton(R.string.yes) { _, _ ->
                        SagerDatabase.groupDao.allGroups()
                            .filter { it.type == GroupType.SUBSCRIPTION }
                            .forEach {
                                GroupUpdater.startUpdate(it, true)
                            }
                    }
                    .setNegativeButton(R.string.no, null)
                    .show()
            }
        }
        return true
    }

    private lateinit var selectedGroup: ProxyGroup

    private val exportProfiles =
        registerForActivityResult(ActivityResultContracts.CreateDocument()) { data ->
            if (data != null) {
                runOnDefaultDispatcher {
                    val profiles = SagerDatabase.proxyDao.getByGroup(selectedGroup.id)
                    val links = profiles.joinToString("\n") { it.toStdLink(compact = true) }
                    try {
                        (requireActivity() as MainActivity).contentResolver.openOutputStream(
                            data
                        )!!.bufferedWriter().use {
                            it.write(links)
                        }
                        onMainDispatcher {
                            snackbar(getString(R.string.action_export_msg)).show()
                        }
                    } catch (e: Exception) {
                        Logs.w(e)
                        onMainDispatcher {
                            snackbar(e.readableMessage).show()
                        }
                    }

                }
            }
        }


    // ------------------------------------------------------ 顶栏：过滤/排序

    private fun topbarSegmentBackground(): Drawable {
        val ctx = requireContext()
        val primary = ctx.getColorAttr(com.google.android.material.R.attr.colorPrimary)
        val fill = ColorUtils.blendARGB(primary, Color.WHITE, TOPBAR_SEGMENT_LIGHTEN)

        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = resources.getDimension(R.dimen.group_delete_corner)
            setColor(fill)
            setStroke(
                resources.getDimensionPixelSize(R.dimen.card_stroke_width),
                ContextCompat.getColor(ctx, R.color.segment_stroke),
            )
        }
    }

    private fun setupFilterBar() {
        val ctx = requireContext()
        val segmentHeight = resources.getDimensionPixelSize(R.dimen.topbar_segment_height)
        val barForeground = toolbarForeground()

        fun cell(onClick: (View) -> Unit) = TextView(ctx).apply {
            applySegmentCellStyle(this, barForeground)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, segmentHeight,
            )
            setOnClickListener { onClick(it) }
        }

        val defaultCell = cell { showFilterPicker(it) }
        val sortCell = cell { showSortPicker(it) }

        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = topbarSegmentBackground()

            val divider = View(ctx).apply {
                setBackgroundColor(ContextCompat.getColor(ctx, R.color.segment_stroke))
            }

            addView(defaultCell)
            addView(
                divider,
                LinearLayout.LayoutParams(dp2px(1), ViewGroup.LayoutParams.MATCH_PARENT),
            )
            addView(sortCell)
        }

        val lp = Toolbar.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, segmentHeight,
        ).apply {
            gravity = Gravity.CENTER_VERTICAL
            marginEnd = dp2px(8)
        }
        toolbar.addView(bar, lp)

        filterDefaultCell = defaultCell
        filterSortCell = sortCell
        updateFilterBar()
    }

    private fun toolbarForeground(): Int {
        val tv = android.util.TypedValue()
        val ctx = toolbar.context
        return if (ctx.theme.resolveAttribute(android.R.attr.textColorPrimary, tv, true)) {
            if (tv.resourceId != 0) ContextCompat.getColor(ctx, tv.resourceId) else tv.data
        } else {
            themeTextColor()
        }
    }

    private fun applySegmentCellStyle(tv: TextView, foreground: Int) {
        tv.apply {
            gravity = Gravity.CENTER
            minWidth = resources.getDimensionPixelSize(R.dimen.topbar_segment_cell_min_width)
            val hPad = resources.getDimensionPixelSize(R.dimen.topbar_segment_cell_padding_h)
            setPadding(hPad, 0, hPad, 0)
            setTextColor(foreground)
            textSize = 13f
            includeFontPadding = false
            isSingleLine = true
            isClickable = true
            isFocusable = true
            background = rippleDrawable()
        }
    }

    private fun rippleDrawable(): android.graphics.drawable.Drawable? {
        val tv = android.util.TypedValue()
        return if (requireContext().theme.resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless, tv, true,
            )
        ) {
            ContextCompat.getDrawable(requireContext(), tv.resourceId)
        } else null
    }

    private fun themeTextColor(): Int {
        val ta = requireContext().obtainStyledAttributes(
            intArrayOf(android.R.attr.textColorPrimary)
        )
        val color = ta.getColor(0, Color.WHITE)
        ta.recycle()
        return color
    }

    private fun updateFilterBar() {
        if (!::filterDefaultCell.isInitialized) return
        val ctx = requireContext()
        filterDefaultCell.text = when (filterSubscription) {
            null -> ctx.getString(R.string.filter_default)
            true -> ctx.getString(R.string.subscription)
            false -> ctx.getString(R.string.filter_local)
        }
        filterSortCell.text = when (sortMode) {
            SORT_ASC -> ctx.getString(R.string.sort_ascending)
            SORT_DESC -> ctx.getString(R.string.sort_descending)
            else -> ctx.getString(R.string.group_order)
        }
        filterSortCell.alpha = 1f
        filterDefaultCell.alpha = 1f
    }

    private fun showFilterPicker(anchor: View) {
        val ctx = requireContext()
        val content = pickerContent()
        addPickerRow(content, filterRow(ctx.getString(R.string.filter_default), filterSubscription == null).apply {
            setOnClickListener { setFilter(null) }
        })
        addPickerRow(content, filterRow(ctx.getString(R.string.subscription), filterSubscription == true).apply {
            setOnClickListener { setFilter(true) }
        })
        addPickerRow(content, filterRow(ctx.getString(R.string.filter_local), filterSubscription == false).apply {
            setOnClickListener { setFilter(false) }
        })
        showPickerWindow(content, anchor)
    }

    private fun showSortPicker(anchor: View) {
        val ctx = requireContext()
        val content = pickerContent()
        addPickerRow(content, filterRow(ctx.getString(R.string.filter_default), sortMode == SORT_DEFAULT).apply {
            setOnClickListener { setSort(SORT_DEFAULT) }
        })
        addPickerRow(content, filterRow(ctx.getString(R.string.sort_ascending) + " (" + ctx.getString(R.string.group_order_by_name) + ")", sortMode == SORT_ASC).apply {
            setOnClickListener { setSort(SORT_ASC) }
        })
        addPickerRow(content, filterRow(ctx.getString(R.string.sort_descending) + " (" + ctx.getString(R.string.group_order_by_name) + ")", sortMode == SORT_DESC).apply {
            setOnClickListener { setSort(SORT_DESC) }
        })
        showPickerWindow(content, anchor)
    }

    private fun pickerContent(): LinearLayout = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp2px(4), 0, dp2px(4))
    }

    private fun addPickerRow(content: LinearLayout, row: View) {
        content.addView(
            row,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    private fun showPickerWindow(content: LinearLayout, anchor: View) {
        dismissFilterPicker()
        val ctx = requireContext()

        val card = com.google.android.material.card.MaterialCardView(ctx).apply {
            radius = dp2px(12).toFloat()
            cardElevation = dp2px(8).toFloat()
            strokeWidth = 0
            val ta = ctx.obtainStyledAttributes(intArrayOf(R.attr.colorSurface))
            val surface = ta.getColor(0, Color.DKGRAY)
            ta.recycle()
            setCardBackgroundColor(surface)
            addView(content)
        }

        filterPopup = PopupWindow(
            card,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true,
        ).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dp2px(8).toFloat()
            isOutsideTouchable = true
            showAsDropDown(anchor, 0, dp2px(4))
        }
    }

    private fun dismissFilterPicker() {
        filterPopup?.dismiss()
        filterPopup = null
    }

    private fun filterRow(label: String, selected: Boolean): TextView = TextView(requireContext()).apply {
        text = label
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(
            if (selected) {
                val ta = context.obtainStyledAttributes(intArrayOf(R.attr.colorPrimary))
                val c = ta.getColor(0, themeTextColor())
                ta.recycle()
                c
            } else {
                themeTextColor()
            }
        )
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp2px(16), dp2px(10), dp2px(20), dp2px(10))
        isClickable = true
        isFocusable = true
        background = rippleDrawable()
    }

    private fun setFilter(subscriptionOnly: Boolean?) {
        filterSubscription = subscriptionOnly
        dismissFilterPicker()
        updateFilterBar()
        groupAdapter.applyFilter()
    }

    private fun setSort(mode: Int) {
        sortMode = mode
        dismissFilterPicker()
        updateFilterBar()
        groupAdapter.applyFilter()
    }

    inner class GroupAdapter : RecyclerView.Adapter<GroupHolder>(),
        GroupManager.Listener,
        UndoSnackbarManager.Interface<ProxyGroup> {

        private val fullList = ArrayList<ProxyGroup>()
        val groupList = ArrayList<ProxyGroup>()

        suspend fun reload() {
            val groups = SagerDatabase.groupDao.allGroups().toMutableList()
            if (groups.size > 1 && SagerDatabase.proxyDao.countByGroup(groups.find { it.ungrouped }!!.id) == 0L) groups.removeAll { it.ungrouped }
            fullList.clear()
            fullList.addAll(groups)
            rebuildVisible()
            groupListView.post {
                notifyDataSetChanged()
            }
        }

        private fun rebuildVisible() {
            var list = fullList.toList()
            val filter = filterSubscription
            if (filter != null) {
                list = list.filter { (it.type == GroupType.SUBSCRIPTION) == filter }
            }
            list = when (sortMode) {
                SORT_ASC -> list.sortedBy { it.displayName().lowercase() }
                SORT_DESC -> list.sortedByDescending { it.displayName().lowercase() }
                else -> list
            }
            groupList.clear()
            groupList.addAll(list)
        }

        fun applyFilter() {
            rebuildVisible()
            notifyDataSetChanged()
        }

        init {
            setHasStableIds(true)

            runOnDefaultDispatcher {
                reload()
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupHolder {
            return GroupHolder(LayoutGroupItemBinding.inflate(layoutInflater, parent, false))
        }

        override fun onBindViewHolder(holder: GroupHolder, position: Int) {
            holder.bind(groupList[position])
        }

        override fun getItemCount(): Int {
            return groupList.size
        }

        override fun getItemId(position: Int): Long {
            return groupList[position].id
        }

        private val updated = HashSet<ProxyGroup>()

        fun move(from: Int, to: Int) {
            val first = groupList[from]
            var previousOrder = first.userOrder
            val (step, range) = if (from < to) Pair(1, from until to) else Pair(
                -1, to + 1 downTo from
            )
            for (i in range) {
                val next = groupList[i + step]
                val order = next.userOrder
                next.userOrder = previousOrder
                previousOrder = order
                groupList[i] = next
                updated.add(next)
            }
            first.userOrder = previousOrder
            groupList[to] = first
            updated.add(first)
            notifyItemMoved(from, to)
        }

        fun commitMove() = runOnDefaultDispatcher {
            updated.forEach { SagerDatabase.groupDao.updateGroup(it) }
            updated.clear()
        }

        fun remove(index: Int) {
            val item = groupList.removeAt(index)
            fullList.removeAll { it.id == item.id }
            notifyItemRemoved(index)
        }

        override fun undo(actions: List<Pair<Int, ProxyGroup>>) {
            for ((index, item) in actions) {
                groupList.add(index, item)
                notifyItemInserted(index)
            }
        }

        override fun commit(actions: List<Pair<Int, ProxyGroup>>) {
            val groups = actions.map { it.second }
            runOnDefaultDispatcher {
                GroupManager.deleteGroup(groups)
                reload()
            }
        }

        override suspend fun groupAdd(group: ProxyGroup) {
            groupList.add(group)
            delay(300L)

            onMainDispatcher {
                undoManager.flush()
                notifyItemInserted(groupList.size - 1)

                if (group.type == GroupType.SUBSCRIPTION) {
                    GroupUpdater.startUpdate(group, true)
                }
            }
        }

        override suspend fun groupRemoved(groupId: Long) {
            val index = groupList.indexOfFirst { it.id == groupId }
            if (index == -1) return
            onMainDispatcher {
                undoManager.flush()
                if (SagerDatabase.groupDao.allGroups().size <= 2) {
                    runOnDefaultDispatcher {
                        reload()
                    }
                } else {
                    groupList.removeAt(index)
                    notifyItemRemoved(index)
                }
            }
        }

        override suspend fun groupUpdated(group: ProxyGroup) {
            val index = groupList.indexOfFirst { it.id == group.id }
            if (index == -1) {
                reload()
                return
            }
            groupList[index] = group
            onMainDispatcher {
                undoManager.flush()

                notifyItemChanged(index)
            }
        }

        override suspend fun groupUpdated(groupId: Long) {
            val index = groupList.indexOfFirst { it.id == groupId }
            if (index == -1) {
                reload()
                return
            }
            onMainDispatcher {
                notifyItemChanged(index)
            }
        }

    }

    override fun onDestroyView() {
        dismissFilterPicker()
        super.onDestroyView()
    }

    override fun onDestroy() {
        if (::groupAdapter.isInitialized) {
            GroupManager.removeListener(groupAdapter)
        }

        super.onDestroy()

        if (!::undoManager.isInitialized) return
        undoManager.flush()
    }

    inner class GroupHolder(binding: LayoutGroupItemBinding) :
        RecyclerView.ViewHolder(binding.root),
        PopupMenu.OnMenuItemClickListener {

        lateinit var proxyGroup: ProxyGroup
        val groupName = binding.groupName
        val groupStatus = binding.groupStatus
        val groupTraffic = binding.groupTraffic
        val groupUser = binding.groupUser
        val editButton = binding.edit
        val optionsButton = binding.options
        val sortButton = binding.groupSort
        val updateButton = binding.groupUpdate
        val subscriptionUpdateProgress = binding.subscriptionUpdateProgress

        override fun onMenuItemClick(item: MenuItem): Boolean {

            fun export(link: String) {
                val success = SagerNet.trySetPrimaryClip(link)
                activity.snackbar(if (success) R.string.action_export_msg else R.string.action_export_err)
                    .show()
            }

            when (item.itemId) {
                R.id.action_universal_qr -> {
                    QRCodeDialog(
                        proxyGroup.toUniversalLink(), proxyGroup.displayName()
                    ).showAllowingStateLoss(parentFragmentManager)
                }

                R.id.action_universal_clipboard -> {
                    export(proxyGroup.toUniversalLink())
                }

                R.id.action_export_clipboard -> {
                    runOnDefaultDispatcher {
                        val profiles = SagerDatabase.proxyDao.getByGroup(selectedGroup.id)
                        val links = profiles.joinToString("\n") { it.toStdLink(compact = true) }
                        onMainDispatcher {
                            SagerNet.trySetPrimaryClip(links)
                            snackbar(getString(R.string.copy_toast_msg)).show()
                        }
                    }
                }

                R.id.action_export_file -> {
                    startFilesForResult(exportProfiles, "profiles_${proxyGroup.displayName()}.txt")
                }

                R.id.action_clear -> {
                    MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                        .setMessage(R.string.clear_profiles_message)
                        .setPositiveButton(R.string.yes) { _, _ ->
                            runOnDefaultDispatcher {
                                GroupManager.clearGroup(proxyGroup.id)
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }

            return true
        }


        fun bind(group: ProxyGroup) {
            proxyGroup = group

            itemView.setOnClickListener { }

            sortButton.isGone = proxyGroup.ungrouped
            sortButton.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN &&
                    this@GroupHolder.bindingAdapterPosition != RecyclerView.NO_POSITION
                ) {
                    itemTouchHelper.startDrag(this@GroupHolder)
                }
                false
            }

            editButton.isGone = proxyGroup.ungrouped
            updateButton.isInvisible = proxyGroup.type != GroupType.SUBSCRIPTION
            groupName.text = proxyGroup.displayName()

            editButton.setOnClickListener {
                startActivity(Intent(it.context, GroupSettingsActivity::class.java).apply {
                    putExtra(GroupSettingsActivity.EXTRA_GROUP_ID, group.id)
                })
            }

            updateButton.setOnClickListener {
                GroupUpdater.startUpdate(proxyGroup, true)
            }

            optionsButton.setOnClickListener {
                selectedGroup = proxyGroup

                val popup = PopupMenu(requireContext(), it)
                popup.menuInflater.inflate(R.menu.group_action_menu, popup.menu)

                if (proxyGroup.type != GroupType.SUBSCRIPTION) {
                    popup.menu.removeItem(R.id.action_share_subscription)
                }
                popup.setOnMenuItemClickListener(this)
                popup.show()
            }

            if (proxyGroup.id in GroupUpdater.updating) {
                (groupName.parent as LinearLayout).apply {
                    setPadding(paddingLeft, dp2px(11), paddingRight, paddingBottom)
                }

                subscriptionUpdateProgress.isVisible = true

                if (!GroupUpdater.progress.containsKey(proxyGroup.id)) {
                    subscriptionUpdateProgress.isIndeterminate = true
                } else {
                    subscriptionUpdateProgress.isIndeterminate = false
                    GroupUpdater.progress[proxyGroup.id]?.let {
                        subscriptionUpdateProgress.max = it.max
                        subscriptionUpdateProgress.progress = it.progress
                    }
                }

                updateButton.isInvisible = true
                editButton.isGone = true
            } else {
                (groupName.parent as LinearLayout).apply {
                    setPadding(paddingLeft, dp2px(15), paddingRight, paddingBottom)
                }

                subscriptionUpdateProgress.isVisible = false
                updateButton.isInvisible = proxyGroup.type != GroupType.SUBSCRIPTION
                editButton.isGone = proxyGroup.ungrouped
            }

            val subscription = proxyGroup.subscription
            if (subscription != null && subscription.bytesUsed > 0L) { // SIP008 & Open Online Config
                groupTraffic.isVisible = true
                groupTraffic.text = if (subscription.bytesRemaining > 0L) {
                    app.getString(
                        R.string.subscription_traffic, Formatter.formatFileSize(
                            app, subscription.bytesUsed
                        ), Formatter.formatFileSize(
                            app, subscription.bytesRemaining
                        )
                    )
                } else {
                    app.getString(
                        R.string.subscription_used, Formatter.formatFileSize(
                            app, subscription.bytesUsed
                        )
                    )
                }
                groupStatus.setPadding(0)
            } else if (subscription != null && !subscription.subscriptionUserinfo.isNullOrBlank()) { // Raw
                var text = ""

                fun get(regex: String): String? {
                    return regex.toRegex().findAll(subscription.subscriptionUserinfo).mapNotNull {
                        if (it.groupValues.size > 1) it.groupValues[1] else null
                    }.firstOrNull()
                }

                try {
                    var used: Long = 0
                    get("upload=([0-9]+)")?.apply {
                        used += toLong()
                    }
                    get("download=([0-9]+)")?.apply {
                        used += toLong()
                    }
                    val total = get("total=([0-9]+)")?.toLong() ?: 0
                    val remain = total - used
                    if (used > 0 || total > 0) {
                        text += if (remain > 0) {
                            getString(
                                R.string.subscription_traffic,
                                used.toBytesString(),
                                remain.toBytesString()
                            )
                        } else {
                            getString(R.string.subscription_used, used.toBytesString())
                        }
                    }
                    get("expire=([0-9]+)")?.apply {
                        text += "\n"
                        text += getString(
                            R.string.subscription_expire,
                            Util.timeStamp2Text(this.toLong() * 1000)
                        )
                    }
                } catch (_: NumberFormatException) {
                    // ignore
                }

                if (text.isNotEmpty()) {
                    groupTraffic.isVisible = true
                    groupTraffic.text = text
                    groupStatus.setPadding(0)
                }
            } else {
                groupTraffic.isVisible = false
                groupStatus.setPadding(0, 0, 0, dp2px(4))
            }

            groupUser.text = subscription?.username ?: ""

            runOnDefaultDispatcher {
                val size = SagerDatabase.proxyDao.countByGroup(group.id)
                onMainDispatcher {
                    @Suppress("DEPRECATION") when (group.type) {
                        GroupType.BASIC -> {
                            if (size == 0L) {
                                groupStatus.setText(R.string.group_status_empty)
                            } else {
                                groupStatus.text = getString(R.string.group_status_proxies, size)
                            }
                        }

                        GroupType.SUBSCRIPTION -> {
                            groupStatus.text = if (size == 0L) {
                                getString(R.string.group_status_empty_subscription)
                            } else {
                                val date = Date(group.subscription!!.lastUpdated * 1000L)
                                getString(
                                    R.string.group_status_proxies_subscription,
                                    size,
                                    "${date.month + 1} - ${date.date}"
                                )
                            }

                        }
                    }
                }

            }

        }
    }

}