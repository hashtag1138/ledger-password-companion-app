# TODO prioritaire

1. Ajouter le Gradle wrapper : `gradle wrapper --gradle-version=9.4.1`.
2. Lancer `./gradlew :core:test :ledger-protocol:test` et corriger les éventuelles incompatibilités de dépendances.
3. Compléter les tests du codec metadata.
4. Finaliser la CLI offline.
5. Implémenter `LedgerHidFraming`.
6. Ajouter un transport PC : HID ou Speculos.
7. Implémenter `AndroidUsbLedgerTransport`.
8. Ajouter les écrans Compose réels : liste, édition, import/export, sync.
