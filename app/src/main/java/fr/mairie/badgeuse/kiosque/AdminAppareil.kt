package fr.mairie.badgeuse.kiosque

import android.app.admin.DeviceAdminReceiver

/**
 * Point d'entrée que le système exige pour qu'une application devienne « propriétaire de l'appareil ».
 * Il n'a aucun comportement propre : tout se passe dans [Kiosque].
 */
class AdminAppareil : DeviceAdminReceiver()
