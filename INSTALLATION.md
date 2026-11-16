# Installation sur un appareil Androïd

Procédure pour la personne qui installe (l'installateur), à faire une seule fois, avec la DGS présente pour les mots de passe.

## 1. Préparer la tablette

- Android 8 ou plus, **neuve ou réinitialisée aux réglages d'usine**, connectée au wifi.
- **Ne configurer aucun compte** (ni Google, ni Samsung) : Android refuse de donner le mode kiosque à une application s'il y a déjà un compte.
- Activer les options pour les développeurs (Réglages > À propos > toucher 7 fois « Numéro de build »), puis le **débogage USB**.

## 2. Installer l'application

Brancher la tablette au Mac, accepter la fenêtre « Autoriser le débogage USB », puis, depuis le dossier du projet :

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleRelease
```

L'APK de la version finale est dans `~/.badgeuse-build/Badgeuse/app/outputs/apk/release/`. Il est **signé automatiquement avec la clé de la mairie** si le fichier `keystore.properties` existe à la racine du projet (sinon il sort non signé : `app-release-unsigned.apk`). Installer ensuite avec `adb install`.

Créer la clé une seule fois (garder le fichier `.jks` et ses mots de passe en lieu sûr, hors du projet partagé) :

```
keytool -genkeypair -v -keystore mairie-badgeuse.jks -alias badgeuse -keyalg RSA -keysize 4096 -validity 10000
```

Puis créer `keystore.properties` à la racine du projet (il est exclu du dépôt par `.gitignore`) :

```
storeFile=mairie-badgeuse.jks
storePassword=...
keyAlias=badgeuse
keyPassword=...
```

## 3. Mode kiosque

Toujours tablette branchée au Mac et sans compte :

```
adb shell dpm set-device-owner fr.mairie.badgeuse/.kiosque.AdminAppareil
```

Réponse attendue : `Success: Device owner set to package ...`. Ouvrir ensuite l'application : elle se verrouille à l'écran (plus de barre de navigation, ni de notifications, ni d'accès aux réglages) et se relance seule quand la tablette redémarre. L'heure est **réglée automatiquement par le réseau et ne peut plus être modifiée** (cahier des charges § 5.1) : vérifier avant l'installation que la tablette est à la bonne heure et sur le fuseau de Paris.

- **Si la commande refuse** (« there are already some accounts ») : un compte existe encore, réinitialiser la tablette.
- **Sortir du kiosque pour une maintenance** : espace administrateur > onglet Export > « Quitter le mode kiosque ». La barre d'état et l'écran d'accueil d'Android redeviennent accessibles (réglages, mises à jour) ; seul le réglage de la date et de l'heure reste interdit. Le kiosque se remet en place au prochain démarrage de l'application.
- **Rendre la tablette à un usage normal** (avant de désinstaller l'application) : même endroit, « Retirer définitivement ». Il faudra ensuite réinitialiser la tablette pour remettre le kiosque.

## 4. Première configuration

1. Espace administrateur (« Administration » en bas de l'accueil) : choisir le **mot de passe administrateur** (8 caractères minimum, connu d'une seule personne).
2. L'application affiche **une seule fois** le code de secours. Le noter sur papier, remis à la DGS, rangé en lieu sûr. Il permet de changer le mot de passe s'il est perdu, mais il ne donne accès à aucune donnée.
3. Créer les agents (onglet Agents) avec un code à 4 chiffres chacun.

## 5. Sauvegarde par e-mail

Onglet Export > « Configurer l'envoi ». Il faut un **compte d'envoi fourni par la mairie** (de préférence une adresse dédiée) :

| Champ | Valeur |
|---|---|
| Serveur, port, sécurité | ceux du fournisseur (SSL en 465 ou STARTTLS en 587) |
| Identifiant et mot de passe | ceux du compte d'envoi. Avec Gmail ou un compte à double authentification, créer un « mot de passe d'application » |
| Adresse de réception | celle qui reçoit les sauvegardes |
| Fréquence | chaque jour ou chaque semaine |
| **Mot de passe de sauvegarde** | choisi ici, 12 caractères minimum |

**Le mot de passe de sauvegarde est à noter hors de la tablette** (avec le code de secours). Il chiffre chaque sauvegarde : sans lui, impossible de restaurer sur une autre tablette. Il n'est jamais affiché de nouveau.

Cliquer ensuite sur **« Envoyer maintenant »** pour vérifier : un message de réussite doit s'afficher et l'e-mail doit arriver avec une pièce jointe `sauvegarde-badgeuse-AAAA-MM-JJ.bdgs`. En cas d'échec, la raison est affichée, et un bandeau rouge reste visible dans l'espace administrateur tant qu'une sauvegarde n'a pas réussi. Les pointages de plus de 4 mois ne sont supprimés qu'après un envoi réussi.

### Durée de conservation

Onglet Export > « Conservation des données » : les pointages **déjà sauvegardés par e-mail** sont effacés de la tablette au-delà de cette durée (4 mois par défaut, de 1 à 60 mois), avec leurs lignes de journal. Les sauvegardes reçues par la mairie les gardent. La durée est à fixer avec la DGS ; chaque changement apparaît dans l'onglet Journal, section « Événements de sécurité ».

## 6. Restaurer sur une tablette neuve

1. Installer l'application (étapes 2 et 3), créer le mot de passe administrateur (étape 4).
2. Copier le fichier `.bdgs` reçu par e-mail (ou une copie enregistrée depuis l'onglet Export) sur la tablette.
3. Espace administrateur > Export > « Restaurer une copie » > saisir le **mot de passe de sauvegarde** > choisir le fichier.

La restauration n'est acceptée que sur une application sans agent ni pointage. L'envoi par e-mail est à reconfigurer ensuite (le mot de passe du compte ne fait pas partie de la sauvegarde).

## À faire avant la livraison

- **Créer la clé de signature de la mairie** (section 2) : la version release est alors signée automatiquement. La clé de développement d'Android ne doit pas servir en production. Conserver la clé : sans elle, les mises à jour ne s'installent pas par-dessus.
- **Fixer la durée de conservation** avec la DGS (section 5).
- **Tester sur la vraie tablette** le mode kiosque, le redémarrage, l'envoi e-mail et la coupure de courant (T-37 à T-39), car ils n'ont pu être vérifiés que sur un émulateur ou un téléphone.
- La bibliothèque d'envoi (Jakarta Mail pour Android) est sous licence CDDL/GPL avec exception : mentionner sa licence dans la documentation remise à la mairie.
