package com.piperostool

import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView

class PiperRemoteQrCaptureActivity : CaptureActivity() {
    override fun initializeContent(): DecoratedBarcodeView {
        setContentView(R.layout.activity_piper_remote_qr_scan)
        return findViewById(R.id.zxing_barcode_scanner)
    }
}
