# TODO prioritaire

1. Comprendre l'incident de reset observé sur vrai Ledger après `push` puis usage `Show password`, malgré un scénario sain sous Speculos 1.3.1.
2. Confirmer en conditions réelles la version de l'app Passwords installée sur le Ledger et corréler le comportement avec le garde-fou `>= 1.3.1`.
3. Ajouter des diagnostics plus précis côté Android sur `getAppInfo` et, si possible, un export structuré des versions/capacités device.
4. Ajouter la gestion de conflits ou de merge avant remplacement local après `pull`.
