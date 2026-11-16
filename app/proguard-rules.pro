# Règles de conservation pour la version finale (R8).
#
# Jakarta Mail pour Android (ADR-002) : la bibliothèque retrouve ses propres classes par leur NOM,
# d'après les fichiers META-INF/javamail.*.providers et META-INF/mailcap (protocole smtp, types de
# contenu des messages). R8 ne voit pas ces usages : sans ces règles, il renommerait ou supprimerait
# ces classes et l'envoi échouerait uniquement dans la version finale.
-keep class com.sun.mail.** { *; }
-keep class javax.mail.** { *; }
-keep class javax.activation.** { *; }
