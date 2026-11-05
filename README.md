<p align="center"><img src="src/main/resources/icon.png" width="96" alt=""></p>

# Younesh (Java)

Version Java de [Younesh](https://github.com/jonasmelheb/younesh), avec la même apparence : un terminal qui range les discussions dans une barre latérale, en dossiers colorés. Un seul fichier `.jar`, qui tourne partout où Java 17+ est installé (Windows, macOS, Linux).

![Younesh](docs/screenshot.png)

## Lancer

1. Télécharger `Younesh-x.y.z.jar` depuis les [Releases](https://github.com/jonasmelheb/younesh-java/releases).
2. `java -jar Younesh-x.y.z.jar` (ou double-clic si les `.jar` sont associés à Java).

Vérifier la version de Java : `java -version` (17 ou plus).

Si l'affichage d'un terminal est bizarre, lance Younesh avec la variable `YOUNESH_PTY_LOG=1` : ce que reçoit chaque terminal est copié dans `%APPDATA%\younesh-java\pty-<nom>.log`.

Younesh se met ensuite à jour tout seul : il télécharge la nouvelle version et remplace son `.jar` à la fermeture (case à décocher dans les Réglages). S'il n'y arrive pas, il signale la nouvelle version.

## Ce qu'il y a dedans

- **Discussions dans la barre latérale**, rangées en **dossiers colorés** (palette ou couleur personnalisée), repliables, avec glisser-déposer pour déplacer et réordonner. Un dossier coloré teinte en pâle tout ce qu'il contient.
- **Terminal rapide** : le bouton ›_ ouvre tout de suite un terminal dans le dossier personnel avec le shell par défaut.
- ▶ sur un dossier lance toutes ses discussions ; « Tout arrêter » les arrête.
- **Shells détectés** : PowerShell 7, Windows PowerShell, Invite de commandes, Git Bash, WSL (zsh/bash sur macOS). Vrai pseudo-terminal (ConPTY sous Windows) grâce à pty4j.
- **Sessions Claude Code** : le bouton ✳ Claude lance `claude` dans le répertoire choisi ; la conversation reprend au prochain lancement.
- **Claude attend** : la discussion passe en orange quand Claude se tait après avoir répondu (et sur la cloche du terminal). La notification Windows s'active dans les Réglages.
- `/rename` dans Claude Code renomme la discussion.
- **Sandbox sbx** (bouton ⧉) : créer une sandbox pour un projet en cochant ce qu'il faut y installer, ou **ouvrir une sandbox existante** (liste tirée de `sbx ls`, même celles créées dans un autre terminal). Clic droit sur une sandbox : rouvrir Claude, installer, supprimer. Comme la version Electron, la configuration est en trois couches (`cli`, `setup`, `installs`), reste sur le PC et s'importe ou s'exporte en JSON (⧉ → Configurer sbx). Variables : `{projet}`, `{dossier}`, `{script}`, et `{secret:NOM}`, demandé au lancement et jamais enregistré.
- **SSH et tunnels** (à activer dans les Réglages), comme la version Electron : le bouton serveur ajoute des connexions SSH dans la barre latérale ou les importe depuis `~/.ssh/config`. Younesh lance le client OpenSSH livré avec Windows : clés, agent SSH et `known_hosts` marchent comme d'habitude. Les **tunnels** (local `-L`, distant `-R`, proxy SOCKS `-D`) tournent en arrière-plan, chacun avec son interrupteur. Quand ssh demande un mot de passe, une phrase de passe ou de confirmer un nouveau serveur, Younesh pose la question dans une petite fenêtre. Un tunnel qui coupe se relance tout seul, et un tunnel peut s'ouvrir au démarrage. Aucun mot de passe n'est enregistré.
- **Liens** : les adresses web dans le terminal sont en bleu souligné ; un clic les ouvre dans le navigateur.
- **Branche git** sous le nom de chaque discussion, lue directement dans `.git`. Le chemin est dans la barre du haut.
- **Palette rapide** (Ctrl+Shift+P), recherche dans la barre latérale (sans accents).
- **Thèmes** Sombre, Minuit, Graphite, Clair ; police, taille, historique, shell et répertoire par défaut.
- Points d'état : vert = en cours, bleu = nouvelle sortie, rouge = terminé, gris = pas démarré.
- Tout est enregistré dans `%APPDATA%\younesh-java\younesh.json` (`~/.younesh-java` ailleurs).

Pas encore porté depuis la version Electron : côte à côte, restauration du contenu, import/export, effet Mica.

## Raccourcis

| Action | Touches |
| --- | --- |
| Palette rapide | Ctrl+Shift+P |
| Nouvelle discussion | Ctrl+Shift+T |
| Nouvelle session Claude | Ctrl+Shift+N |
| Terminal dans le dossier personnel | Ctrl+Shift+Q |
| Nouveau dossier | Ctrl+Shift+D |
| Discussion suivante / précédente | Ctrl+Tab / Ctrl+Shift+Tab |
| Fermer la discussion | Ctrl+Shift+W |
| Renommer | F2 |
| Aller à la discussion qui attend | Ctrl+Shift+U |
| Afficher / masquer la barre latérale | Ctrl+Shift+B |
| Copier / coller | Ctrl+C (avec une sélection) ou Ctrl+Maj+C / Ctrl+V |
| Nouvelle ligne dans Claude (même lancé à la main dans un shell) | Shift+Entrée |

Clic droit sur une discussion ou un dossier pour toutes les options, double-clic pour renommer, clic molette pour fermer.

## Développement

```bash
mvn package                 # construit target/Younesh.jar
java -jar target/Younesh.jar
```

Chaque tag `v*` construit le jar sur GitHub Actions et le publie dans une Release ([workflow](.github/workflows/build.yml)).

Construit avec Swing, [FlatLaf](https://www.formdev.com/flatlaf/), [JediTerm](https://github.com/JetBrains/jediterm) et [pty4j](https://github.com/JetBrains/pty4j).

## Licence

[MIT](LICENSE)

---

<p align="right"><em>Made by Y</em></p>
