# Fuzzing Tracker

Ce document suit les familles de fuzzers à implémenter contre l'app Ledger Passwords via la CLI et Speculos.

Objectif :

- trouver les crashs applicatifs ;
- trouver les hangs, timeouts et erreurs APDU ;
- détecter les corruptions de metadata ;
- identifier les scénarios qui pourraient correspondre à un reset ou à un comportement dangereux sur vrai device.

Règle de travail :

1. implémenter un fuzzer ;
2. lancer ses tests ;
3. mettre à jour ce fichier ;
4. passer au suivant.

## Légende

- `pending` : pas commencé
- `in_progress` : en cours d'implémentation ou d'investigation
- `done` : implémenté et validé
- `blocked` : dépendance ou limite externe

## Oracles communs

Les fuzzers peuvent réutiliser un ou plusieurs oracles communs :

- crash du process Speculos ;
- sortie APDU non `0x9000` là où le scénario attend un succès ;
- timeout lecture/écriture ;
- écran inattendu ;
- écran figé ;
- divergence `push -> dump -> decode` ;
- corruption de metadata relue ;
- exception levée par la CLI ou le harness ;
- redémarrage apparent de l'app dans Speculos ;
- sortie hors du flux UI attendu.

## Ordre d'implémentation recommandé

1. `FZ-14` corpus ciblé dangerous nicknames
2. `FZ-04` stateful de scénarios métier
3. `FZ-02` mutation de metadata valides
4. `FZ-05` UI par navigation bouton
5. `FZ-10` charset-oriented
6. `FZ-08` round-trip
7. `FZ-12` persistance multi-session
8. `FZ-03` APDU bas niveau
9. `FZ-06` chaos timing
10. `FZ-11` Unicode/normalisation
11. `FZ-09` listes/menus
12. `FZ-13` prompts APDU + UI mêlés
13. `FZ-01` metadata génératives
14. `FZ-07` différentiel
15. `FZ-15` régression orientée incident
16. `FZ-16` oracles multiples consolidés

## Backlog détaillé

### [x] FZ-01 Metadata génératives

- `status`: `done`
- `scope`: génération de `backup.json` et de raw metadata synthétiques
- `focus`:
  - nicknames vides, courts, longs, 19 octets exacts, 20+ octets
  - UTF-8 multi-octets
  - espaces, tabs, newlines, caractères invisibles
  - volumes proches de `storage_size`
- `entrypoint`: CLI `device push/pull/verify` via Speculos
- `oracles`:
  - APDU error
  - timeout
  - crash
  - dump corrompu après push
- `notes`:
  - implémenté dans `scripts/fuzz-generated-metadata.py`
  - synthèse de corpus `backup.json` valides et de raw metadata synthétiques sans passer par les garde-fous du companion
  - campagnes validées:
    - `empty_control_pull`
    - `json_maxcount_177_pull`
    - `raw_storage_edge_186_show_last`
    - `raw_overlong_20byte_show`
    - `raw_blank_plain_space_show_second`
  - findings confirmés:
    - `empty_control_pull` passe sans dérive
    - `json_maxcount_177_pull` accepte un corpus JSON dense de `177` entrées, mais le `pull` final relit seulement `11` entrées, avec la dernière déjà corrompue en `vm010-xxxxxxxxxxx\\0\\0`
    - `raw_storage_edge_186_show_last` et `raw_overlong_20byte_show` sont rejetés proprement pendant `LOAD_METADATAS` à l'offset `4080` avec `sw=0x6f10`
    - `raw_blank_plain_space_show_second` ne crashe pas, mais `show` sur la position `2` sélectionne l'entrée vide au lieu de `plain-target`
  - conclusion:
    - l'app rejette correctement certains dépassements raw proches de la limite de stockage
    - en revanche, les gros corpus JSON et les listes contenant vide + espace révèlent encore une corruption/troncature et une mauvaise sélection d'item

### [x] FZ-02 Mutation de metadata valides

- `status`: `done`
- `scope`: partir d'un raw valide puis muter les octets
- `focus`:
  - `length` faux
  - type/kind incohérent
  - charset mask étrange
  - entrée tronquée
  - fin de buffer cassée
  - padding non nul
- `entrypoint`: APDU `LOAD_METADATAS` puis scénarios UI/CLI de lecture
- `oracles`:
  - crash
  - hang
  - corruption relue
  - comportement anormal sur `show/type/delete`
