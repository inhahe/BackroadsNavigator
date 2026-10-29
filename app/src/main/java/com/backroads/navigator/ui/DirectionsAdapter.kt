package com.backroads.navigator.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.backroads.navigator.R
import com.backroads.navigator.api.RouteStep

class DirectionsAdapter(
    private val onStepClick: (Int, RouteStep) -> Unit
) : RecyclerView.Adapter<DirectionsAdapter.ViewHolder>() {

    private val steps = mutableListOf<RouteStep>()

    fun setSteps(newSteps: List<RouteStep>) {
        steps.clear()
        steps.addAll(newSteps)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_direction_step, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val step = steps[position]
        holder.bind(position + 1, step)
        holder.itemView.setOnClickListener { onStepClick(position, step) }
    }

    override fun getItemCount() = steps.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val numberText: TextView = view.findViewById(R.id.text_step_number)
        private val instructionText: TextView = view.findViewById(R.id.text_step_instruction)
        private val roadText: TextView = view.findViewById(R.id.text_step_road)
        private val distText: TextView = view.findViewById(R.id.text_step_dist)

        fun bind(number: Int, step: RouteStep) {
            numberText.text = number.toString()
            instructionText.text = step.instruction
            roadText.text = step.name.ifBlank { "" }
            roadText.visibility = if (step.name.isBlank()) View.GONE else View.VISIBLE
            distText.text = formatDist(step.distanceMeters)
        }

        private fun formatDist(meters: Double): String = when {
            meters >= 1000 -> String.format("%.1f km", meters / 1000)
            else -> String.format("%.0f m", meters)
        }
    }
}
