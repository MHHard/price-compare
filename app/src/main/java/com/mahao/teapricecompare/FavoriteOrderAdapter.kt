package com.mahao.teapricecompare

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

class FavoriteOrderAdapter(
    private val onEdit: (FavoriteOrder) -> Unit,
    private val onDelete: (FavoriteOrder) -> Unit,
    private val onCompare: (FavoriteOrder) -> Unit,
) : ListAdapter<FavoriteOrder, FavoriteOrderAdapter.ViewHolder>(DiffCallback) {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view as MaterialCardView
        val nameText: TextView = view.findViewById(R.id.orderNameText)
        val emojiText: TextView = view.findViewById(R.id.orderEmoji)
        val platformsText: TextView = view.findViewById(R.id.platformsText)
        val priceRow: LinearLayout = view.findViewById(R.id.priceRow)
        val moreButton: ImageButton = view.findViewById(R.id.moreButton)
        val editButton: MaterialButton = view.findViewById(R.id.editButton)
        val deleteButton: MaterialButton = view.findViewById(R.id.deleteButton)
        val compareButton: MaterialButton = view.findViewById(R.id.compareButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_favorite_order, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val order = getItem(position)
        val ctx = holder.itemView.context

        holder.nameText.text = order.name
        holder.emojiText.text = "T"
        holder.platformsText.text = order.targets.meituanTarget()?.let {
            "${it.storeKeyword} · ${it.productKeyword}"
        } ?: "还没有配置美团查询"

        // 首页不伪造价格：真实价格只有跑完美团流程后才有来源。
        holder.priceRow.removeAllViews()
        TextView(ctx).apply {
            text = "买券  ·  外卖  ·  自取   尚未查价"
            setTextColor(ctx.getColor(R.color.text_tertiary))
            textSize = 12f
            holder.priceRow.addView(this)
        }

        holder.editButton.setOnClickListener { onEdit(order) }
        holder.deleteButton.setOnClickListener { onDelete(order) }
        holder.compareButton.setOnClickListener { onCompare(order) }
        holder.moreButton.setOnClickListener { onEdit(order) }
        holder.itemView.setOnClickListener { onCompare(order) }
    }

    private fun Map<Platform, PlatformTarget>.meituanTarget(): PlatformTarget? =
        this[Platform.MEITUAN_DELIVERY]
            ?: this[Platform.MEITUAN]
            ?: this[Platform.MEITUAN_PICKUP]

    object DiffCallback : DiffUtil.ItemCallback<FavoriteOrder>() {
        override fun areItemsTheSame(old: FavoriteOrder, new: FavoriteOrder) = old.id == new.id
        override fun areContentsTheSame(old: FavoriteOrder, new: FavoriteOrder) = old == new
    }
}