- `notes`:
  - implémenté dans `scripts/fuzz-metadata-mutations.py`
  - injection du raw muté via APDU `LOAD_METADATAS`, puis vérifications CLI/UI via Speculos
  - cas explicitement testés:
    - `first_len_plus4`
    - `first_charset_ff`
    - `second_kind_unknown`
    - `second_len_plus1`
    - `second_len_overflow`
    - `terminator_removed_tail_ff`
    - `padding_non_zero_after_terminator`
  - findings confirmés:
    - `first_len_plus4`, `second_kind_unknown`, `second_len_overflow` et `terminator_removed_tail_ff` sont rejetés pendant `LOAD_METADATAS` avec `sw=0x6f10`
    - `first_charset_ff` et `padding_non_zero_after_terminator` sont acceptés sans crash ni corruption relue
    - `second_len_plus1` est accepté, puis `device pull` relit silencieusement un nickname corrompu `gmail\\0`
    - sur ce même seed `second_len_plus1`, `type password` du second item réussit, mais `show password` et `delete password` du second item font crasher `app-passwords` sous Speculos avec `signal 11`
  - ce seed `second_len_plus1` doit être conservé comme cas de régression prioritaire

### [x] FZ-03 APDU bas niveau

- `status`: `done`
- `scope`: fuzz direct du protocole `GET_APP_CONFIG`, `DUMP_METADATAS`, `LOAD_METADATAS`
- `focus`:
  - chunks invalides
  - tailles hors contrat
  - séquences `p1` incohérentes
  - payloads trop courts / trop longs
- `entrypoint`: harness APDU bas niveau au-dessus de `SpeculosTransport`
- `oracles`:
  - status words inattendus
  - deadlocks
  - crash
- `notes`:
  - implémenté dans `scripts/fuzz-low-level-apdu.py`
  - s'appuie sur l'échange direct socket/APDU ajouté au harness partagé `scripts/speculos_fuzz_lib.py`
  - campagnes validées:
    - `load_zero_length_nonfinal_then_valid_final`
    - `load_partial_prefix_abandon`
    - `load_valid_final_then_extra_nonfinal`
    - `load_out_of_order_two_chunk`
    - `load_duplicate_first_chunk_then_final_remainder`
    - `dump_bad_p1_payload_then_pull`
    - `dump_partial_then_info_then_pull`
  - findings confirmés:
    - `load_partial_prefix_abandon` persiste silencieusement un état partiel relu comme `load-alph`
    - `load_zero_length_nonfinal_then_valid_final` persiste aussi `load-alph` au lieu de `load-alpha` / `load-beta`
    - `load_valid_final_then_extra_nonfinal` vide complètement le vault relu
    - `load_out_of_order_two_chunk` et `load_duplicate_first_chunk_then_final_remainder` n'échouent pas proprement et relisent un état corrompu `bulk-0`, `bulk-0`
    - `dump_bad_p1_payload_then_pull` et `dump_partial_then_info_then_pull` finissent tous deux en hang/timeout avec l'écran bloqué sur `Transfer metadatas ?`
  - conclusion:
    - la couche APDU seule suffit à casser l'état de l'app sans passer par l'UI
    - les problèmes sont ici des corruptions silencieuses et des deadlocks de dump, pas seulement des crashs d'écran ou de sélection de liste

### [x] FZ-04 Stateful de scénarios métier

- `status`: `done`
- `scope`: séquences réalistes ou semi-réalistes de haut niveau
- `focus`:
  - `push -> show password`
  - `push -> type password`
  - `push -> delete`
  - `push -> pull -> verify`
  - `load invalid -> list -> show`
- `entrypoint`: CLI + automation boutons Speculos
- `oracles`:
  - crash
  - timeout
  - divergence entre état poussé et état relu
- `notes`:
  - implémenté dans `scripts/fuzz-stateful-scenarios.py`
  - harness durci pour utiliser `speculos-auto-approve.sh`, des ports dynamiques par scénario, et une navigation `home_to_menu()` tolérante aux retours UI intermédiaires
  - scénarios validés:
    - `sofian_push_show_pull`
    - `sofian_push_type_pull`
    - `sofian_push_delete_pull`
    - `leading_space_push_show_pull`
    - `leading_space_push_type_pull`
    - `multi_type_second_delete_first_pull`
    - `multi_push_verify_pull`
  - finding confirmé:
    - le seed `["alpha", "beta"]` dans `multi_show_second_pull` provoque un crash reproductible de `app-passwords` sous Speculos pendant `device push`
    - signature observée: `Remote end closed connection without response` côté CLI, puis `The app crashed with signal 11` dans le log Speculos
  - ce seed doit être conservé comme cas de régression pour les prochains fuzzers

