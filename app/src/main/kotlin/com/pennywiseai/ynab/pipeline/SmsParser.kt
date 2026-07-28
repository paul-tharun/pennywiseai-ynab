package com.pennywiseai.ynab.pipeline

import com.pennywiseai.parser.core.ParsedTransaction

/**
 * The pipeline's parse step, behind a seam. The production binding delegates to
 * parser-core's BankParserFactory (PipelineModule); tests supply a lambda that
 * returns a canned ParsedTransaction (or null), so the pipeline's decision logic is
 * tested without depending on parser-core's parsing of specific SMS strings.
 */
fun interface SmsParser {
    /** Returns the parsed transaction, or null if no parser matched (message is dropped). */
    fun parse(body: String, sender: String, timestamp: Long): ParsedTransaction?
}
