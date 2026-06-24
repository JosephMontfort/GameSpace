/*
 * Copyright (C) 2026 The XPerience Project
 * SPDX-License-Identifier: Apache-2.0
 */
package mx.xperience.gamespace

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
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
        private const val TYPE_GAME       = 0
        private const val TYPE_ADD_BUTTON = 1
    }

    // Track last-animated position for entrance animation
    private var lastAnimatedPosition = -1

    override fun getItemViewType(position: Int) =
        if (position == games.size) TYPE_ADD_BUTTON else TYPE_GAME

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val view = inflater.inflate(R.layout.item_game, parent, false)
        return if (viewType == TYPE_GAME) GameViewHolder(view) else AddViewHolder(view)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        // Staggered entrance animation — only on first bind, not on scroll-back
        if (position > lastAnimatedPosition) {
            val anim = android.view.animation.AnimationUtils.loadAnimation(
                holder.itemView.context, android.R.anim.fade_in
            )
            // Stagger: each item delays 30ms more than the previous
            anim.startOffset = (position * 30L).coerceAtMost(300L)
            holder.itemView.startAnimation(anim)
            lastAnimatedPosition = position
        }

        when (holder) {
            is GameViewHolder -> {
                val game = games[position]
                holder.icon.setImageDrawable(game.icon)
                holder.name.text = game.name

                // Clean card: no border by default
                holder.card.strokeWidth = 0
                holder.card.setCardBackgroundColor(Color.parseColor("#111411"))

                holder.itemView.setOnClickListener {
                    // Brief press-scale feedback
                    holder.card.animate()
                        .scaleX(0.92f).scaleY(0.92f).setDuration(80)
                        .withEndAction {
                            holder.card.animate()
                                .scaleX(1f).scaleY(1f).setDuration(120).start()
                        }.start()
                    onClick(game.packageName)
                }
                holder.itemView.setOnLongClickListener {
                    // Green border flash on long-press
                    holder.card.strokeColor = Color.parseColor("#00FF41")
                    holder.card.strokeWidth = 2
                    holder.itemView.postDelayed({
                        holder.card.strokeWidth = 0
                    }, 600)
                    onLongClick(game.packageName)
                    true
                }
            }

            is AddViewHolder -> {
                holder.name.text = holder.itemView.context.getString(R.string.gs_add_game)
                // Dashed green-tinted border card
                holder.card.setCardBackgroundColor(Color.parseColor("#0D1A0D"))
                holder.card.strokeColor  = Color.parseColor("#2200FF41")
                holder.card.strokeWidth  = 2
                holder.card.cardElevation = 0f

                // "+" icon — tinted green, slightly transparent
                holder.icon.setImageResource(android.R.drawable.ic_input_add)
                holder.icon.setColorFilter(Color.parseColor("#4400FF41"))
                holder.icon.setPadding(28, 28, 28, 28)
                holder.icon.alpha = 1f

                holder.itemView.setOnClickListener {
                    holder.card.animate()
                        .scaleX(0.92f).scaleY(0.92f).setDuration(80)
                        .withEndAction {
                            holder.card.animate()
                                .scaleX(1f).scaleY(1f).setDuration(120).start()
                        }.start()
                    onAddClick()
                }
            }
        }
    }

    override fun getItemCount() = games.size + 1

    fun resetAnimationTracker() {
        lastAnimatedPosition = -1
    }

    class GameViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view.findViewById(R.id.card_game)
        val icon: ImageView        = view.findViewById(R.id.game_icon)
        val name: TextView         = view.findViewById(R.id.game_name)
    }

    class AddViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view.findViewById(R.id.card_game)
        val icon: ImageView        = view.findViewById(R.id.game_icon)
        val name: TextView         = view.findViewById(R.id.game_name)
    }
}
