package com.op15.philoharness.rag

/**
 * Abstraction pure (pas de dependance Android/Context) entre ReasoningController et RagStore
 * -- meme motivation que LlmClient/LogicSolver/ArgumentStructurer deja injectes : garder
 * ReasoningController testable en JVM pur (RagStore depend de android.content.Context et
 * android.database.sqlite, aucun des deux n'est utilisable dans un test JVM classique).
 *
 * Angle mort comble ici (TODO.md, "PAS cable : rien n'appelle encore RagStore.indexChat/search") :
 * l'indexation existait et fonctionnait (confirmee par ses propres commentaires de bugs reels
 * trouves sur device), mais rien ne lisait jamais ce qui avait ete indexe.
 */
fun interface RagRetriever {
    /** @return les passages les plus pertinents pour [query], jamais plus de [topK]. Liste vide si rien de pertinent (jamais invente). */
    fun retrieve(query: String, topK: Int): List<String>

    companion object {
        /** Par defaut pour les sites d'appel qui ne branchent pas le RAG (ex. tests JVM existants). */
        val NONE = RagRetriever { _, _ -> emptyList() }
    }
}
