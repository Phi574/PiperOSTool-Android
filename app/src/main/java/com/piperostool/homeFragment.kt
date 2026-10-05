package com.piperostool

import android.os.Bundle
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment

class homeFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View>(R.id.homePiperBrowser).setOnClickListener {
            startActivity(Intent(requireContext(), PiperBrowserActivity::class.java))
        }
        view.findViewById<View>(R.id.homeFakeMap).setOnClickListener {
            startActivity(Intent(requireContext(), FakeMapActivity::class.java))
        }
    }
}
