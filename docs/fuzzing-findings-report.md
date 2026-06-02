# Rapport de Findings Fuzzing `app-passwords`

Ce document synthétise les findings issus des `16` campagnes de fuzzing réalisées contre l'app Ledger Passwords via la CLI du companion et Speculos.

Référence détaillée :

- [fuzzing-tracker.md](/home/sofian/Sources/ledger-passwords-companion/docs/fuzzing-tracker.md:1)

## Périmètre

Couvert par cette campagne :

- chemins APDU `GET_APP_INFO`, `GET_APP_CONFIG`, `DUMP_METADATAS`, `LOAD_METADATAS` ;
- navigation UI Speculos ;
- scénarios `push`, `pull`, `verify`, `diff`, `show`, `type`, `delete` ;
- builds `app-passwords` `1.3.0`, `1.3.1` et `master` sur certaines campagnes.

Non couvert directement :

- comportement firmware/hardware réel au-delà de ce que Speculos émule ;
- stack USB Android réelle ;
- preuve d'un factory reset matériel identique au vrai device.

## Niveau de confiance

- `Élevé` : crashs `signal 11`, timeouts, corruptions relues, mauvais item sélectionné sous Speculos, reproductibles sur plusieurs runs ou plusieurs fuzzers.
- `Moyen` : comportements vus dans un seul harness mais cohérents avec d'autres findings.
- `Faible` : comportements potentiellement pollués par le build de test Speculos.

## Synthèse exécutive

1. `app-passwords` peut crasher sur des metadata valides, sans corruption préalable injectée par le companion.
2. Les zones les plus fragiles sont la gestion de listes, la sélection d'item, et certains enchaînements APDU/UI.
3. Des metadata malformées mais acceptées peuvent ensuite faire crasher les flows `show` et `delete`.
4. La couche APDU seule peut produire des corruptions silencieuses et des hangs.
5. Le companion ne peut pas "corriger" ces bugs côté Ledger app, mais il peut réduire fortement la surface de risque avant `push` réel.

## Cas qui font crasher l'app

### 1. Reproducer minimal valide : `["alpha", "beta"] -> push -> show second`

- `sévérité`: critique
- `confiance`: élevée
- `fuzzers`: `FZ-04`, `FZ-06`, `FZ-07`, `FZ-15`
- `signature`:
  - la CLI voit `Remote end closed connection without response`
  - Speculos rapporte `The app crashed with signal 11`

Conclusion :

- ce cas est le meilleur reproducer minimal actuel ;
- il prouve qu'un état sémantiquement valide peut suffire à casser l'app.

### 2. Listes denses / largeurs mixtes

- `sévérité`: critique
- `confiance`: élevée
- `fuzzers`: `FZ-09`, `FZ-13`

Cas confirmés :

- `dense_twelve_show_delete_last`
- `reindex_delete_first_then_last`
- `mixed_widths_show_first_middle_last`
- `dense_twelve_push_prompt_spam_show_last`

Conclusion :

- la logique liste/rendu/indexation est fragile ;
- le crash ne dépend pas uniquement d'Unicode exotique ou de metadata corrompues.

### 3. Unicode mixte

- `sévérité`: critique
- `confiance`: élevée
- `fuzzers`: `FZ-11`

Cas confirmé :

- `mixed_unicode_show_all` crashe l'app avec `signal 11`.

Conclusion :

- les confusables Unicode ne provoquent pas seulement de mauvais choix d'item ;
- certains corpus Unicode mixtes font aussi tomber le process.

### 4. Metadata malformée mais acceptée : `second_len_plus1`

- `sévérité`: critique
- `confiance`: élevée
- `fuzzers`: `FZ-02`, `FZ-07`, `FZ-15`

Comportement :

- `LOAD_METADATAS` est accepté ;
- `pull` relit silencieusement un nickname corrompu `gmail\0` ;
- `show` et `delete` du second item font ensuite crasher l'app.

Conclusion :

- l'app ne valide pas assez strictement ce raw en entrée ;
- la traversée ou la sélection suivantes ne savent plus gérer l'état obtenu.

### 5. Navigation chaotique sur état valide

- `sévérité`: élevée
- `confiance`: moyenne
- `fuzzers`: `FZ-05`

Cas confirmé :

- `single_entry_walk_seed21` fait crasher l'app après navigation pseudo-aléatoire sur un état valide contenant `["sofian terki"]`.

Conclusion :

- il existe au moins une fragilité de machine d'état UI indépendante des seuls corpus `alpha/beta`.

## Cas dangereux sans crash immédiat

### 1. Mauvais item supprimé ou affiché

- `sévérité`: élevée
- `confiance`: élevée
- `fuzzers`: `FZ-09`, `FZ-11`

Cas confirmés :

- `sameprefix_19bytes_delete_third` supprime le mauvais item ;
- `sameprefix_19bytes_type_last_delete_second` supprime le mauvais item après `type` ;
- `nfc_nfd_delete_second`, `nbsp_delete_second`, `zero_width_delete_second`, `bidi_delete_second` retirent `plain` au lieu du second item demandé.

Conclusion :

- la sélection par position n'est pas fiable sur certains corpus proches visuellement ou textuellement ;
- ce point est dangereux même sans crash, car il peut provoquer une suppression inattendue côté device.

### 2. Corruptions silencieuses via APDU

- `sévérité`: élevée
- `confiance`: élevée
- `fuzzers`: `FZ-03`

Cas confirmés :

