package com.lumicontrol.ui.fragments

import android.content.Intent
import android.os.Bundle
import android.view.*
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.lumicontrol.R
import com.lumicontrol.ui.activities.ScanActivity
import com.lumicontrol.ui.viewmodels.BulbViewModel

class HomeFragment : Fragment() {

    private val viewModel: BulbViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val btnScan = view.findViewById<Button>(R.id.btnScan)
        val btnAllOn = view.findViewById<Button>(R.id.btnAllOn)
        val btnAllOff = view.findViewById<Button>(R.id.btnAllOff)
        val tvStatus = view.findViewById<TextView>(R.id.tvStatus)

        btnScan?.setOnClickListener {
            startActivity(Intent(requireContext(), ScanActivity::class.java))
        }
        btnAllOn?.setOnClickListener { viewModel.setAllPower(true) }
        btnAllOff?.setOnClickListener { viewModel.setAllPower(false) }

        viewModel.connectedBulbs.observe(viewLifecycleOwner) { bulbs ->
            tvStatus?.text = if (bulbs.isEmpty()) "Aucune ampoule connectée"
            else "${bulbs.size} ampoule(s) connectée(s)"
        }
    }
}

class ColorFragment : Fragment() {
    companion object {
        fun newInstance(mac: String) = ColorFragment().apply {
            arguments = Bundle().apply { putString("mac_address", mac) }
        }
    }
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_color, container, false)
}
