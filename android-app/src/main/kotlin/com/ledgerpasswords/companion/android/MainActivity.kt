package com.ledgerpasswords.companion.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ledgerpasswords.companion.core.model.CharsetPolicy
import com.ledgerpasswords.companion.core.model.PasswordIdentifier
import com.ledgerpasswords.companion.core.model.Vault

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LedgerPasswordsCompanionApp(
                vault = Vault(
                    entries = listOf(
                        PasswordIdentifier("github", CharsetPolicy.fromCli("upper,lower,numbers")),
                        PasswordIdentifier("gmail", CharsetPolicy.All),
                    ),
                ),
            )
        }
    }
}

@Composable
fun LedgerPasswordsCompanionApp(vault: Vault) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            HomeScreen(vault = vault)
        }
    }
}

@Composable
fun HomeScreen(vault: Vault) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Ledger Passwords Companion", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Gère uniquement les identifiants utilisés par l'app Ledger Passwords. " +
                "Le Ledger reste responsable de la génération des mots de passe.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Liste locale", style = MaterialTheme.typography.titleMedium)
                Text("${vault.entries.size} identifiants")
                Spacer(Modifier.height(12.dp))
                vault.entries.forEach { entry ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(entry.nickname)
                        Text(entry.charsets.toLedgerNames().joinToString(","), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Button(onClick = { /* TODO: open edit screen */ }) {
            Text("Ajouter un identifiant")
        }
        Button(onClick = { /* TODO: open import/export screen */ }) {
            Text("Import / Export backup.json")
        }
        Button(onClick = { /* TODO: open Ledger sync screen */ }) {
            Text("Synchroniser avec Ledger")
        }
    }
}

@Preview(showBackground = true)
@Composable
fun HomeScreenPreview() {
    LedgerPasswordsCompanionApp(
        vault = Vault(entries = listOf(PasswordIdentifier("github"), PasswordIdentifier("gmail"))),
    )
}
