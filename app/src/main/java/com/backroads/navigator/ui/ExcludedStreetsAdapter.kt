package com.backroads.navigator.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.backroads.navigator.R
import com.backroads.navigator.api.ExcludedStreet

class ExcludedStreetsAdapter(
    private val onRemoveClick: (ExcludedStreet) -> Unit
) : RecyclerView.Adapter<ExcludedStreetsAdapter.ViewHolder>() {

    private val streets = mutableListOf<ExcludedStreet>()

    fun setStreets(newStreets: List<ExcludedStreet>) {
        streets.clear()
        streets.addAll(newStreets)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_excluded_street, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(streets[position])
    }

    override fun getItemCount() = streets.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val nameText: TextView = view.findViewById(R.id.text_street_name)
        private val addressText: TextView = view.findViewById(R.id.text_street_address)
        private val removeBtn: ImageButton = view.findViewById(R.id.btn_remove)

        fun bind(street: ExcludedStreet) {
            nameText.text = street.name
            addressText.text = street.displayAddress
            removeBtn.setOnClickListener { onRemoveClick(street) }
        }
    }
}
