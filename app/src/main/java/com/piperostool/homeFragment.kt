package com.piperostool

import android.os.Bundle
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.core.widget.NestedScrollView
import com.google.android.material.card.MaterialCardView

class homeFragment : Fragment() {
    private var appTab: TextView? = null
    private var gameTab: TextView? = null
    private var utilityTab: TextView? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        appTab = view.findViewById(R.id.homeTabApps)
        gameTab = view.findViewById(R.id.homeTabGames)
        utilityTab = view.findViewById(R.id.homeTabUtility)
        appTab?.setOnClickListener { showCategory(view, 0) }
        gameTab?.setOnClickListener { showCategory(view, 1) }
        utilityTab?.setOnClickListener { showCategory(view, 2) }
        showCategory(view, 0)

        view.findViewById<View>(R.id.homePiperBrowser).setOnClickListener {
            startActivity(Intent(requireContext(), PiperBrowserActivity::class.java))
        }
        view.findViewById<View>(R.id.homeFakeMap).setOnClickListener {
            startActivity(Intent(requireContext(), FakeMapActivity::class.java))
        }
        view.findViewById<View>(R.id.homePiperQr).setOnClickListener {
            startActivity(Intent(requireContext(), PiperQrActivity::class.java))
        }
        view.findViewById<View>(R.id.homePiperMedia).setOnClickListener {
            startActivity(Intent(requireContext(), PiperMediaActivity::class.java))
        }
        view.findViewById<View>(R.id.homePiper2fa).setOnClickListener {
            startActivity(Intent(requireContext(), BetaTwoFactorActivity::class.java))
        }
        view.findViewById<View>(R.id.homePiperRemote).setOnClickListener {
            startActivity(Intent(requireContext(), PiperRemoteActivity::class.java))
        }
        view.findViewById<View>(R.id.homePiperAdb).setOnClickListener {
            startActivity(Intent(requireContext(), com.piperostool.privileged.ui.PiperAdbActivity::class.java))
        }
    }

    override fun onDestroyView() {
        appTab = null
        gameTab = null
        utilityTab = null
        super.onDestroyView()
    }

    private fun showCategory(root: View, category: Int) {
        val appIds = listOf(R.id.homePiperBrowser, R.id.homePiperQr, R.id.homePiperMedia, R.id.homePiper2fa)
        val utilityIds = listOf(R.id.homeFakeMap, R.id.homePiperRemote, R.id.homePiperAdb)
        appIds.forEach { root.findViewById<View>(it).visibility = if (category == 0) View.VISIBLE else View.GONE }
        utilityIds.forEach { root.findViewById<View>(it).visibility = if (category == 2) View.VISIBLE else View.GONE }

        val heading = root.findViewById<TextView>(R.id.homeToolsHeading)
        val gameNotice = root.findViewById<MaterialCardView>(R.id.homeGamesNoticeCard)
        heading.visibility = if (category == 1) View.GONE else View.VISIBLE
        heading.setText(if (category == 2) R.string.home_utilities_heading else R.string.home_tools_heading)
        gameNotice.visibility = if (category == 1) View.VISIBLE else View.GONE
        root.findViewById<NestedScrollView>(R.id.homeContentScroll).scrollTo(0, 0)
        updateTabStyles(category)
        styleGamesNotice(root)
    }

    private fun updateTabStyles(position: Int) {
        val context = context ?: return
        val density = resources.displayMetrics.density
        val surface = PiperModernUi.surfaceColor(context)
        val border = PiperModernUi.borderColor(context)
        val accent = PiperModernUi.accentColor(context)
        val secondary = PiperModernUi.secondaryTextColor(context)
        val onAccent = PiperModernUi.onAccentColor(context)
        view?.findViewById<View>(R.id.homeTabBar)?.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 18f * density
            setColor(surface)
            setStroke(density.toInt().coerceAtLeast(1), border)
        }
        listOf(appTab, gameTab, utilityTab).forEachIndexed { index, tab ->
            tab ?: return@forEachIndexed
            val selected = index == position
            tab.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 14f * density
                setColor(if (selected) accent else surface)
                setStroke(density.toInt().coerceAtLeast(1), if (selected) accent else border)
            }
            tab.setTextColor(if (selected) onAccent else secondary)
            tab.isSelected = selected
            tab.isActivated = selected
        }
    }

    private fun styleGamesNotice(root: View) {
        val context = root.context
        val card = root.findViewById<MaterialCardView>(R.id.homeGamesNoticeCard)
        card.setCardBackgroundColor(PiperModernUi.surfaceColor(context))
        card.strokeColor = PiperModernUi.borderColor(context)
        card.strokeWidth = resources.displayMetrics.density.toInt().coerceAtLeast(1)
        card.radius = 20f * resources.displayMetrics.density
        card.cardElevation = 0f
        root.findViewById<TextView>(R.id.homeGamesTitle)
            .setTextColor(PiperModernUi.textColor(context))
        root.findViewById<TextView>(R.id.homeGamesMessage)
            .setTextColor(PiperModernUi.secondaryTextColor(context))
    }
}
