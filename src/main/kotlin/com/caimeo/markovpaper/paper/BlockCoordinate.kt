package com.caimeo.markovpaper.paper

fun parseBlockCoordinate(token: String, base: Int): Int {
    if (!token.startsWith('~')) {
        return token.toIntOrNull() ?: error("Invalid block coordinate '$token'")
    }
    val offsetText = token.drop(1)
    val offset = if (offsetText.isEmpty()) 0 else {
        offsetText.toIntOrNull() ?: error("Invalid relative block coordinate '$token'")
    }
    return Math.addExact(base, offset)
}
