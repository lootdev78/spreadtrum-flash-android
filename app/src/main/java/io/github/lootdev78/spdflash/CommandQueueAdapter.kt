package io.github.lootdev78.spdflash

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.lootdev78.spdflash.databinding.ItemCommandBinding

class CommandQueueAdapter(
    private val onRemove: (QueuedCommand) -> Unit,
    private val onMove: (QueuedCommand, Int) -> Unit,
    private val onDuplicate: (QueuedCommand) -> Unit,
) : ListAdapter<QueuedCommand, CommandQueueAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemCommandBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position), position)

    inner class Holder(private val binding: ItemCommandBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: QueuedCommand, position: Int) {
            val context = binding.root.context
            val color = when (item.risk) {
                RiskLevel.SAFE -> ContextCompat.getColor(context, R.color.success)
                RiskLevel.CAUTION -> ContextCompat.getColor(context, R.color.warning)
                RiskLevel.DESTRUCTIVE -> ContextCompat.getColor(context, R.color.danger)
            }
            binding.riskStrip.backgroundTintList = ColorStateList.valueOf(color)
            binding.commandTitle.text = item.title
            binding.commandRisk.text = item.risk.label
            binding.commandRisk.setTextColor(color)
            binding.commandTokens.text = item.tokens.joinToString(" ") { token ->
                if (token.any { it.isWhitespace() }) "\"$token\"" else token
            }
            binding.commandSize.text = item.expectedBytes?.let { "Estimated: ${SizeParser.human(it)}" } ?: "Size: dynamic / unknown"
            binding.moveUpButton.isEnabled = position > 0
            binding.moveDownButton.isEnabled = position < currentList.lastIndex
            binding.moveUpButton.setOnClickListener { onMove(item, -1) }
            binding.moveDownButton.setOnClickListener { onMove(item, 1) }
            binding.duplicateCommandButton.setOnClickListener { onDuplicate(item) }
            binding.removeCommandButton.setOnClickListener { onRemove(item) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<QueuedCommand>() {
        override fun areItemsTheSame(oldItem: QueuedCommand, newItem: QueuedCommand) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: QueuedCommand, newItem: QueuedCommand) = oldItem == newItem
    }
}
