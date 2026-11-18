package com.mindfilter.app.service

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Administrateur de l'appareil : tant qu'il est actif, Android refuse la désinstallation de
 * MindFilter. Aucune règle n'est imposée au téléphone (aucune « policy »).
 */
class MindFilterAdminReceiver : DeviceAdminReceiver() {

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "MindFilter est verrouillé par un code maître. Déverrouillez-le dans l'appli avant de le désactiver."

    companion object {
        fun component(context: Context) = ComponentName(context, MindFilterAdminReceiver::class.java)

        fun isActive(context: Context): Boolean = try {
            (context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).isAdminActive(component(context))
        } catch (_: Exception) {
            false
        }

        /** Écran Android d'activation (l'utilisateur doit confirmer). */
        fun activationIntent(context: Context): Intent =
            Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(context))
                .putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Empêche la désinstallation de MindFilter tant que le code maître n'a pas été saisi.",
                )

        fun deactivate(context: Context) {
            try {
                (context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).removeActiveAdmin(component(context))
            } catch (_: Exception) {
            }
        }
    }
}
