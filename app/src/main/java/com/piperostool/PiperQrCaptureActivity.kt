package com.piperostool

import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView

class PiperQrCaptureActivity : CaptureActivity() {
    override fun initializeContent(): DecoratedBarcodeView {
        setContentView(R.layout.activity_piper_qr_capture)
        return findViewById(R.id.piper_qr_barcode_scanner)
    }
}
