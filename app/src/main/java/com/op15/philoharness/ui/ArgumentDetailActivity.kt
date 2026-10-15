package com.op15.philoharness.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.op15.philoharness.arguments.ArgumentDao
import com.op15.philoharness.arguments.ArgumentEntity
import com.op15.philoharness.ui.theme.PhiloHarnessTheme
import org.json.JSONArray

/**
 * Vue detail d'un argument (manque signale dans TODO.md, "Pas de clic sur un item
 * de la liste" + confirme pertinent par l'audit externe comparant a un concurrent).
 * Affiche ce qui etait jusque-la ecrase dans une seule ligne de bulle : premisses
 * explicites/implicites, hypotheses, formalisation SMT-LIB2 et verdict solveur bruts
 * (persistes par PhiloReasoningWorker.verifyIndependently depuis cette session --
 * ils existaient deja en memoire mais n'etaient jamais ecrits nulle part avant).
 */
class ArgumentDetailActivity : ComponentActivity() {

    companion object {
        const val EXTRA_ARGUMENT_ID = "argument_id"
    }

    private lateinit var dao: ArgumentDao

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dao = (application as PhiloHarnessApplication).database.argumentDao()
        val argumentId = intent.getStringExtra(EXTRA_ARGUMENT_ID)

        setContent {
            PhiloHarnessTheme {
                var argument by androidx.compose.runtime.remember { mutableStateOf<ArgumentEntity?>(null) }
                LaunchedEffect(argumentId) {
                    argument = argumentId?.let { dao.getArgument(it) }
                }
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Detail de l'argument", fontWeight = FontWeight.Bold) },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                                }
                            },
                        )
                    },
                ) { padding ->
                    val current = argument
                    if (current == null) {
                        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                            Text("Argument introuvable (deja supprime ?).")
                        }
                    } else {
                        DetailBody(current, Modifier.fillMaxSize().padding(padding))
                    }
                }
            }
        }
    }

    @Composable
    private fun DetailBody(argument: ArgumentEntity, modifier: Modifier = Modifier) {
        val sections = buildList {
            add("Question" to argument.questionOrigine.ifBlank { argument.texteOriginal })
            add("Statut" to argument.statut.name)
            add("Type de raisonnement" to argument.typeRaisonnement.name)
            if (argument.conclusion.isNotBlank()) add("Conclusion" to argument.conclusion)
            jsonListOrNull(argument.premissesExplicitesJson)?.let { add("Premisses explicites" to it) }
            jsonListOrNull(argument.premissesImplicitesJson)?.let { add("Premisses implicites" to it) }
            jsonListOrNull(argument.hypothesesJson)?.let { add("Hypotheses" to it) }
            argument.limitesFormalisation?.let { add("Limites de formalisation" to it) }
            argument.lastSmtLib2?.let { add("Formalisation SMT-LIB2" to it) }
            argument.lastSolverVerdict?.let { verdict ->
                val detail = argument.lastSolverDetail
                val solver = argument.lastSolverUsed
                val text = buildString {
                    append(verdict)
                    if (!solver.isNullOrBlank()) append(" (solveur : $solver)")
                    if (!detail.isNullOrBlank()) append("\n\n$detail")
                }
                add("Verdict solveur brut" to text)
            }
            argument.lastAbstentionReason?.let { add("Derniere raison d'abstention" to it) }
            argument.lastToolOutput?.let { add("Dernier resultat d'outil" to it) }
        }
        LazyColumn(
            modifier = modifier,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(sections) { (title, body) ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(14.dp)) {
                        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }

    /** Les champs *Json de ArgumentEntity sont des tableaux JSON de strings -- jamais affiches bruts avec crochets/guillemets. */
    private fun jsonListOrNull(json: String): String? = try {
        val array = JSONArray(json)
        if (array.length() == 0) null else (0 until array.length()).joinToString("\n") { "- ${array.getString(it)}" }
    } catch (e: Exception) {
        null
    }
}
