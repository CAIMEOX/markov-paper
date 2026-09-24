package com.caimeo.markovpaper.paper

/** One operation policy for command execution and completion. */
internal object MarkovCommandAccess {
    fun permission(args: Array<out String>): String = when (args.firstOrNull()?.lowercase() ?: "preview") {
        "stop", "models", "setpos", "clearpos", "bound" -> "markov-paper.use"
        "debugworld" -> "markov-paper.admin"
        "materialize" -> "markov-paper.materialize"
        "assemblage", "hybrid", "workbench" -> {
            if (args.getOrNull(1).equals("materialize", ignoreCase = true)) "markov-paper.materialize"
            else "markov-paper.preview"
        }
        else -> "markov-paper.preview"
    }

    fun allows(args: Array<out String>, hasPermission: (String) -> Boolean): Boolean = hasPermission(permission(args))
}
