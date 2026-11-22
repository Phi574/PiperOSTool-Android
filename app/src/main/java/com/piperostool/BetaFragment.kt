package com.piperostool

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.card.MaterialCardView

class BetaFragment : Fragment() {
    private var pager: ViewPager2? = null
    private var appsTab: TextView? = null
    private var gamesTab: TextView? = null
    private var pageCallback: ViewPager2.OnPageChangeCallback? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_beta, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val viewPager = view.findViewById<ViewPager2>(R.id.betaPager)
        val apps = view.findViewById<TextView>(R.id.betaTabApps)
        val games = view.findViewById<TextView>(R.id.betaTabGames)
        pager = viewPager
        appsTab = apps
        gamesTab = games
        pageCallback = object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateTabStyles(position)
            }
        }.also(viewPager::registerOnPageChangeCallback)
        viewPager.adapter = BetaContentAdapter()
        viewPager.setCurrentItem(0, false)
        apps.setOnClickListener { viewPager.setCurrentItem(0, true) }
        games.setOnClickListener { viewPager.setCurrentItem(1, true) }
        updateTabStyles(0)
        viewPager.post { updateTabStyles(viewPager.currentItem) }
    }

    override fun onResume() {
        super.onResume()
        pager?.post { updateTabStyles(pager?.currentItem ?: 0) }
    }

    override fun onDestroyView() {
        pageCallback?.let { pager?.unregisterOnPageChangeCallback(it) }
        pager?.adapter = null
        pager = null
        pageCallback = null
        appsTab = null
        gamesTab = null
        super.onDestroyView()
    }

    private fun updateTabStyles(position: Int) {
        val context = context ?: return
        val density = resources.displayMetrics.density
        val surface = PiperModernUi.surfaceColor(context)
        val border = PiperModernUi.borderColor(context)
        val accent = PiperModernUi.accentColor(context)
        val secondary = PiperModernUi.secondaryTextColor(context)
        val onAccent = PiperModernUi.onAccentColor(context)

        view?.findViewById<View>(R.id.betaTabBar)?.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 18f * density
            setColor(surface)
            setStroke(density.toInt().coerceAtLeast(1), border)
        }
        listOf(appsTab, gamesTab).forEachIndexed { index, tab ->
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


    private inner class BetaContentAdapter :
        RecyclerView.Adapter<BetaContentAdapter.PageHolder>() {
        private val appsPage = 0
        private val gamesPage = 1

        override fun getItemCount(): Int = 2

        override fun getItemViewType(position: Int): Int =
            if (position == gamesPage) gamesPage else appsPage

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val layout = if (viewType == gamesPage) {
                R.layout.fragment_beta_games
            } else {
                R.layout.fragment_beta_apps
            }
            val page = LayoutInflater.from(parent.context).inflate(layout, parent, false)
            return PageHolder(page)
        }

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            val page = holder.itemView
            if (position == gamesPage) {
                styleGamesNotice(page)
                return
            }
            page.findViewById<View>(R.id.featurePiperTerminal).setOnClickListener {
                startActivity(Intent(requireContext(), PiperTerminalActivity::class.java))
            }
            page.findViewById<View>(R.id.featureFileManager).setOnClickListener {
                startActivity(Intent(requireContext(), PiperFileManagerActivity::class.java))
            }
            closeBetaFeature(
                page.findViewById(R.id.featurePiperSetEdit),
                page.findViewById(R.id.setEditFeatureStatus)
            )
            closeBetaFeature(
                page.findViewById(R.id.featurePiperDataTransfer),
                page.findViewById(R.id.dataTransferFeatureStatus)
            )
        }

        inner class PageHolder(view: View) : RecyclerView.ViewHolder(view)
    }

    private fun styleGamesNotice(root: View) {
        val context = root.context
        val card = root.findViewById<MaterialCardView>(R.id.betaGamesNoticeCard)
        card.setCardBackgroundColor(PiperModernUi.surfaceColor(context))
        card.strokeColor = PiperModernUi.borderColor(context)
        card.strokeWidth = (resources.displayMetrics.density).toInt().coerceAtLeast(1)
        card.radius = 20f * resources.displayMetrics.density
        card.cardElevation = 0f
        root.findViewById<TextView>(R.id.betaGamesTitle)
            .setTextColor(PiperModernUi.textColor(context))
        root.findViewById<TextView>(R.id.betaGamesMessage)
            .setTextColor(PiperModernUi.secondaryTextColor(context))
    }
    private fun closeBetaFeature(card: MaterialCardView, badge: ViewGroup) {
        card.alpha = 0.48f
        badge.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(30).toFloat()
            setColor(Color.rgb(116, 126, 138))
        }
        (badge.getChildAt(1) as? TextView)?.apply {
            setText(R.string.feature_close_status)
            setTextColor(Color.WHITE)
        }
        (badge.getChildAt(0) as? android.widget.ImageView)?.setColorFilter(Color.WHITE)
        card.setOnClickListener {
            PiperDialog.showMessage(
                context = requireContext(),
                title = getString(R.string.feature_unavailable_title),
                message = getString(R.string.feature_unavailable_message),
                positiveLabel = getString(android.R.string.ok)
            )
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
