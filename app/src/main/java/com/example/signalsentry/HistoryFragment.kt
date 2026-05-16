package com.example.signalsentry

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.signalsentry.databinding.FragmentHistoryBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!
    private lateinit var db: AppDatabase
    private lateinit var adapter: ScanSessionAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        db = AppDatabase.getDatabase(requireContext())

        setupRecyclerView()
        loadSessions()
    }

    private fun setupRecyclerView() {
        adapter = ScanSessionAdapter(emptyList()) { session ->
            showSessionDetail(session)
        }
        binding.historyRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.historyRecyclerView.adapter = adapter
    }

    private fun loadSessions() {
        lifecycleScope.launch(Dispatchers.IO) {
            val sessions = db.signalDao().getAllSessions()
            withContext(Dispatchers.Main) {
                adapter.updateData(sessions)
            }
        }
    }

    private fun showSessionDetail(session: ScanSession) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_session_detail, null)
        val snapshotImg = dialogView.findViewById<ImageView>(R.id.detailSnapshot)
        val statsText = dialogView.findViewById<TextView>(R.id.detailStats)

        val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
        val duration = (session.endTime - session.startTime) / 1000
        
        statsText.text = """
            SESSION REPORT
            -------------------
            Date: ${dateFormat.format(Date(session.startTime))}
            Duration: ${duration}s
            Average Signal: ${session.avgDbm} dBm
            Total Data Points: ${session.totalPoints}
            Vulnerable Spots: ${session.deadZones}
            
            Analysis: ${if (session.deadZones < 3) "✅ Network connection was consistent." else "⚠️ Significant signal drops detected."}
        """.trimIndent()

        session.snapshotPath?.let { path ->
            val file = File(path)
            if (file.exists()) {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                snapshotImg.setImageBitmap(bitmap)
            }
        }

        AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setPositiveButton("Close", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
