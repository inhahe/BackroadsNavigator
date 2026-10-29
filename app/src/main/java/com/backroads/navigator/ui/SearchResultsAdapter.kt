package com.backroads.navigator.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.backroads.navigator.R
import com.backroads.navigator.api.GeocodingResult

class SearchResultsAdapter(
    private val onItemClick: (GeocodingResult) -> Unit
) : RecyclerView.Adapter<SearchResultsAdapter.ViewHolder>() {

    private val results = mutableListOf<GeocodingResult>()

    fun setResults(newResults: List<GeocodingResult>) {
        results.clear()
        results.addAll(newResults)
        notifyDataSetChanged()
    }

    fun clear() {
        results.clear()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_search_result, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val result = results[position]
        holder.bind(result)
        holder.itemView.setOnClickListener { onItemClick(result) }
    }

    override fun getItemCount() = results.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: ImageView = view.findViewById(R.id.icon_place)
        private val nameText: TextView = view.findViewById(R.id.text_name)
        private val addressText: TextView = view.findViewById(R.id.text_address)

        fun bind(result: GeocodingResult) {
            // Saved places have type "saved_home", "saved_work", "saved_favorite"
            when {
                result.type == "saved_home" -> {
                    icon.setImageResource(R.drawable.ic_home)
                    icon.visibility = View.VISIBLE
                    nameText.text = "Home"
                    addressText.text = result.displayName
                        .removePrefix("Home — ").split(",")
                        .take(3).joinToString(",").trim()
                }
                result.type == "saved_work" -> {
                    icon.setImageResource(R.drawable.ic_work)
                    icon.visibility = View.VISIBLE
                    nameText.text = "Work"
                    addressText.text = result.displayName
                        .removePrefix("Work — ").split(",")
                        .take(3).joinToString(",").trim()
                }
                result.type == "saved_favorite" -> {
                    icon.setImageResource(R.drawable.ic_star)
                    icon.visibility = View.VISIBLE
                    // Label is everything before " — "
                    val dashIndex = result.displayName.indexOf(" — ")
                    if (dashIndex > 0) {
                        nameText.text = result.displayName.substring(0, dashIndex)
                        addressText.text = result.displayName.substring(dashIndex + 3)
                            .split(",").take(3).joinToString(",").trim()
                    } else {
                        nameText.text = result.displayName.split(",").first().trim()
                        addressText.text = result.displayName
                    }
                }
                else -> {
                    icon.visibility = View.GONE
                    // Normal geocoding result: first part = name, rest = address
                    val parts = result.displayName.split(",")
                    nameText.text = parts.firstOrNull()?.trim() ?: result.displayName
                    addressText.text = if (parts.size > 1) {
                        parts.drop(1).joinToString(",").trim()
                    } else {
                        result.type.replaceFirstChar { it.uppercase() }
                    }
                }
            }
        }
    }
}
