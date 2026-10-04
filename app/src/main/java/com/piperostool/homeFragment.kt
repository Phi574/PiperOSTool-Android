package com.piperostool

import android.os.Bundle
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import com.example.liquidglass.LiquidGlassView
import android.view.ViewGroup
import android.widget.TextView
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
        if (!PiperUiPreferences.isModern(requireContext())) {
        view.findViewById<TextView>(R.id.homeScreenTitle).setText(R.string.home_classic_title)
        view.findViewById<TextView>(R.id.homeScreenSummary).setText(R.string.home_classic_summary)
        view.findViewById<LiquidGlassView>(R.id.homeGlassPanel)?.apply {
            enableDynamicBackground = true
            backdropSource = requireActivity().findViewById(R.id.homeBackground)
        }
        }
    }
}
