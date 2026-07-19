package io.github.lootdev78.spdflash

object CommandLineParser {
    fun parse(input: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaping = false

        input.forEach { ch ->
            when {
                escaping -> {
                    current.append(ch)
                    escaping = false
                }
                ch == '\\' -> escaping = true
                quote != null && ch == quote -> quote = null
                quote != null -> current.append(ch)
                ch == '\'' || ch == '"' -> quote = ch
                ch.isWhitespace() -> if (current.isNotEmpty()) {
                    result += current.toString()
                    current.clear()
                }
                else -> current.append(ch)
            }
        }
        require(quote == null) { "Unclosed quotation marks" }
        if (escaping) current.append('\\')
        if (current.isNotEmpty()) result += current.toString()
        return result
    }
}
