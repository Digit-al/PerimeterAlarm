# AGENT.md — Instructions pour les agents IA

> Fichier de référence pour tout agent (HAL ou autre) qui travaille sur **Perimeter Alarm**.
> À lire **en premier** à chaque session, avant toute autre chose.

---

## 📌 Règle d'or

**Mantenez les 4 fichiers ci-dessous à jour à CHAQUE session significative.**
Ils sont la mémoire à long terme du projet — si la session expire (context exhaustion, crash, interruption), un agent frais ne pourra redémarrer que grâce à eux.

| Fichier | Rôle | À mettre à jour quand… |
|---------|------|------------------------|
| `README.md` | Documentation technique EN (architecture, features, setup) | Toute feature ajoutée/retirée, changement d'architecture, nouveau prérequis |
| `README.fr.md` | Documentation technique FR (miroir exact de README.md) | **À chaque modif de README.md** — les deux doivent rester synchrones |
| `WORK_LOG.md` | Journal chronologique de l'état du projet | **TOUTES les sessions** — c'est LE point de reprise |
| `PROMPT.md` | Prompt de reconstruction complète du projet from scratch | Toute feature ajoutée, changement de stack, de dépendances ou de modèle de données |

---

## 🔄 Protocole de session

### Au début de la session (REPRISE)

1. Lire `WORK_LOG.md` → section **"Current State"** pour situer l'état.
2. Lire `PROMPT.md` → comprendre la vision globale et les contraintes.
3. Parcourir le code source si le WORK_LOG ne suffit pas.

### Pendant la session

- Travailler normalement.
- Noter mentalement ce qui a changé (features, bugs fixés, décisions).

### À la fin de la session (AVANT de finir)

1. **WORK_LOG.md** — Mettre à jour :
   - La date et le commit hash dans la section "Current State".
   - Le statut du build.
   - Les cases `[x]` terminées, ajouter les nouvelles tâches dans "What's pending / next".
   - Ajouter une entrée datée dans le log détaillé (section "### YYYY-MM-DD").

2. **README.md** — Si une feature ou un aspect technique a changé, mettre à jour.
3. **README.fr.md** — Synchroniser avec le README.md (traduction exacte).
4. **PROMPT.md** — Mettre à jour uniquement si la stack, les dépendances ou le modèle de données ont évolué.

5. **Git commit** avec un message descriptif (convention `type: description`).

---

## 📐 Structure du WORK_LOG.md

```markdown
# Work Log — Perimeter Alarm

## Current State (last updated: YYYY-MM-DD)
**Branch**: ...
**Last commit**: `<hash>` — <message>
**Build**: ✅/❌ <détail>
**User testing**: <statut>

### What's done
- [x] ...

### What's pending / next
- [ ] ...

### Log
#### YYYY-MM-DD — <titre de la session>
- Ce qui a été fait
- Décisions prises (et pourquoi)
- Prochaines étapes immédiates
```

---

## ⚠️ Pièges à éviter

- **Oublier de synchroniser README.fr.md** avec README.md → divergence traduite = confusion.
- **Ne pas noter les décisions non-obvious** dans le WORK_LOG → l'agent suivant va re-faire le mauvais choix.
- **Oublier un commit** → l'état du projet dans Git ne correspond plus à la réalité.
- **Modifier PROMPT.md sans nécessité** → c'est un prompt de reconstruction, il doit refléter l'état "vivant" du projet, pas l'historique.

---

## 🎯 Objectif final

Un agent IA (ou humain) qui ouvre ce dépôt **froid** doit pouvoir :
1. Lire `WORK_LOG.md` → savoir où on en est.
2. Lire `PROMPT.md` → comprendre le projet complet.
3. Lancer un build → vérifier que ça compile.
4. Continuer le travail sans perdre 30 min à se re-contextualiser.
