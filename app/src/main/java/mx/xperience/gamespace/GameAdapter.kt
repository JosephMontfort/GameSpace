/*
 * Copyright (C) 2026 The XPerience Project
 * SPDX-License-Identifier: Apache-2.0
 */
package mx.xperience.gamespace

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

class GameAdapter(
    private val games: MutableList<GameModel>,
    private val onClick: (String) -> Unit,
    private val onLongClick: (String) -> Unit,
    private val onAddClick: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_GAME = 0
        private const val TYPE_ADD  = 1
    }

    private var lastAnimatedPosition = -1

    override fun getItemViewType(pos: Int) = if (pos < games.size) TYPE_GAME else TYPE_ADD

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_game, parent, false)
        return if (viewType == TYPE_GAME) GameVH(view) else AddVH(view)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, pos: Int) {
        // Staggered fade-in on first appearance only
        if (pos > lastAnimatedPosition) {
            AlphaAnimation(0f, 1f).apply {
                duration      = 220
                startOffset   = (pos * 25L).coerceAtMost(250L)
                interpolator  = DecelerateInterpolator()
                holder.itemView.startAnimation(this)
            }
            lastAnimatedPosition = pos
        }

        when (holder) {
            is GameVH -> {
                val game = games[pos]
                holder.icon.setImageDrawable(game.icon)
                holder.name.text = game.name
                holder.card.setCardBackgroundColor(Color.parseColor("#141714"))
                holder.card.strokeWidth = 1
                holder.card.strokeColor = Color.parseColor("#1E2A1E")

                holder.itemView.setOnClickListener {
                    holder.card.animate().scaleX(0.90f).scaleY(0.90f).setDuration(70)
                        .withEndAction {
                            holder.card.animate().scaleX(1f).scaleY(1f).setDuration(130).start()
                        }.start()
                    onClick(game.packageName)
                }
                holder.itemView.setOnLongClickListener {
                    holder.card.strokeColor = Color.parseColor("#00FF41")
                    holder.card.strokeWidth = 2
                    holder.itemView.postDelayed({ holder.card.strokeWidth = 1; holder.card.strokeColor = Color.parseColor("#1E2A1E") }, 500)
                    onLongClick(game.packageName)
                    true
                }
            }
            is AddVH -> {
                holder.name.text = holder.itemView.context.getString(R.string.gs_add_game)
                holder.card.setCardBackgroundColor(Color.parseColor("#0D140D"))
                holder.card.strokeColor = Color.parseColor("#1A3A1A")
                holder.card.strokeWidth = 1
                holder.card.cardElevation = 0f
                holder.icon.setImageResource(android.R.drawable.ic_input_add)
                holder.icon.setColorFilter(Color.parseColor("#3300FF41"))
                holder.icon.setPadding(22, 22, 22, 22)
                holder.itemView.setOnClickListener {
                    holder.card.animate().scaleX(0.90f).scaleY(0.90f).setDuration(70)
                        .withEndAction {
                            holder.card.animate().scaleX(1f).scaleY(1f).setDuration(130).start()
                        }.start()
                    onAddClick()
                }
                holder.itemView.setOnLongClickListener(null)
            }
        }
    }

    override fun getItemCount() = games.size + 1

    fun resetAnimations() { lastAnimatedPosition = -1 }

    class GameVH(v: View) : RecyclerView.ViewHolder(v) {
        val card: MaterialCardView = v.findViewById(R.id.card_game)
        val icon: ImageView        = v.findViewById(R.id.game_icon)
        val name: TextView         = v.findViewById(R.id.game_name)
    }

    class AddVH(v: View) : RecyclerView.ViewHolder(v) {
        val card: MaterialCardView = v.findViewById(R.id.card_game)
        val icon: ImageView        = v.findViewById(R.id.game_icon)
        val name: TextView         = v.findViewById(R.id.game_name)
    }
}
