package com.lumicontrol.ui.fragments

import android.os.Bundle
import android.view.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.lumicontrol.R
import com.lumicontrol.models.LightEffect
import com.lumicontrol.ui.viewmodels.BulbViewModel

class HomeFragment : Fragment() {

    private val viewModel: BulbViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }
}

class ColorFragment : Fragment() {

    companion object {
        fun newInstance(mac: String) = ColorFragment().apply {
            arguments = Bundle().apply { putString("mac_address", mac) }
        }
    }

    private val viewModel: BulbViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_color, container, false)
    }
}
