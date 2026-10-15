# apk

Export partiel, volontaire, des sources Kotlin (`.kt` uniquement) de l'app OP15 Assistant.

**Ce depot n'est PAS le projet complet.** Il contient seulement les fichiers `.kt` pour
relecture/partage rapide. Il manque deliberement :

- le Gradle wrapper, `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties` ;
- `AndroidManifest.xml` et les ressources (`res/`, layouts, strings) ;
- les binaires natifs (dont Z3 JNI, present et fonctionnel dans le vrai projet) ;
- tout ce qui n'est pas du Kotlin source.

Si vous auditez la securite ou la completude du build de ce projet, **ne vous basez pas
sur ce depot** : il apparaitra toujours comme "projet Android incomplet" ou "librairie
native manquante" puisque ces fichiers ne sont pas exportes ici par choix, pas par oubli.

Le vrai projet, buildable (`./gradlew assembleDebug` fonctionne, APK installee et testee
en direct sur le device cible), est dans **[gat45/op15-assistant](https://github.com/gat45/op15-assistant)**.

Objectif de ce depot : partage/relecture rapide du code source, pas distribution ou audit
de build.
