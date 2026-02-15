/*
 * Copyright (C) 2026 The XPerience Project
 * SPDX-License-Identifier: Apache-2.0
 */
package mx.xperience.gamespace

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

class GameAdapter(
    private val games: List<GameModel>,
        private val onClick: (String) -> Unit
) : RecyclerView.Adapter<GameAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view.findViewById(R.id.card_game)
        val icon: ImageView = view.findViewById(R.id.game_icon)
        val name: TextView = view.findViewById(R.id.game_name)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_game, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val game = games[position]
        holder.icon.setImageDrawable(game.icon)
        holder.name.text = game.name

        holder.itemView.setOnClickListener {
            // Animación de feedback visual
            holder.card.strokeWidth = 4 // Activa el borde verde
            val anim = AnimationUtils.loadAnimation(holder.itemView.context, android.R.anim.fade_in)
            holder.card.startAnimation(anim)

            // Efecto de escala
            holder.itemView.animate().scaleX(0.9f).scaleY(0.9f).setDuration(100).withEndAction {
                holder.itemView.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                onClick(game.packageName)
            }.start()
        }
    }

    override fun getItemCount() = games.size
}

/*data class GameModel(
    val name: String,
    val packageName: String,
    val icon: Drawable
)*/