### [x] FZ-05 UI par navigation bouton

- `status`: `done`
- `scope`: appuis `left/right/both` pseudo-aléatoires mais structurés
- `focus`:
  - navigation chaotique
  - validation/rejet rapides
  - changement d'écran inattendu
- `entrypoint`: API boutons Speculos
- `oracles`:
  - écran figé
  - crash
  - retour home anormal
- `notes`:
  - implémenté dans `scripts/fuzz-ui-navigation.py`
  - le runner prépare l'état via CLI/Speculos ou injection raw, exécute une marche pseudo-aléatoire sur les boutons, puis termine par `device info` et `device pull`
  - cas explicitement testés:
    - `empty_home_walk_seed11`
    - `single_entry_walk_seed21`
    - `leading_space_walk_seed22`
    - `multi_entry_walk_seed31`
    - `mutated_second_len_plus1_walk_seed41`
  - findings confirmés:
    - `single_entry_walk_seed21` fait crasher `app-passwords` sous Speculos après une navigation chaotique sur un état valide contenant `["sofian terki"]`
    - signature observée: `Remote end closed connection without response` côté CLI, puis `The app crashed with signal 11` dans le log Speculos
    - les marches chaotiques sur état vide et sur vault multi-entrée peuvent créer de nouveaux identifiants via l'UI seule, par exemple `["A", "password1", "password2", "password3"]` et `["A", "github", "gmail", "proton"]`
    - le seed corrompu `second_len_plus1` survit aussi à la navigation chaotique et laisse un état relu anormal `["00", "github", "gmail\\0"]`
  - le seed `single_entry_walk_seed21` doit être conservé comme cas de régression pour les fuzzers suivants

### [x] FZ-06 Chaos timing

- `status`: `done`
- `scope`: variation des délais entre APDU et boutons
- `focus`:
  - micro-délais aléatoires
  - rafales de requêtes
  - enchaînement write puis read immédiat
- `entrypoint`: CLI + automation Speculos
- `oracles`:
  - timeout
  - réponse incomplète
  - état UI/APDU bloqué
- `notes`:
  - implémenté dans `scripts/fuzz-chaos-timing.py`
  - ce runner mélange deux familles de stress:
    - approbation manuelle jitterée des prompts `Overwrite metadatas` et `Approve`
    - rafales de commandes CLI avec micro-délais et concurrence de lectures
  - cas explicitement testés:
    - `sofian_manual_push_show_seed61`
    - `sofian_push_verify_pull_burst_seed62`
    - `alpha_beta_manual_push_show_second_seed63`
    - `leading_space_manual_push_delete_seed64`
    - `multi_concurrent_reads_seed65`
  - findings confirmés:
    - `alpha_beta_manual_push_show_second_seed63` fait encore crasher `app-passwords` sous Speculos avec `Remote end closed connection without response`, puis `The app crashed with signal 11`
    - ce crash reste reproductible même avec approbation jitterée des prompts, donc il n'est pas lié à un timing trop "propre" du harness
    - `multi_concurrent_reads_seed65` provoque de façon reproductible un timeout côté CLI sur `device info` quand plusieurs lectures (`info`, `pull`, `verify`, `pull`) sont lancées avec de faibles décalages
    - dans ce cas concurrent, l'app ne crashe pas sous Speculos, mais l'état APDU/transport devient non fiable et une commande reste bloquée jusqu'au timeout
  - les cas `sofian_manual_push_show_seed61`, `sofian_push_verify_pull_burst_seed62` et `leading_space_manual_push_delete_seed64` passent avec conservation de l'état attendu

### [x] FZ-07 Différentiel

- `status`: `done`
- `scope`: rejouer le même corpus sur plusieurs versions de `app-passwords`
- `focus`:
  - 1.3.0 vs 1.3.1 vs HEAD
  - divergence de crash, output, prompts
- `entrypoint`: build multi-version + Speculos
- `oracles`:
  - mismatch comportemental
  - régression entre versions
