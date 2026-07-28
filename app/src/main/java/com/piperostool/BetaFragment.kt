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
        val browserFeature = view.findViewById<View>(R.id.featurePiperBrowser)
        val offlineState = view.findViewById<View>(R.id.betaOfflineState)

        NetworkAccess.observe(viewLifecycleOwner, requireContext()) { online ->
            browserFeature.visibility = if (online) View.VISIBLE else View.GONE
            offlineState.visibility = if (online) View.GONE else View.VISIBLE
            if (!online) NetworkAccess.showOffline(view)
        }

        browserFeature.setOnClickListener {
            NetworkAccess.requireOnline(view) {
                startActivity(Intent(requireContext(), PiperBrowserActivity::class.java))
            }
        }
        view.findViewById<View>(R.id.featurePiperMedia).setOnClickListener {
            startActivity(Intent(requireContext(), PiperMediaActivity::class.java))
        }
        view.findViewById<View>(R.id.featurePiperTerminal).setOnClickListener {
            startActivity(Intent(requireContext(), PiperTerminalActivity::class.java))
        }
    }
}
