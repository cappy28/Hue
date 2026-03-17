package com.lumicontrol.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lumicontrol.models.Bulb
import com.lumicontrol.models.ConnectionState

class BulbCardAdapter(
    private val onBulbClick: (Bulb) -> Unit,
    private val onPowerToggle: (Bulb, Boolean) -> Unit,
    private val onFavorite: (Bulb) -> Unit,
    private val onLongClick: (Bulb) -> Unit
) : ListAdapter<Bulb, BulbCardAdapter.ViewHolder>(BulbDiff()) {

    inner class ViewHolder(val view: android.view.View) : RecyclerView.ViewHolder(view)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(android.view.View(parent.context))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {}
}

private class BulbDiff : DiffUtil.ItemCallback<Bulb>() {
    override fun areItemsTheSame(a: Bulb, b: Bulb) = a.macAddress == b.macAddress
    override fun areContentsTheSame(a: Bulb, b: Bulb) = a == b
}

class ScanResultAdapter(
    private val onConnect: (Bulb) -> Unit,
    private val onHistory: (Bulb) -> Unit
) : RecyclerView.Adapter<ScanResultAdapter.ViewHolder>() {

    private val items = mutableListOf<Bulb>()

    fun submitScanList(bulbs: List<Bulb>) { items.clear(); items.addAll(bulbs); notifyDataSetChanged() }
    fun submitHistoryList(bulbs: List<Bulb>) {}

    inner class ViewHolder(val view: android.view.View) : RecyclerView.ViewHolder(view)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(android.view.View(parent.context))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {}
    override fun getItemCount() = items.size
}
