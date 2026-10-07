package com.piperostool

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import androidx.fragment.app.Fragment
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.firebase.auth.FirebaseAuth

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
        view.findViewById<View>(R.id.homePiperQr).setOnClickListener {
            startActivity(Intent(requireContext(), PiperQrActivity::class.java))
        }
        view.findViewById<View>(R.id.btnHomeWindow).setOnClickListener { openWindowDrawer(view) }
        view.findViewById<View>(R.id.btnCloseHomeWindow).setOnClickListener { closeWindowDrawer(view) }
        view.findViewById<View>(R.id.homeWindowScrim).setOnClickListener { closeWindowDrawer(view) }
        view.findViewById<View>(R.id.homeWindowProfile).setOnClickListener {
            closeWindowDrawer(view) {
                startActivity(Intent(requireContext(), AccountProfileActivity::class.java))
            }
        }
        view.findViewById<View>(R.id.homeWindowSecurity).setOnClickListener {
            closeWindowDrawer(view) {
                startActivity(Intent(requireContext(), DeviceSessionsActivity::class.java))
            }
        }
        view.findViewById<View>(R.id.homeWindowSupport).setOnClickListener {
            closeWindowDrawer(view) { openSupport() }
        }
        view.findViewById<View>(R.id.btnHomeWindowLogout).setOnClickListener {
            closeWindowDrawer(view) { confirmLogout() }
        }

        val homeScroll = view.findViewById<androidx.core.widget.NestedScrollView>(R.id.homeContentScroll)
        val baseTopPadding = homeScroll.paddingTop
        val drawerPanel = view.findViewById<View>(R.id.homeWindowPanel)
        val baseDrawerVerticalMargin = (12 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            val safeTop = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            ).top
            val safeBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            homeScroll.setPadding(
                homeScroll.paddingLeft,
                baseTopPadding + safeTop,
                homeScroll.paddingRight,
                homeScroll.paddingBottom
            )
            (drawerPanel.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let { params ->
                params.topMargin = baseDrawerVerticalMargin + safeTop
                params.bottomMargin = baseDrawerVerticalMargin + safeBottom
                drawerPanel.layoutParams = params
            }
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }

    fun closeWindowDrawer(): Boolean {
        val root = view ?: return false
        if (root.findViewById<View>(R.id.homeWindowOverlay).visibility != View.VISIBLE) return false
        closeWindowDrawer(root)
        return true
    }

    private fun openWindowDrawer(root: View) {
        val overlay = root.findViewById<View>(R.id.homeWindowOverlay)
        if (overlay.visibility == View.VISIBLE) return
        val panel = root.findViewById<View>(R.id.homeWindowPanel)
        val scrim = root.findViewById<View>(R.id.homeWindowScrim)
        overlay.visibility = View.VISIBLE
        val distance = panel.width.takeIf { it > 0 } ?: (320 * resources.displayMetrics.density).toInt()
        panel.translationX = distance.toFloat()
        scrim.alpha = 0f
        panel.animate().cancel()
        scrim.animate().cancel()
        panel.animate().translationX(0f).setDuration(260L).setInterpolator(DecelerateInterpolator()).start()
        scrim.animate().alpha(1f).setDuration(220L).start()
        (activity as? HomeActivity)?.hideBottomNav(force = true)
    }

    private fun closeWindowDrawer(root: View, afterClosed: (() -> Unit)? = null) {
        val overlay = root.findViewById<View>(R.id.homeWindowOverlay)
        if (overlay.visibility != View.VISIBLE) {
            afterClosed?.invoke()
            return
        }
        val panel = root.findViewById<View>(R.id.homeWindowPanel)
        val scrim = root.findViewById<View>(R.id.homeWindowScrim)
        val distance = panel.width.takeIf { it > 0 } ?: (320 * resources.displayMetrics.density).toInt()
        panel.animate().cancel()
        scrim.animate().cancel()
        panel.animate().translationX(distance.toFloat()).setDuration(220L)
            .setInterpolator(DecelerateInterpolator()).withEndAction {
                overlay.visibility = View.GONE
                panel.translationX = 0f
                afterClosed?.invoke()
            }.start()
        scrim.animate().alpha(0f).setDuration(180L).start()
        (activity as? HomeActivity)?.showBottomNav(force = true)
    }

    private fun openSupport() {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:gayivt@gmail.com")).apply {
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.home_support_email_subject))
        }
        runCatching { startActivity(intent) }.onFailure {
            android.widget.Toast.makeText(requireContext(), R.string.home_support_no_email, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmLogout() {
        PiperDialog.showConfirm(
            context = requireContext(),
            title = getString(R.string.auth_logout),
            message = getString(R.string.logout_confirmation),
            positiveLabel = getString(R.string.auth_logout),
            destructive = true
        ) {
            DeviceSessionManager.endCurrentSession(requireContext().applicationContext) {
                FirebaseAuth.getInstance().signOut()
                startActivity(Intent(requireContext(), LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
                requireActivity().finish()
            }
        }
    }
}