- `notes`:
  - implémenté dans `scripts/fuzz-differential.py`
  - versions comparées:
    - `nanos_plus_1_3_0` via `nanos+_1.6.0_1.3.0_sdk_v26.0.2`
    - `nanos_plus_1_3_1` via `nanos+_1.6.1_1.3.1_sdk_v26.1.7`
    - `master`
  - corpus rejoué:
    - `sofian_show_first`
    - `leading_space_delete_first`
    - `alpha_beta_show_second`
    - `second_len_plus1_show_second`
  - findings confirmés:
    - `leading_space_delete_first` passe sur `1.3.0`, `1.3.1` et `master`
    - `sofian_show_first` passe sur `1.3.1` et `master`
    - `alpha_beta_show_second` et `second_len_plus1_show_second` font crasher `app-passwords` sur `1.3.1` et `master` avec `Remote end closed connection without response`, puis `The app crashed with signal 11`
    - sur `1.3.0`, les cas qui supposent l'accès direct à `Passwords list` ne sont pas comparables avec le même harness: l'automation tombe sur `Create password` puis timeoute
  - interprétation:
    - la campagne confirme une divergence réelle de flux UI/menu entre `1.3.0` et `1.3.1+`
    - les crashs `alpha/beta` et `second_len_plus1` restent présents au moins sur `1.3.1` et `master`
    - un premier échec transitoire `master/sofian_show_first` a été infirmé par une repro ciblée verte; il ne doit pas être compté comme finding

### [x] FZ-08 Round-trip

- `status`: `done`
- `scope`: vérifier les invariants `encode -> load -> dump -> decode`
- `focus`:
  - conservation des nicknames
  - conservation des charsets
  - stabilité sur cycles répétés
- `entrypoint`: CLI et codec local
- `oracles`:
  - divergence byte-level
  - divergence sémantique
- `implementation`:
  - harness partagé : `scripts/speculos_fuzz_lib.py`
  - fuzzer : `scripts/fuzz-roundtrip.py`
- `test status`:
  - smoke validé sur `sofian_space_three_cycles` et `embedded_raw_preferred_three_cycles`
  - run complet validé sur `5` corpus pendant `3` cycles chacun
- `findings`:
  - aucun crash ni dérive observés sur cette campagne
  - `sofian terki` reste stable sur `3` cycles `push -> verify -> pull`
  - le corpus multi-entrée `github/gmail/proton` conserve exactement nicknames, charsets et bytes bruts
  - le nickname UTF-8 `éééééééééa` à exactement `19` octets reste stable sur `3` cycles
  - un `backup.json` avec `raw_metadatas` embarqué reste piloté par le raw: `parsed-loses` est ignoré au profit de `raw-wins`, sans dérive après `3` cycles
- `notes`: bon filet de sécurité pour les régressions ; les seeds instables restent à chercher dans les fuzzers UI/stateful, pas dans le round-trip pur

### [x] FZ-09 Listes et menus

- `status`: `done`
- `scope`: stress des listes de passwords et de la navigation de menu
- `focus`:
  - beaucoup d'entrées
  - noms très proches
  - largeurs UI extrêmes
  - premiers / derniers index
- `entrypoint`: push corpus puis navigation UI
- `oracles`:
  - crash lors du rendu
  - mauvais item sélectionné
  - écran incohérent
- `implementation`:
  - harness partagé : `scripts/speculos_fuzz_lib.py`
  - fuzzer : `scripts/fuzz-list-menus.py`
- `test status`:
  - smoke ciblé validé sur le harness, puis campagne complète exécutée sur `5` cas
  - amélioration du harness `home_to_menu()` pour revenir proprement au menu depuis les écrans `show password` et `PASSWORD HAS BEEN WRITTEN`
- `findings`:
  - `dense_twelve_show_delete_last`: un corpus dense de `12` entrées ASCII (`slot-01` à `slot-12`) fait crasher `app-passwords` avec `signal 11` juste après `LOAD_METADATAS`
  - `sameprefix_19bytes_delete_third`: sur `sameprefixvalue-001..005`, la suppression demandée en position `3` retire en réalité le dernier item `sameprefixvalue-005`
  - `sameprefix_19bytes_type_last_delete_second`: après un `type` du dernier item, la suppression demandée en position `2` retire en réalité `sameprefixvalue-103` au lieu de `sameprefixvalue-102`
  - `reindex_delete_first_then_last`: un corpus `reindex-01..06` fait crasher `app-passwords` avec `signal 11`
  - `mixed_widths_show_first_middle_last`: un corpus mixant `a`, `medium-name`, un nickname UTF-8 à `19` octets et deux entrées longues ASCII fait aussi crasher `app-passwords` avec `signal 11`
