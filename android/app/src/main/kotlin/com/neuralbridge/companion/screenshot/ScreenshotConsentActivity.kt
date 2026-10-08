package com.neuralbridge.companion.screenshot

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import com.neuralbridge.companion.service.NeuralBridgeAccessibilityService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Screenshot Consent Activity
 *
 * Transparent Activity that handles MediaProjection user consent dialog.
 *
 * FIX: Agora CRIA o MediaProjection imediatamente no onActivityResult e
 * armazena em pendingProjection, além de notificar o Service se ele já
 * estiver rodando. Isso elimina a dependência de polling/timing.
 */
class ScreenshotConsentActivity : Activity() {

    companion object {
        private const val TAG = "ScreenshotConsentActivity"
        private const val REQUEST_MEDIA_PROJECTION = 1

        // Prevents two concurrent instances from both showing the system dialog
        private val isConsentInProgress = AtomicBoolean(false)

        // FIX: MediaProjection já criado, disponível pra consumo imediato
        @Volatile
        private var pendingProjection: MediaProjection? = null

        /**
         * Check if a MediaProjection is available (already created).
         * Pode ser chamado mesmo sem o Service estar rodando.
         */
        fun hasProjection(): Boolean {
            return pendingProjection != null
        }

        /**
         * Consume the pending MediaProjection (thread-safe — only one caller wins).
         * @return The MediaProjection instance, or null
         */
        @Synchronized
        fun consumeProjection(): MediaProjection? {
            val proj = pendingProjection
            pendingProjection = null
            return proj
        }

        /**
         * Queried by MainActivity.updateAllPermissionStatus()
         * Retorna true se há projeção disponível (já concedida)
         */
        fun isPermissionGranted(): Boolean {
            return pendingProjection != null
        }

        /**
         * Create intent to launch this Activity
         */
        fun createIntent(context: Context): Intent {
            return Intent(context, ScreenshotConsentActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!isConsentInProgress.compareAndSet(false, true)) {
            Log.d(TAG, "Consent already in progress — dismissing duplicate")
            finish()
            return
        }

        Log.d(TAG, "ScreenshotConsentActivity created")

        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = mediaProjectionManager.createScreenCaptureIntent()

        startActivityForResult(intent, REQUEST_MEDIA_PROJECTION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            Log.d(TAG, "MediaProjection consent result: resultCode=$resultCode")

            if (resultCode == RESULT_OK && data != null) {
                // FIX: Cria o MediaProjection IMEDIATAMENTE, aqui e agora
                try {
                    // PR#2: no Android 14+ o tipo FGS precisa incluir MEDIA_PROJECTION ANTES
                    // de getMediaProjection() — service-side upgrade com guard AtomicBoolean.
                    NeuralBridgeAccessibilityService.instance?.upgradeForegroundServiceForMediaProjection()
                    val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    val projection = manager.getMediaProjection(resultCode, data)

                    // Registra callback pra detectar quando a sessão morrer
                    projection.registerCallback(object : MediaProjection.Callback() {
                        override fun onStop() {
                            Log.w(TAG, "MediaProjection session stopped by system")
                            // Se a sessão morrer, limpa a referência
                            if (pendingProjection == projection) {
                                pendingProjection = null
                            }
                        }
                    }, null)

                    pendingProjection = projection

                    // Notifica o Service se ele já estiver rodando
                    val service = NeuralBridgeAccessibilityService.instance
                    if (service != null) {
                        service.onMediaProjectionGranted(projection)
                        Log.i(TAG, "MediaProjection created and passed to running service")
                    } else {
                        Log.i(TAG, "MediaProjection created and stored — service will pick it up when it starts")
                    }

                    Log.i(TAG, "MediaProjection consent granted and projection created")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create MediaProjection", e)
                }
            } else {
                Log.w(TAG, "MediaProjection consent denied or cancelled")
            }
        }

        isConsentInProgress.set(false)
        finish()
    }

    override fun onDestroy() {
        isConsentInProgress.set(false)
        super.onDestroy()
    }
}
