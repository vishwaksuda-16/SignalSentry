package com.example.signalsentry

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.signalsentry.databinding.ItemScanSessionBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.graphics.BitmapFactory
import android.view.View

class ScanSessionAdapter(
    private var sessions: List<ScanSession>,
    private val onItemClick: (ScanSession) -> Unit
) : RecyclerView.Adapter<ScanSessionAdapter.SessionViewHolder>() {

    inner class SessionViewHolder(val binding: ItemScanSessionBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SessionViewHolder {
        val binding = ItemScanSessionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SessionViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SessionViewHolder, position: Int) {
        val session = sessions[position]
        val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
        
        holder.binding.sessionDate.text = dateFormat.format(Date(session.startTime))
        holder.binding.sessionStats.text = "Avg: ${session.avgDbm} dBm | ${session.totalPoints} points"
        
        if (session.deadZones > 0) {
            holder.binding.sessionDanger.visibility = View.VISIBLE
            holder.binding.sessionDanger.text = "⚠️ ${session.deadZones} Weak Spots Found"
        } else {
            holder.binding.sessionDanger.visibility = View.GONE
        }

        // Load snapshot if exists
        session.snapshotPath?.let { path ->
            val imgFile = File(path)
            if (imgFile.exists()) {
                val bitmap = BitmapFactory.decodeFile(imgFile.absolutePath)
                holder.binding.sessionSnapshot.setImageBitmap(bitmap)
            }
        }

        holder.itemView.setOnClickListener { onItemClick(session) }
    }

    override fun getItemCount() = sessions.size

    fun updateData(newSessions: List<ScanSession>) {
        sessions = newSessions
        notifyDataSetChanged()
    }
}
