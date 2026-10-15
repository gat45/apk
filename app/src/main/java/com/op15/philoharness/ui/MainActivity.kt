package com.op15.philoharness.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.op15.philoharness.arguments.ArgumentDao
import com.op15.philoharness.arguments.ArgumentEntity
import com.op15.philoharness.arguments.EpistemicStatus
import com.op15.philoharness.arguments.ReasoningType
import com.op15.philoharness.control.DefaultPhiloReasoningWorker
import com.op15.philoharness.control.PhiloReasoningWorker
import com.op15.philoharness.control.ReasoningPhase
import com.op15.philoharness.rag.RagStore
import com.op15.philoharness.ui.theme.PhiloHarnessTheme
import com.op15.philoharness.ui.theme.PhiloStatusColors
import com.op15.philoharness.voice.SttController
import com.op15.philoharness.voice.SttResult
import com.op15.philoharness.voice.TtsController
import com.op15.philoharness.voice.TtsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Ecran de chat (bulles utilisateur/assistant), Compose/Material3 -- migration demandee
 * explicitement ("l'interface de chat ressemble pas a tes autres apk OP15") pour rapprocher
 * le style de GovernorActivity.kt (op15-unified, deja Compose/Material3). Toute la logique
 * metier (WorkManager, TTS/STT, RAG, confirmation d'outils IRREVERSIBLE) est INCHANGEE --
 * seule la couche de rendu passe des Views XML a Compose.
 */
class MainActivity : ComponentActivity() {

    /** Expose l'etat a la composition via StateFlow -- plus simple que de fileter des callbacks Compose depuis onCreate. */
    private val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val statusText = MutableStateFlow("")
    private val pendingConfirmation = MutableStateFlow<ArgumentEntity?>(null)
    /**
     * Ergonomie (angle mort reel : avant, la seule facon de savoir si le serveur LLM tournait
     * etait d'ouvrir Diagnostics manuellement -- pas visible depuis l'ecran ou on envoie
     * justement des messages). null = encore jamais sonde.
     */
    private val serverReachable = MutableStateFlow<Boolean?>(null)

    /** Evite de reafficher la meme boite de confirmation a chaque recomposition de la liste. */
    private val shownConfirmationFor = mutableSetOf<String>()
    private lateinit var ttsController: TtsController
    private lateinit var sttController: SttController
    private lateinit var dao: ArgumentDao
    private lateinit var workManager: WorkManager

    /** Dictee -> rempli le champ de saisie Compose -- pont simple entre callback Android et etat Compose. */
    private var dictatedText = MutableStateFlow<String?>(null)

    private val requestMicPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            sttController.startListening()
        } else {
            Toast.makeText(this, "Permission micro refusee : dictee indisponible.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Cablage reel (pas un stub) : chaque message envoye est indexe dans le RAG local,
        // et l'etat materiel est journalise au demarrage -- preuve concrete validee sur
        // device le 13/10 (voir DEVICE_TEST_CHECKLIST.md) : FTS5 absent sur OxygenOS 16,
        // repli LIKE fonctionnel y compris apres redemarrage de l'app.
        lifecycleScope.launch(Dispatchers.IO) {
            android.util.Log.i("HardwareTelemetry", com.op15.philoharness.hardware.HardwareTelemetry.summary())
        }

        dao = (application as PhiloHarnessApplication).database.argumentDao()
        workManager = WorkManager.getInstance(this)

        ttsController = TtsController(this) { state ->
            if (state is TtsState.Unavailable) {
                runOnUiThread { Toast.makeText(this, "Voix indisponible : ${state.reason}", Toast.LENGTH_SHORT).show() }
            }
        }

        sttController = SttController(this) { result ->
            runOnUiThread {
                when (result) {
                    is SttResult.Recognized -> {
                        // Boucle conversationnelle : une phrase dictee declenche directement
                        // l'envoi, comme si l'utilisateur avait tape puis appuye sur envoyer.
                        dictatedText.value = result.text
                        sendMessage(result.text)
                    }
                    is SttResult.Error -> Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
                    SttResult.Cancelled, SttResult.ListeningStarted, SttResult.ListeningEnded -> Unit
                }
            }
        }

        lifecycleScope.launch {
            dao.observeActiveArguments().collectLatest { arguments ->
                messages.value = ChatMapper.toChatMessages(arguments)
                arguments.forEach { maybeShowToolConfirmation(it) }
            }
        }

        // Sonde legere /health toutes les 5s -- meme contrat que DiagnosticsActivity.probeServer()
        // mais ici on n'a besoin que d'un booleen (pas du nom du modele charge), pour un indicateur
        // visible directement sur l'ecran de chat.
        lifecycleScope.launch(Dispatchers.IO) {
            while (true) {
                serverReachable.value = probeServerHealth()
                kotlinx.coroutines.delay(5000)
            }
        }

        setContent {
            PhiloHarnessTheme {
                ChatScreen(
                    messagesFlow = messages,
                    statusFlow = statusText,
                    dictatedTextFlow = dictatedText,
                    serverReachableFlow = serverReachable,
                    onSend = ::sendMessage,
                    onMicTap = ::onMicTap,
                    onDiagnosticsTap = { startActivity(Intent(this, DiagnosticsActivity::class.java)) },
                    onDelete = ::deleteMessage,
                    onRetry = ::retryMessage,
                )
            }
        }
    }

    /** @return joignable ou non -- jamais une exception propagee, meme philosophie que DiagnosticsActivity.httpGet. */
    private fun probeServerHealth(): Boolean = try {
        val connection = java.net.URL("http://127.0.0.1:8080/health").openConnection() as java.net.HttpURLConnection
        connection.connectTimeout = 1200
        connection.readTimeout = 1200
        connection.requestMethod = "GET"
        val ok = connection.responseCode == 200
        connection.disconnect()
        ok
    } catch (e: Exception) {
        false
    }

    private fun onMicTap() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            sttController.startListening()
        } else {
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /**
     * Point d'entree unique (clavier ou micro) : envoie un message et lit la
     * reponse a voix haute des qu'elle est prete -- plus de bouton separe
     * "Analyser"/"Lire la reponse" a chercher, un assistant repond toujours.
     */
    private fun sendMessage(text: String) {
        RagStore.indexChat(this, "main", "user", text)
        val argumentId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val argument = ArgumentEntity(
            id = argumentId,
            questionOrigine = text,
            texteOriginal = text,
            definitionsJson = "{}",
            premissesExplicitesJson = "[]",
            premissesImplicitesJson = "[]",
            conclusion = "",
            typeRaisonnement = ReasoningType.DEDUCTIF,
            hypothesesJson = "[]",
            limitesFormalisation = null,
            statut = EpistemicStatus.EN_ATTENTE_DE_VERIFICATION,
            niveauConfianceJustification = "",
            createdAtEpochMs = now,
            validFrom = now,
            reasoningPhase = ReasoningPhase.NEEDS_REASONING.name,
        )

        lifecycleScope.launch {
            dao.insertArgument(argument)

            val request = OneTimeWorkRequestBuilder<DefaultPhiloReasoningWorker>()
                .setInputData(workDataOf(PhiloReasoningWorker.KEY_ARGUMENT_ID to argumentId))
                .build()
            workManager.enqueueUniqueWork(argumentId, ExistingWorkPolicy.KEEP, request)

            lifecycleScope.launch {
                workManager.getWorkInfoByIdFlow(request.id).collectLatest { info ->
                    statusText.value = when (info?.state) {
                        WorkInfo.State.ENQUEUED -> "En file d'attente..."
                        WorkInfo.State.RUNNING -> "..."
                        WorkInfo.State.SUCCEEDED -> ""
                        WorkInfo.State.FAILED -> "Echec de l'analyse."
                        WorkInfo.State.BLOCKED -> "Bloque."
                        WorkInfo.State.CANCELLED -> "Annule."
                        null -> ""
                    }
                    if (info?.state == WorkInfo.State.SUCCEEDED) {
                        val finalArgument = dao.getArgument(argumentId)
                        val conclusion = finalArgument?.conclusion
                        if (!conclusion.isNullOrBlank()) {
                            RagStore.indexChat(this@MainActivity, "main", "assistant", conclusion)
                        }
                        ttsController.speak(
                            if (!conclusion.isNullOrBlank()) conclusion else "Je ne peux pas encore repondre a cette question.",
                        )
                    }
                }
            }
        }
    }

    /**
     * Validation humaine OBLIGATOIRE pour tout outil IRREVERSIBLE (git_commit,
     * screen_tap, screen_input) : le Worker ne les execute JAMAIS tout seul
     * (cf. PhiloReasoningWorker.doWork). Rien ne s'execute sans que
     * l'utilisateur confirme explicitement.
     */
    private fun maybeShowToolConfirmation(argument: ArgumentEntity) {
        val tool = argument.pendingTool ?: return
        if (argument.pendingToolConfirmed || argument.pendingToolRefused) return
        val key = "${argument.id}:$tool:${argument.pendingToolArgsJson}"
        if (!shownConfirmationFor.add(key)) return // deja affiche pour cette demande precise
        pendingConfirmation.value = argument
    }

    private fun confirmPendingTool(argumentId: String) {
        lifecycleScope.launch {
            dao.confirmPendingTool(argumentId)
            reenqueueWorker(argumentId)
            pendingConfirmation.value = null
        }
    }

    private fun refusePendingTool(argumentId: String) {
        lifecycleScope.launch {
            dao.refusePendingTool(argumentId)
            reenqueueWorker(argumentId)
            pendingConfirmation.value = null
        }
    }

    /**
     * Suppression depuis l'UI (manque signale dans TODO.md et confirme par un
     * concurrent similaire, cf. audit externe) : jamais un DELETE SQL --
     * [ArgumentDao.softDelete] marque juste invalidatedAt, la bulle disparait de
     * observeActiveArguments mais rien n'est detruit en base.
     */
    private fun deleteMessage(argumentId: String) {
        lifecycleScope.launch {
            dao.softDelete(argumentId, System.currentTimeMillis())
        }
    }

    /** Relance l'analyse d'un message existant (ex. apres un echec) sans le ressaisir. */
    private fun retryMessage(argumentId: String) {
        lifecycleScope.launch {
            val current = dao.getArgument(argumentId) ?: return@launch
            dao.updateArgument(
                current.copy(
                    statut = EpistemicStatus.EN_ATTENTE_DE_VERIFICATION,
                    conclusion = "",
                    reasoningPhase = ReasoningPhase.NEEDS_REASONING.name,
                    lastAbstentionReason = null,
                ),
            )
            reenqueueWorker(argumentId)
        }
    }

    private fun reenqueueWorker(argumentId: String) {
        val request = OneTimeWorkRequestBuilder<DefaultPhiloReasoningWorker>()
            .setInputData(workDataOf(PhiloReasoningWorker.KEY_ARGUMENT_ID to argumentId))
            .build()
        workManager.enqueueUniqueWork(argumentId, ExistingWorkPolicy.REPLACE, request)
    }

    override fun onDestroy() {
        sttController.destroy()
        ttsController.shutdown()
        super.onDestroy()
    }

    // ---- Compose UI ----

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ChatScreen(
        messagesFlow: MutableStateFlow<List<ChatMessage>>,
        statusFlow: MutableStateFlow<String>,
        dictatedTextFlow: MutableStateFlow<String?>,
        serverReachableFlow: MutableStateFlow<Boolean?>,
        onSend: (String) -> Unit,
        onMicTap: () -> Unit,
        onDiagnosticsTap: () -> Unit,
        onDelete: (String) -> Unit,
        onRetry: (String) -> Unit,
    ) {
        val messageList by messagesFlow.asStateFlow().collectAsState()
        val status by statusFlow.asStateFlow().collectAsState()
        val dictated by dictatedTextFlow.asStateFlow().collectAsState()
        val serverReachable by serverReachableFlow.asStateFlow().collectAsState()
        val pendingTool by pendingConfirmation.asStateFlow().collectAsState()
        var inputText by rememberSaveable { mutableStateOf("") }
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()

        LaunchedEffect(dictated) {
            dictated?.let { inputText = it; dictatedTextFlow.value = null }
        }
        LaunchedEffect(messageList.size) {
            if (messageList.isNotEmpty()) listState.animateScrollToItem(messageList.size - 1)
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("OP15 Assistant", fontWeight = FontWeight.Bold) },
                    actions = {
                        // Ergonomie : avant, il fallait ouvrir Diagnostics pour savoir si le
                        // serveur LLM tournait -- visible ici directement, la ou on envoie
                        // justement des messages. Couleur neutre tant que la 1ere sonde n'est
                        // pas revenue (jamais rouge par defaut avant d'avoir une vraie reponse).
                        val (dotColor, dotLabel) = when (serverReachable) {
                            true -> MaterialTheme.colorScheme.primary to "Serveur LLM joignable"
                            false -> MaterialTheme.colorScheme.error to "Serveur LLM injoignable -- toucher pour ouvrir Diagnostics"
                            null -> MaterialTheme.colorScheme.outline to "Etat du serveur LLM en cours de verification"
                        }
                        Box(
                            Modifier
                                .padding(end = 4.dp)
                                .size(10.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(dotColor)
                                .semantics { contentDescription = dotLabel },
                        )
                        IconButton(onClick = onDiagnosticsTap) {
                            Icon(Icons.Default.Info, contentDescription = "Diagnostics materiel et modele")
                        }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                // Ergonomie : les actions Supprimer/Relancer (appui long) et Detail (appui
                // simple) n'avaient aucune indication visuelle -- decouvrable seulement par
                // hasard. Bandeau affiche une seule fois (SharedPreferences), fermable.
                val context = androidx.compose.ui.platform.LocalContext.current
                val prefs = remember { context.getSharedPreferences("op15_assistant_ui", android.content.Context.MODE_PRIVATE) }
                var showHint by remember { mutableStateOf(!prefs.getBoolean("bubble_hint_dismissed", false)) }
                if (showHint) {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Astuce : appui simple sur un message pour le detail, appui long pour le supprimer/relancer.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = {
                                showHint = false
                                prefs.edit().putBoolean("bubble_hint_dismissed", true).apply()
                            }) {
                                Icon(Icons.Default.Close, contentDescription = "Fermer cette astuce")
                            }
                        }
                    }
                }
                if (status.isNotBlank()) {
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(messageList, key = { "${it.argumentId}:${it::class.simpleName}" }) { message ->
                        MessageBubble(message, onDelete = onDelete, onRetry = onRetry)
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        // Le placeholder seul est un anti-pattern a11y connu (TODO.md) : TalkBack
                        // ne l'annonce que champ vide, plus rien une fois du texte saisi -- un
                        // contentDescription stable sur le champ reste correct dans les deux cas.
                        modifier = Modifier.weight(1f).semantics { contentDescription = "Message a envoyer a l'assistant" },
                        placeholder = { Text("Pose ta question...") },
                        maxLines = 4,
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = onMicTap) {
                        // "Mic" n'existe pas dans le set d'icones "core" (Icons.Default) --
                        // "extended" ajouterait une dependance lourde pour une seule icone ;
                        // reutilise le drawable systeme deja utilise avant la migration.
                        Icon(painterResource(android.R.drawable.ic_btn_speak_now), contentDescription = "Dicter un message")
                    }
                    IconButton(onClick = {
                        val text = inputText.trim()
                        if (text.isNotEmpty()) {
                            onSend(text)
                            inputText = ""
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Envoyer le message")
                    }
                }
            }
        }

        pendingTool?.let { argument ->
            ToolConfirmationDialog(
                argument = argument,
                onConfirm = { confirmPendingTool(argument.id) },
                onRefuse = { refusePendingTool(argument.id) },
            )
        }
    }

    @Composable
    private fun ToolConfirmationDialog(argument: ArgumentEntity, onConfirm: () -> Unit, onRefuse: () -> Unit) {
        val tool = argument.pendingTool ?: return
        val args = argument.pendingToolArgsJson.orEmpty()
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {}, // pas d'annulation implicite -- action IRREVERSIBLE, reponse explicite requise
            title = { Text("Validation requise") },
            text = { Text("Le harnais demande a executer :\n\n$tool" + (if (args.isNotBlank()) " ($args)" else "") + "\n\nConfirmer cette action ?") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = onConfirm) { Text("Confirmer") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = onRefuse) { Text("Refuser") } },
        )
    }

    /** Appui long sur une bulle -> menu Supprimer/Relancer (manque signale dans TODO.md). */
    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun MessageActionMenu(argumentId: String, onDelete: (String) -> Unit, onRetry: (String) -> Unit, content: @Composable () -> Unit) {
        var expanded by remember { mutableStateOf(false) }
        Box(
            modifier = Modifier.combinedClickable(
                onClick = {
                    startActivity(
                        Intent(this@MainActivity, ArgumentDetailActivity::class.java)
                            .putExtra(ArgumentDetailActivity.EXTRA_ARGUMENT_ID, argumentId),
                    )
                },
                onLongClick = { expanded = true },
            ),
        ) {
            content()
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(text = { Text("Relancer") }, onClick = { expanded = false; onRetry(argumentId) })
                DropdownMenuItem(text = { Text("Supprimer") }, onClick = { expanded = false; onDelete(argumentId) })
            }
        }
    }

    @Composable
    private fun MessageBubble(message: ChatMessage, onDelete: (String) -> Unit, onRetry: (String) -> Unit) {
        when (message) {
            is ChatMessage.User -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    MessageActionMenu(message.argumentId, onDelete, onRetry) {
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp),
                            modifier = Modifier.widthIn(max = 300.dp),
                        ) {
                            Text(
                                message.text,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            )
                        }
                    }
                }
            }
            is ChatMessage.Assistant -> {
                val dark = androidx.compose.foundation.isSystemInDarkTheme()
                // Bug reel trouve en auditant l'UI (retour "l'ui est pauvre") : ces couleurs de
                // statut existaient dans colors.xml depuis l'ancienne UI mais n'etaient plus
                // referencees apres la refonte en bulles -- SOUTENU et REFUTE etaient
                // visuellement identiques. Reconnecte ici, port du meme principe.
                val borderColor = when (message.status) {
                    EpistemicStatus.SOUTENU -> if (dark) PhiloStatusColors.supportedDark else PhiloStatusColors.supportedLight
                    EpistemicStatus.REFUTE -> if (dark) PhiloStatusColors.refutedDark else PhiloStatusColors.refutedLight
                    EpistemicStatus.CONTESTE -> if (dark) PhiloStatusColors.pendingDark else PhiloStatusColors.pendingLight
                    else -> MaterialTheme.colorScheme.outline
                }
                val borderWidth = if (message.status in setOf(EpistemicStatus.SOUTENU, EpistemicStatus.REFUTE, EpistemicStatus.CONTESTE)) 2.dp else 1.dp
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                    MessageActionMenu(message.argumentId, onDelete, onRetry) {
                        Column {
                            Card(
                                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = androidx.compose.foundation.BorderStroke(borderWidth, borderColor),
                                modifier = Modifier.widthIn(max = 300.dp),
                            ) {
                                Text(
                                    message.text,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                )
                            }
                            message.meta?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 2.dp, start = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
