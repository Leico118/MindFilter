package com.mindfilter.app.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.content.pm.PackageInstaller
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.mindfilter.app.service.MindFilterAccessibilityService

object Permissions {

    fun isAccessibilityEnabled(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val ours = ComponentName(context, MindFilterAccessibilityService::class.java)
        val enabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        if (enabled.any {
                val si = it.resolveInfo?.serviceInfo
                si != null && si.packageName == ours.packageName && si.name == ours.className
            }
        ) return true
        val raw = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return raw.split(':').any {
            it.equals(ours.flattenToString(), true) || it.equals(ours.flattenToShortString(), true)
        }
    }

    /**
     * Android 13+ : une appli installée depuis un fichier téléchargé (navigateur, gestionnaire
     * de fichiers) est en « paramètres restreints » : Android bloque l'activation de son
     * service d'accessibilité tant que la personne ne l'a pas autorisé dans « Infos sur
     * l'appli ». Ce n'est pas contournable (et ne doit pas l'être) : on guide simplement.
     */
    fun mayBeRestricted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return try {
            val src = context.packageManager.getInstallSourceInfo(context.packageName).packageSource
            src == PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE ||
                src == PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE ||
                src == PackageInstaller.PACKAGE_SOURCE_UNSPECIFIED
        } catch (e: Exception) {
            true
        }
    }

    fun openAccessibilitySettings(context: Context) {
        val ours = ComponentName(context, MindFilterAccessibilityService::class.java).flattenToString()
        // Met MindFilter en évidence dans la liste quand le téléphone le permet.
        val args = Bundle().apply { putString(":settings:fragment_args_key", ours) }
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(":settings:fragment_args_key", ours)
            .putExtra(":settings:show_fragment_args", args)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            openAppDetails(context)
        }
    }

    /** Page « Infos sur l'appli » : menu ⋮ → « Autoriser les paramètres restreints ». */
    fun openAppDetails(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
        }
    }
}
