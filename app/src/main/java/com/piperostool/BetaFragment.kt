package com.piperostool

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment

class BetaFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_beta, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View>(R.id.featurePiperQr).visibility = View.GONE
        view.findViewById<View>(R.id.featurePiperMedia).setOnClickListener {
            startActivity(Intent(requireContext(), PiperMediaActivity::class.java))
        }
        view.findViewById<View>(R.id.featurePiperTerminal).setOnClickListener {
            startActivity(Intent(requireContext(), PiperTerminalActivity::class.java))
        }
        view.findViewById<View>(R.id.featureFileManager).setOnClickListener {
            startActivity(Intent(requireContext(), PiperFileManagerActivity::class.java))
        }
        view.findViewById<View>(R.id.featurePiperRemote).setOnClickListener {
            startActivity(Intent(requireContext(), PiperRemoteActivity::class.java))
        }
        view.findViewById<View>(R.id.featurePiperAdb).setOnClickListener {
            startActivity(Intent(requireContext(), com.piperostool.privileged.ui.PiperAdbActivity::class.java))
        }
    }
}