- `load_partial_prefix_abandon` persiste `load-alph` ;
- `load_zero_length_nonfinal_then_valid_final` persiste aussi `load-alph` ;
- `load_valid_final_then_extra_nonfinal` vide le vault ;
- `load_out_of_order_two_chunk` et `load_duplicate_first_chunk_then_final_remainder` relisent `bulk-0`, `bulk-0`.

Conclusion :

- le protocole `LOAD_METADATAS` n'est pas robustement défensif ;
- une séquence interrompue ou incohérente peut laisser un état persistant invalide.

### 3. Hangs APDU et écrans bloqués

- `sévérité`: élevée
- `confiance`: élevée
- `fuzzers`: `FZ-03`, `FZ-06`, `FZ-13`

Cas confirmés :

- `dump_bad_p1_payload_then_pull`
- `dump_partial_then_info_then_pull`
- `sofian_verify_prompt_doubletap`
- `multi_concurrent_reads_seed65`

Conclusion :

- certains flows laissent l'app bloquée sur `Transfer metadatas ?` ;
- l'état APDU/transport devient alors non fiable jusqu'au timeout.

## Cas qui ne reproduisent pas le problème

### 1. `sofian terki` seul

- `sévérité`: contrôle
- `confiance`: élevée
- `fuzzers`: `FZ-08`, `FZ-14`, `FZ-15`

Constat :

- `sofian terki` est stable en round-trip ;
- `sofian_push_show_control` et `sofian_push_verify_show_control` passent ;
- le simple fait d'avoir un espace dans le nickname n'explique donc pas, à lui seul, le reset vu sur vrai device.

### 2. Charset engine

- `sévérité`: contrôle
- `confiance`: élevée
- `fuzzers`: `FZ-10`

Constat :

- pas de crash sur la campagne orientée charsets ;
- les vecteurs connus restent cohérents.

Conclusion :

- les bugs confirmés ne se concentrent pas dans le moteur de génération de password.

## Vérification spécifique : les espaces sont-ils permis ?

Oui, le source de `app-passwords` indique que les espaces sont supportés.

Éléments concrets :

- la création rejette un nickname vide ou dupliqué, pas les espaces, dans [ui_passwords.c](/home/sofian/Sources/ledger-passwords-companion/build/speculos/app-passwords/src/ui/ui_passwords.c:274) ;
- le clavier n'est pas limité à des lettres seules dans [ui_passwords.c](/home/sofian/Sources/ledger-passwords-companion/build/speculos/app-passwords/src/ui/ui_passwords.c:302) ;
- le nickname est copié tel quel dans [password.c](/home/sofian/Sources/ledger-passwords-companion/build/speculos/app-passwords/src/password.c:7) ;
- la vraie contrainte visible est la taille, avec `MAX_METANAME = 20` dans [types.h](/home/sofian/Sources/ledger-passwords-companion/build/speculos/app-passwords/src/types.h:8), soit `19` octets UTF-8 utiles pour le nickname.

Conclusion :

- l'espace n'est pas un caractère interdit ;
- le companion ne doit pas le traiter comme un cas intrinsèquement invalide.

## Ce qui paraît être la cause

À ce stade, le meilleur diagnostic est un cluster de bugs côté Ledger app :

1. bug de gestion des listes et de la sélection d'item ;
2. validation insuffisante de certaines metadata en entrée ;
3. robustesse insuffisante du protocole `LOAD/DUMP` aux séquences anormales ;
4. fragilité d'état quand transport APDU et UI interagissent en même temps.

Le source contient plusieurs points suspects, sans preuve causale unique à lui seul :

- traversée metadata basée sur des longueurs stockées dans [metadata.c](/home/sofian/Sources/ledger-passwords-companion/build/speculos/app-passwords/src/metadata.c:56) ;
- calculs de longueur et bornes d'affichage dans [metadata.h](/home/sofian/Sources/ledger-passwords-companion/build/speculos/app-passwords/src/metadata.h:9) ;
- construction de la liste UI dans [ui_passwords.c](/home/sofian/Sources/ledger-passwords-companion/build/speculos/app-passwords/src/ui/ui_passwords.c:202) ;
- accumulation dans le buffer de liste dans [password_list.c](/home/sofian/Sources/ledger-passwords-companion/build/speculos/app-passwords/src/ui/password_list.c:61).

## Limites et artefacts de test

Le cluster `restart -> password1/password2/password3` de `FZ-12` ne doit pas être interprété brut comme un bug produit.

Notre build Speculos active :

- `TESTING=1`
- `POPULATE=1`

dans [build-passwords-app.sh](/home/sofian/Sources/ledger-passwords-companion/scripts/build-passwords-app.sh:11).

Et le source de test injecte bien ces entrées dans [app_main.c](/home/sofian/Sources/ledger-passwords-companion/build/speculos/app-passwords/src/app_main.c:44).

Conclusion :

- les findings de pollution après restart sont utiles pour le harness ;
- mais ils doivent être rejoués sans `POPULATE=1` avant d'être traités comme bug produit.

## Conséquence pour le companion

Le companion ne peut pas réparer `app-passwords`, mais il peut :

- réduire la surface de risque avant `push` réel ;
- refuser les états bruts suspects ;
- avertir sur les corpus à haut risque ;
- garder `push` et `verify` séparés ;
- concentrer les campagnes de stress sur Speculos avant toute écriture hardware.

Le plan concret est documenté dans :

- [companion-mitigation-plan.md](/home/sofian/Sources/ledger-passwords-companion/docs/companion-mitigation-plan.md:1)
