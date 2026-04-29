package com.piperostool

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment

class homeFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Nạp giao diện fragment_home
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Mọi logic của bản 1.0 (như ẩn hiện bottomNavCard, quét bảo mật) đã được gỡ bỏ hoàn toàn.
        // Nơi đây đã sẵn sàng để code các tính năng Dashboard mới cho v2.0!
    }
}