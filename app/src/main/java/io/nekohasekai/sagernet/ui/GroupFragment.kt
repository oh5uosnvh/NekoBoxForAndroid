package io.nekohasekai.sagernet.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sagernet.widget.FixedLinearLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.databinding.LayoutGroupItemBinding
import io.nekohasekai.sagernet.fmt.toUniversalLink
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.widget.ListListener
import io.nekohasekai.sagernet.widget.QRCodeDialog
import io.nekohasekai.sagernet.widget.UndoSnackbarManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.matsuri.nb4a.utils.Util
import moe.matsuri.nb4a.utils.toBytesString
import java.util.Collections

class GroupFragment : ToolbarFragment(R.layout.layout_group),
    Toolbar.OnMenuItemClickListener {

    lateinit var activity: MainActivity
    lateinit var groupListView: RecyclerView
    lateinit var layoutManager: LinearLayoutManager
    lateinit var groupAdapter: GroupAdapter
    lateinit var undoManager: UndoSnackbarManager<ProxyGroup>
    private lateinit var itemTouchHelper: ItemTouchHelper

    // ------------------------------------------------------ 顶栏过滤 / 排序
    private lateinit var filterDefaultCell: TextView
    private lateinit var filterSortCell: TextView
    private var filterSubscription: Boolean? = null
    private var sortMode: Int = SORT_DEFAULT

    companion object {
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

        setupFilterBar()

        groupListView = view.findViewById(R.id.group_list)
        layoutManager = FixedLinearLayoutManager(groupListView)
        groupListView.layoutManager = layoutManager
        groupAdapter = GroupAdapter()
        GroupManager.addListener(groupAdapter)
        groupListView.adapter = groupAdapter

        undoManager = UndoSnackbarManager(activity, groupAdapter)

        // 官方原生 ItemTouchHelper 滑动删除 + 拖拽排序（通过 ☷ 按钮触发拖拽）
        itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.START
        ) {
            override fun isLongPressDragEnabled(): Boolean = false

            override fun getSwipeDirs(
                recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder
            ): Int {
                val proxyGroup = (viewHolder as? GroupHolder)?.proxyGroup ?: return 0
                if (proxyGroup.ungrouped || proxyGroup.id in GroupUpdater.updating) {
                    return 0
                }
                return super.getSwipeDirs(recyclerView, viewHolder)
            }

            override fun getDragDirs(
                recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder
            ): Int {
                val proxyGroup = (viewHolder as? GroupHolder)?.proxyGroup ?: return 0
                if (proxyGroup.ungrouped || proxyGroup.id in GroupUpdater.updating) {
                    return 0
                }
                return super.getDragDirs(recyclerView, viewHolder)
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val index = viewHolder.bindingAdapterPosition
                if (index != RecyclerView.NO_POSITION) {
                    val group = (viewHolder as GroupHolder).proxyGroup
                    groupAdapter.remove(index)
                    undoManager.remove(index to group)
                }
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

    private fun setupFilterBar() {
        val foreground = resolveToolbarForeground()

        val segment = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = topbarSegmentBackground()
            layoutParams = Toolbar.LayoutParams(
                Toolbar.LayoutParams.WRAP_CONTENT,
                resources.getDimensionPixelSize(R.dimen.topbar_segment_height),
            ).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                marginStart = dp2px(12)
            }
        }

        filterDefaultCell = TextView(context).apply {
            applySegmentCellStyle(this, foreground)
            setOnClickListener { showFilterPicker(it) }
        }

        val divider = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp2px(1), ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(resources.getColor(R.color.segment_stroke))
        }

        filterSortCell = TextView(context).apply {
            applySegmentCellStyle(this, foreground)
            setOnClickListener { showSortPicker(it) }
        }

        segment.addView(filterDefaultCell)
        segment.addView(divider)
        segment.addView(filterSortCell)

        toolbar.addView(segment)
        updateFilterBar()
    }

    private fun resolveToolbarForeground(): Int {
        val ta = requireContext().obtainStyledAttributes(
            intArrayOf(android.R.attr.textColorPrimary, R.attr.colorOnPrimary)
        )
        val color = ta.getColor(1, ta.getColor(0, Color.WHITE))
        ta.recycle()
        return color
    }

    private fun topbarSegmentBackground(): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(resources.getColor(R.color.segment_fill))
        setStroke(1, resources.getColor(R.color.segment_stroke))
        cornerRadius = dp2px(8).toFloat()
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

    private fun rippleDrawable(): Drawable {
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp2px(8).toFloat()
            setColor(Color.WHITE)
        }
        return RippleDrawable(
            android.content.res.ColorStateList.valueOf(Color.parseColor("#33FFFFFF")),
            null,
            mask,
        )
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

    private var activePicker: PopupWindow? = null

    private fun pickerContent(): LinearLayout = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp2px(6), dp2px(6), dp2px(6), dp2px(6))
    }

    private fun addPickerRow(parent: LinearLayout, row: View) {
        parent.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp2px(40),
        ).apply {
            bottomMargin = dp2px(4)
        })
    }

    private fun filterRow(title: String, selected: Boolean): View {
        val ctx = requireContext()
        return FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp2px(8).toFloat()
                setColor(if (selected) themeColor(R.attr.colorPrimary) else Color.TRANSPARENT)
            }
            isClickable = true
            isFocusable = true
            val tv = TextView(ctx).apply {
                text = title
                textSize = 14f
                setTextColor(if (selected) Color.WHITE else themeColor(android.R.attr.textColorPrimary))
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp2px(12), 0, dp2px(12), 0)
            }
            addView(tv, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.START or Gravity.CENTER_VERTICAL,
            ))
        }
    }

    private fun showPickerWindow(content: View, anchor: View) {
        activePicker?.dismiss()
        val scroll = ScrollView(requireContext()).apply {
            addView(content)
        }
        val popupBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp2px(12).toFloat()
            setColor(themeColor(android.R.attr.colorBackground))
            setStroke(1, resources.getColor(R.color.segment_stroke))
        }
        val pw = PopupWindow(
            scroll,
            dp2px(200),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true,
        ).apply {
            setBackgroundDrawable(popupBg)
            elevation = dp2px(8).toFloat()
            isOutsideTouchable = true
        }
        activePicker = pw
        pw.showAsDropDown(anchor, 0, dp2px(4))
    }

    private fun themeColor(attr: Int): Int {
        val ta = requireContext().obtainStyledAttributes(intArrayOf(attr))
        val color = ta.getColor(0, Color.GRAY)
        ta.recycle()
        return color
    }

    private fun setFilter(sub: Boolean?) {
        filterSubscription = sub
        updateFilterBar()
        activePicker?.dismiss()
        groupAdapter.applyFilterAndSort()
    }

    private fun setSort(mode: Int) {
        sortMode = mode
        updateFilterBar()
        activePicker?.dismiss()
        groupAdapter.applyFilterAndSort()
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
                            .forEach { GroupUpdater.startUpdate(it, true) }
                    }
                    .setNegativeButton(R.string.no, null)
                    .show()
            }
            else -> return false
        }
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        GroupManager.removeListener(groupAdapter)
    }

    inner class GroupHolder(val binding: LayoutGroupItemBinding) :
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

        fun bind(group: ProxyGroup) {
            proxyGroup = group

            editButton.isGone = proxyGroup.ungrouped
            sortButton.isGone = proxyGroup.ungrouped
            updateButton.isInvisible = proxyGroup.type != GroupType.SUBSCRIPTION
            groupName.text = proxyGroup.displayName()

            editButton.setOnClickListener {
                startActivity(Intent(it.context, GroupSettingsActivity::class.java).apply {
                    putExtra(GroupSettingsActivity.EXTRA_GROUP_ID, group.id)
                })
            }

            // ☷ 排序按钮按住直接进入拖拽排序
            sortButton.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN &&
                    this@GroupHolder.bindingAdapterPosition != RecyclerView.NO_POSITION
                ) {
                    itemTouchHelper.startDrag(this@GroupHolder)
                }
                false
            }

            updateButton.setOnClickListener {
                GroupUpdater.startUpdate(proxyGroup, true)
            }

            optionsButton.setOnClickListener {
                selectedGroup = proxyGroup
                val popup = PopupMenu(requireContext(), it)
                popup.menuInflater.inflate(R.menu.group_action_menu, popup.menu)
                popup.setOnMenuItemClickListener(this)
                popup.show()
            }

            if (proxyGroup.type == GroupType.SUBSCRIPTION) {
                subscriptionUpdateProgress.isGone = proxyGroup.id !in GroupUpdater.updating
                groupTraffic.isGone = false
                groupStatus.text = getString(
                    R.string.group_status,
                    proxyGroup.order,
                    proxyGroup.profileCount,
                    proxyGroup.frontProxyCount
                )
                if (proxyGroup.subscription?.upload != 0L || proxyGroup.subscription?.download != 0L) {
                    groupTraffic.text = getString(
                        R.string.traffic,
                        toBytesString(proxyGroup.subscription?.upload ?: 0L),
                        toBytesString(proxyGroup.subscription?.download ?: 0L),
                        if (proxyGroup.subscription?.total == 0L) getString(R.string.traffic_infinity)
                        else toBytesString(proxyGroup.subscription?.total ?: 0L)
                    )
                } else {
                    groupTraffic.text = getString(
                        R.string.group_status_traffic_empty,
                        proxyGroup.subscription?.status() ?: ""
                    )
                }
                if (proxyGroup.subscription?.user.isNullOrEmpty()) {
                    groupUser.isGone = true
                } else {
                    groupUser.isGone = false
                    groupUser.text = proxyGroup.subscription?.user
                }
            } else {
                subscriptionUpdateProgress.isGone = true
                groupTraffic.isGone = true
                groupUser.isGone = true
                groupStatus.text = getString(
                    R.string.group_status_basic,
                    proxyGroup.order,
                    proxyGroup.profileCount,
                    proxyGroup.frontProxyCount
                )
            }
        }

        override fun onMenuItemClick(item: MenuItem): Boolean {
            when (item.itemId) {
                R.id.action_export -> {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val universalLink = selectedGroup.toUniversalLink()
                        withContext(Dispatchers.Main) {
                            startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).setType("text/plain")
                                        .putExtra(Intent.EXTRA_TEXT, universalLink),
                                    getString(R.string.export)
                                )
                            )
                        }
                    }
                }
                R.id.action_export_clipboard -> {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val universalLink = selectedGroup.toUniversalLink()
                        withContext(Dispatchers.Main) {
                            Util.setClipboard(universalLink)
                            SagerNet.showToast(R.string.action_export_clipboard)
                        }
                    }
                }
                R.id.action_qr_code -> {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val universalLink = selectedGroup.toUniversalLink()
                        withContext(Dispatchers.Main) {
                            QRCodeDialog(universalLink).show(
                                parentFragmentManager, "qr_code"
                            )
                        }
                    }
                }
                R.id.action_clear -> {
                    MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                        .setMessage(R.string.clear_profiles_message)
                        .setPositiveButton(R.string.yes) { _, _ ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                SagerDatabase.proxyDao.deleteByGroup(selectedGroup.id)
                            }
                        }
                        .setNegativeButton(R.string.no, null)
                        .show()
                }
                R.id.action_delete -> {
                    MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                        .setMessage(R.string.delete_group_message)
                        .setPositiveButton(R.string.yes) { _, _ ->
                            val index = bindingAdapterPosition
                            if (index != RecyclerView.NO_POSITION) {
                                groupAdapter.remove(index)
                                undoManager.remove(index to proxyGroup)
                            }
                        }
                        .setNegativeButton(R.string.no, null)
                        .show()
                }
                else -> return false
            }
            return true
        }
    }

    var selectedGroup = ProxyGroup()

    inner class GroupAdapter : RecyclerView.Adapter<GroupHolder>(),
        GroupManager.GroupListener, UndoSnackbarManager.UndoAdapter<ProxyGroup> {

        var groupList: MutableList<ProxyGroup> = ArrayList()
        private var rawGroupList: MutableList<ProxyGroup> = ArrayList()

        init {
            reloadGroups()
        }

        fun reloadGroups() {
            lifecycleScope.launch(Dispatchers.IO) {
                val groups = SagerDatabase.groupDao.allGroups().toMutableList()
                withContext(Dispatchers.Main) {
                    rawGroupList = groups
                    applyFilterAndSort()
                }
            }
        }

        fun applyFilterAndSort() {
            var list = rawGroupList.toList()
            filterSubscription?.let { isSub ->
                list = if (isSub) {
                    list.filter { it.type == GroupType.SUBSCRIPTION }
                } else {
                    list.filter { it.type != GroupType.SUBSCRIPTION && !it.ungrouped }
                }
            }
            list = when (sortMode) {
                SORT_ASC -> list.sortedWith { a, b ->
                    if (a.ungrouped) -1 else if (b.ungrouped) 1
                    else a.displayName().compareTo(b.displayName(), ignoreCase = true)
                }
                SORT_DESC -> list.sortedWith { a, b ->
                    if (a.ungrouped) -1 else if (b.ungrouped) 1
                    else b.displayName().compareTo(a.displayName(), ignoreCase = true)
                }
                else -> list
            }
            groupList = list.toMutableList()
            notifyDataSetChanged()
        }

        fun move(from: Int, to: Int) {
            if (from < to) {
                for (i in from until to) {
                    Collections.swap(groupList, i, i + 1)
                }
            } else {
                for (i in from downTo to + 1) {
                    Collections.swap(groupList, i, i - 1)
                }
            }
            notifyItemMoved(from, to)
        }

        fun commitMove() {
            lifecycleScope.launch(Dispatchers.IO) {
                groupList.forEachIndexed { index, group ->
                    group.order = index
                    SagerDatabase.groupDao.updateGroup(group)
                }
            }
        }

        fun remove(index: Int) {
            val group = groupList.removeAt(index)
            rawGroupList.remove(group)
            notifyItemRemoved(index)
        }

        override fun undo(data: List<Pair<Int, ProxyGroup>>) {
            lifecycleScope.launch(Dispatchers.IO) {
                data.forEach { (_, group) ->
                    SagerDatabase.groupDao.createGroup(group)
                }
                reloadGroups()
            }
        }

        override fun commit(data: List<Pair<Int, ProxyGroup>>) {
            lifecycleScope.launch(Dispatchers.IO) {
                data.forEach { (_, group) ->
                    GroupManager.deleteGroup(group.id)
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupHolder {
            return GroupHolder(
                LayoutGroupItemBinding.inflate(layoutInflater, parent, false)
            )
        }

        override fun onBindViewHolder(holder: GroupHolder, position: Int) {
            holder.bind(groupList[position])
        }

        override fun getItemCount(): Int = groupList.size

        override fun onGroupCreated(group: ProxyGroup) {
            reloadGroups()
        }

        override fun onGroupUpdated(group: ProxyGroup) {
            val index = groupList.indexOfFirst { it.id == group.id }
            if (index != -1) {
                groupList[index] = group
                notifyItemChanged(index)
            }
        }

        override fun onGroupRemoved(groupId: Long) {
            val index = groupList.indexOfFirst { it.id == groupId }
            if (index != -1) {
                groupList.removeAt(index)
                notifyItemRemoved(index)
            }
        }
    }
}
