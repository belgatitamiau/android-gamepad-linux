package com.gamepadbridge

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class QRScannerActivity : AppCompatActivity() {

    private val scanner = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            val data = result.contents.trim()
            Log.i(TAG, "QR: $data")
            val parts = data.split(":")
            if (parts.size == 2) {
                val port = parts[1].toIntOrNull()
                if (port != null) {
                    Intent().apply {
                        putExtra(EXTRA_HOST, parts[0])
                        putExtra(EXTRA_PORT, port)
                    }.also { setResult(RESULT_OK, it) }
                    finish()
                    return@registerForActivityResult
                }
            }
            Log.w(TAG, "Bad QR format: $data")
        }
        setResult(RESULT_CANCELED)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val options = ScanOptions()
        options.setDesiredBarcodeFormats("QR_CODE")
        options.setPrompt("Scan QR from PC server")
        options.setBeepEnabled(false)
        options.setOrientationLocked(true)
        options.setCameraId(0)
        scanner.launch(options)
    }

    companion object {
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        private const val TAG = "QRScanner"
    }
}