- `notes`: campagne extrêmement rentable ; elle révèle à la fois des crashs de rendu/liste et des sélections d'item incohérentes dans les flows `Delete`

### [x] FZ-10 Charset-oriented

- `status`: `done`
- `scope`: exploration ciblée des bitmasks de charset
- `focus`:
  - `0x00`, `0xFF`
  - bits isolés
  - combinaisons rares
  - masks invalides injectés via mutation
- `entrypoint`: générateur de corpus + `show/type password`
- `oracles`:
  - crash génération
  - output invalide
  - comportement incohérent
- `implementation`:
  - harness partagé : `scripts/speculos_fuzz_lib.py`
  - fuzzer : `scripts/fuzz-charset-oriented.py`
- `test status`:
  - smoke validé sur `official_vectors_gmail_and_alias` et `raw_mask00_alias_allsets`
  - campagne complète validée sur `8` cas sans failure
- `findings`:
  - les vecteurs officiels `app-passwords` sont respectés exactement pour `gmail` sur `0x01`, `0x03`, `0x07`, `0x0F`, `0x1F`, `0x3F`, `0x7F`, `0xFF`
  - le bitmask `0x00` est bien traité comme alias de `ALL_SETS` dans le chemin de génération: il produit exactement le même mot de passe que `0xFF`
  - sur `raw_mask00_alias_allsets`, le `pull` re-canonise le charset en `ALL_SETS` côté JSON tout en conservant le byte raw `0x00`
  - les masks singleton se comportent comme attendu: `0x08` génère `--------------------`, `0x10` génère `____________________`, `0x20` génère `20` espaces
  - les masks `0x40`, `0x80` et le combo rare `0x81` ne craschent pas et restent cohérents en `show/type/pull`
- `notes`: aucune régression trouvée sur cette campagne ; les problèmes confirmés restent concentrés sur la logique de listes/sélection plutôt que sur le moteur de génération charset

### [x] FZ-11 Unicode et normalisation

- `status`: `done`
- `scope`: nicknames Unicode piégeux
- `focus`:
  - NFC/NFD
  - combining marks
  - bidi
  - zero-width
  - séparateurs Unicode
- `entrypoint`: push via CLI, puis lecture UI
- `oracles`:
  - crash
  - mismatch affichage / stockage
  - comparaison cassée
- `implementation`:
  - harness partagé : `scripts/speculos_fuzz_lib.py`
  - fuzzer : `scripts/fuzz-unicode-normalization.py`
- `test status`:
  - smoke validé sur `nfc_nfd_delete_second` et `zero_width_delete_second`
  - campagne complète validée sur `5` cas, avec `5` failures reproductibles
- `findings`:
  - `nfc_nfd_delete_second`: en demandant la suppression du `2e` item sur `["é", "é", "plain"]`, l'app retire en réalité `plain` et laisse l'entrée NFD `é`
  - `nbsp_delete_second`: en demandant la suppression du `2e` item sur `["foo bar", "foo\\u00a0bar", "plain"]`, l'app retire en réalité `plain` et laisse l'entrée `NBSP`
  - `zero_width_delete_second`: en demandant la suppression du `2e` item sur `["zerowidth", "zero\\u200bwidth", "plain"]`, l'app retire en réalité `plain` et laisse l'entrée avec zéro-width
  - `bidi_delete_second`: en demandant la suppression du `2e` item sur `["abc123", "abc\\u202e123", "plain"]`, l'app retire en réalité `plain` et laisse l'entrée bidi
  - `mixed_unicode_show_all`: un corpus mixte Unicode fait crasher `app-passwords` sous Speculos avec `Remote end closed connection without response`, puis `The app crashed with signal 11`
- `notes`: les confusables Unicode aggravent le même drift de sélection déjà vu en `FZ-09`, avec en plus un crash confirmé sur corpus Unicode mixte

### [x] FZ-12 Persistance multi-session

