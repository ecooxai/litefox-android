package org.mozilla.reference.browser.tabs

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import mozilla.components.browser.state.state.TabSessionState
import org.mozilla.reference.browser.R

internal data class IconTab(val tab: TabSessionState, val selected: Boolean)

/** Recycled favicon cells; no external favicon requests and no private-tab mixing. */
internal class TabIconAdapter(
    private val onSelect: (TabSessionState) -> Unit,
    private val onClose: (TabSessionState) -> Unit,
) : ListAdapter<IconTab, TabIconAdapter.Holder>(DIFF) {
    fun submitTabs(tabs: List<TabSessionState>, selectedId: String?) {
        submitList(tabs.map { IconTab(it, it.id == selectedId) })
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val context = parent.context
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            setPadding(dp(2), dp(4), dp(2), dp(4))
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(76)).apply {
                setMargins(dp(2), dp(2), dp(2), dp(2))
            }
            val attributes = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
            foreground = attributes.getDrawable(0)
            attributes.recycle()
        }
        val frame = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val favicon = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        val fallback = TextView(context).apply {
            layoutParams = FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 20f
        }
        val label = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(20))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setTextColor(context.getColor(R.color.icons))
            textSize = 11f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        frame.addView(favicon)
        frame.addView(fallback)
        row.addView(frame)
        row.addView(label)
        return Holder(row, favicon, fallback, label)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = getItem(position)
        val context = holder.itemView.context
        val density = context.resources.displayMetrics.density
        val tab = item.tab
        val host = TabIconLabels.host(tab.content.url)
        val title = tab.content.title.ifBlank { host }
        val icon = tab.content.icon?.takeUnless { it.isRecycled }
        holder.favicon.setImageBitmap(icon)
        holder.favicon.visibility = if (icon == null) View.GONE else View.VISIBLE
        holder.fallback.visibility = if (icon == null) View.VISIBLE else View.GONE
        holder.fallback.text = TabIconLabels.initial(host)
        holder.fallback.background = GradientDrawable().apply {
            cornerRadius = 10 * density
            val hue = ((host.hashCode() and Int.MAX_VALUE) % 360).toFloat()
            setColor(Color.HSVToColor(floatArrayOf(hue, 0.62f, 0.62f)))
        }
        holder.label.text = host
        holder.itemView.apply {
            isSelected = item.selected
            contentDescription = "$title, $host"
            background = GradientDrawable().apply {
                cornerRadius = 10 * density
                setColor(if (item.selected) 0x335A39A8 else Color.TRANSPARENT)
                if (item.selected) setStroke((2 * density).toInt(), context.getColor(R.color.tab_icon_selected))
            }
            setOnClickListener { onSelect(tab) }
            setOnLongClickListener {
                PopupMenu(context, this).apply {
                    menu.add(R.string.tabs_icon_close).setOnMenuItemClickListener {
                        onClose(tab)
                        true
                    }
                    show()
                }
                true
            }
        }
    }

    override fun onViewRecycled(holder: Holder) {
        holder.favicon.setImageDrawable(null)
        holder.itemView.setOnClickListener(null)
        holder.itemView.setOnLongClickListener(null)
        super.onViewRecycled(holder)
    }

    internal class Holder(
        root: View,
        val favicon: ImageView,
        val fallback: TextView,
        val label: TextView,
    ) : RecyclerView.ViewHolder(root)

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<IconTab>() {
            override fun areItemsTheSame(old: IconTab, new: IconTab) = old.tab.id == new.tab.id
            override fun areContentsTheSame(old: IconTab, new: IconTab): Boolean =
                old.selected == new.selected && old.tab.content.url == new.tab.content.url &&
                    old.tab.content.title == new.tab.content.title &&
                    old.tab.content.private == new.tab.content.private &&
                    old.tab.content.icon === new.tab.content.icon
        }
    }
}
