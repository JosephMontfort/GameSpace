/*
 * Copyright (C) 2026 The XPerience Project
 * SPDX-License-Identifier: Apache-2.0
 */
package mx.xperience.gamespace

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * RecyclerView adapter for the "add game" picker dialog.
 *
 * Using RecyclerView instead of ListView/ArrayAdapter eliminates the scroll
 * lag that occurs when ListView re-inflates views or rebinds icons on fast scroll.
 * Icons are pre-loaded before this adapter is constructed (on a background thread
 * in MainActivity.showAddGameDialog), so getView() / onBindViewHolder() is
 * purely a setText + setImageDrawable — no PackageManager calls on the UI thread.
 */
class AppPickerAdapter(
    private val apps: List<GameModel>,
    private val onPick: (String) -> Unit
) : RecyclerView.Adapter<AppPickerAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.app_icon)
        val name: TextView  = view.findViewById(R.id.app_name)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app_picker, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val app = apps[position]
        holder.icon.setImageDrawable(app.icon)
        holder.name.text = app.name
        holder.itemView.setOnClickListener { onPick(app.packageName) }
    }

    override fun getItemCount() = apps.size
}