- `status`: `done`
- `scope`: écrire un état, redémarrer l'app, relire
- `focus`:
  - `push -> restart -> show`
  - `push -> restart -> type`
  - `push -> restart -> list`
- `entrypoint`: Speculos relancé entre les étapes
- `oracles`:
  - crash après reboot
  - perte/corruption de metadata
- `implementation`:
  - wrapper Speculos persisté : `scripts/run-speculos-passwords.sh`
  - harness partagé : `scripts/speculos_fuzz_lib.py`
  - fuzzer : `scripts/fuzz-multi-session-persistence.py`
- `test status`:
  - smoke validé sur `sofian_restart_list` et `alpha_beta_restart_show_second`
  - campagne complète validée sur `6` cas, avec `6` failures reproductibles
- `findings`:
  - `sofian_restart_list`, `sofian_restart_show_first`, `sofian_restart_type_first`: après restart, le dump relit `["sofian terki", "password1", "password2", "password3"]` au lieu de `["sofian terki"]`
  - `alpha_beta_restart_show_second`: après restart, le dump relit `["alpha", "beta", "password1", "password2", "password3"]` et un `show` en position `2` affiche `password1` au lieu de `beta`
  - `dense_twelve_restart_show_last`: une liste dense de `12` entrées ASCII crashe `app-passwords` après restart avec `Remote end closed connection without response`, puis `The app crashed with signal 11`
  - `second_len_plus1_restart_show_second`: le raw corrompu accepté survit au restart, mais le dump relit `["github", "gmail\\0", "password1", "password2", "password3"]` et un `show` en position `2` affiche `password1`
- `notes`: le restart Speculos avec NVRAM persistée révèle une pollution systématique par `password1/password2/password3`, qui se combine ensuite avec les bugs de sélection déjà vus sur les listes

### [x] FZ-13 Prompts APDU + UI mêlés

- `status`: `done`
- `scope`: injecter des appuis pendant des flows APDU
- `focus`:
  - validation/rejet rapide
  - navigation inattendue pendant prompt
  - sortie/reentrée pendant transfert
- `entrypoint`: CLI async + API boutons Speculos
- `oracles`:
  - blocage
  - état incohérent
  - crash
- `implementation`:
  - harness partagé : `scripts/speculos_fuzz_lib.py`
  - fuzzer : `scripts/fuzz-apdu-ui-interleaved.py`
- `test status`:
  - smoke validé sur `sofian_push_prompt_bounce_show` et `alpha_beta_push_prompt_spam_show_second`
  - campagne complète validée sur `5` cas, avec `3` failures reproductibles
- `findings`:
  - `sofian_verify_prompt_doubletap`: un `verify` après push normal timeoute en lecture si on injecte des appuis parasites autour de `Approve`; la CLI échoue avec `error: Read timed out` et l'écran reste bloqué sur `Transfer metadatas ?`
  - `alpha_beta_push_prompt_spam_show_second`: un `push` valide de `["alpha", "beta"]` crashe `app-passwords` pendant le transfert quand on mélange APDU et boutons sur les prompts, avec `Remote end closed connection without response`, puis `The app crashed with signal 11`
  - `dense_twelve_push_prompt_spam_show_last`: même crash `signal 11` sur une liste dense de `12` entrées si on spamme les prompts pendant le `push`
  - `sofian_push_prompt_bounce_show` reste stable malgré les appuis parasites
  - `leading_space_push_prompt_spam_type` reste aussi stable sur cette campagne
- `notes`: le mélange APDU/UI ne casse pas tous les cas simples, mais il suffit à transformer des seeds déjà fragiles en crashs pendant le `push` et à bloquer un `verify` pourtant valide

### [x] FZ-14 Corpus ciblé dangerous nicknames

- `status`: `done`
- `scope`: petit corpus manuel à forte valeur
- `focus`:
  - `sofian terki`
  - espaces début/fin
  - apostrophe, backtick, slash, backslash
  - accents
  - 19 octets exacts
  - collisions visuelles
- `entrypoint`: push minimal puis `show/type/delete`
- `oracles`:
  - crash
  - corruption
  - divergence de rendu
- `implementation`:
  - harness partagé : `scripts/speculos_fuzz_lib.py`
  - fuzzer : `scripts/fuzz-dangerous-nicknames.py`
- `test status`:
  - smoke validé sur `sofian_space` pour `show`, `type`, `delete`
  - smoke validé sur `leading_space` pour `show` et `delete`
  - cas instable observé sur `leading_space` + scénario `type`
- `findings`:
  - `sofian terki` ne reproduit pas le reset sous Speculos 1.3.1
  - `leading_space` (`" leading"`) a déclenché un timeout intermittent pendant `device push` avant le scénario `type`
  - repro ciblée sur `leading_space/type` : `2` succès, `1` timeout `LOAD_METADATAS`
- `notes`: meilleur point d'entrée pour l'incident actuel ; le cas `leading_space` doit être promu dans `FZ-15`

### [x] FZ-15 Régression orientée incident

- `status`: `done`
- `scope`: scénarios très proches des incidents observés
- `focus`:
  - `push nickname -> show password`
  - `push -> verify séparé`
  - `push -> restart -> show`
  - variantes autour d'un même nickname
- `entrypoint`: harness dédié incident
- `oracles`:
  - crash
  - écran inattendu
  - état relu incohérent
- `implementation`:
  - harness partagé : `scripts/speculos_fuzz_lib.py`
  - fuzzer : `scripts/fuzz-incident-regression.py`
- `test status`:
  - smoke validé sur `sofian_push_verify_show_control` et `alpha_beta_push_show_second`
  - campagne complète validée sur `8` cas, avec `5` failures reproductibles
- `findings`:
  - `sofian_push_show_control` et `sofian_push_verify_show_control` passent ; le flux incident minimal sur un seul identifiant reste stable sous Speculos
  - `sofian_push_restart_show_first` échoue après restart : l'état relu devient `["password1", "password2", "password3", "sofian terki"]`
  - `leading_space_push_type_repeat3` passe `3/3` dans ce harness ; la flakiness observée plus tôt n'a pas été reproduite sur cette campagne
  - `alpha_beta_push_show_second` crashe toujours `app-passwords` avec `Remote end closed connection without response`, puis `signal 11`
  - `alpha_beta_push_verify_show_second` crashe aussi ; un `verify` séparé ne neutralise donc pas ce seed minimal
  - `second_len_plus1_show_second` crashe aussi `app-passwords` avec `signal 11`
  - `second_len_plus1_restart_show_second` ne crashe pas au restart, mais sélectionne `password1` au lieu de `gmail\\0`
- `notes`:
  - `FZ-15` confirme que le meilleur reproducer minimal actuel est `["alpha", "beta"] -> push -> show second`
  - la séparation `push` / `verify` est saine pour `sofian terki`, mais ne suffit pas à protéger les seeds déjà fragiles

### [x] FZ-16 Oracles multiples consolidés

- `status`: `done`
- `scope`: couche de détection commune à tous les fuzzers
- `focus`:
  - crash process
  - timeout
  - APDU mismatch
  - redémarrage app
  - corruption relue
  - écran inattendu
- `entrypoint`: bibliothèque partagée du harness
- `oracles`: n/a, ce fuzzer est l'oracle
- `implementation`:
  - bibliothèque partagée : `scripts/fuzz_oracles.py`
  - validations dédiées : `scripts/fuzz-oracle-consolidation.py`
  - intégration branchée au moins dans `scripts/fuzz-incident-regression.py` et `scripts/fuzz-low-level-apdu.py`
- `test status`:
  - validation complète exécutée via `./scripts/fuzz-oracle-consolidation.py --json-out /tmp/fz16-full.json`
  - `4/4` checks verts
- `findings`:
  - le contrôle sain `sofian_push_verify_show_control` ne déclenche aucun oracle parasite
  - le reproducer minimal `alpha_beta_push_show_second` déclenche bien `speculos_crash`, `transport_closed` et `empty_screen`
  - le cas de pollution après restart `sofian_push_restart_show_first` déclenche bien `pulled_state_mismatch` et `password_pollution`
  - le hang APDU `dump_partial_then_info_then_pull` déclenche bien `timeout` et `stuck_transfer_prompt` ; il remonte aussi `unexpected_screen`, ce qui est cohérent avec un écran bloqué sur `Transfer metadatas ?`
- `notes`:
  - la couche commune sait maintenant classer les familles de panne principales déjà observées: crash Speculos, fermeture de transport, timeout, écran bloqué, pollution `password1/2/3`, mismatch de dump, sélection inattendue
  - la phase d'implémentation des `16` fuzzers du tracker est terminée
