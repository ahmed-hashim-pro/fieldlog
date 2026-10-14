package fieldlog.core

/**
 * A Lamport timestamp that names one operation. Ordered by counter, then by
 * replica name, so any two replicas agree on which of two operations is later.
 */
data class OpId(val counter: Long, val replica: String) : Comparable<OpId> {
    override fun compareTo(other: OpId): Int =
        if (counter != other.counter) counter.compareTo(other.counter) else replica.compareTo(other.replica)

    override fun toString() = "$counter@$replica"

    companion object {
        fun parse(s: String): OpId {
            val at = s.indexOf('@')
            require(at > 0 && at < s.length - 1) { "bad op id '$s'" }
            val counter = s.substring(0, at).toLongOrNull() ?: throw IllegalArgumentException("bad op id '$s'")
            return OpId(counter, s.substring(at + 1))
        }
    }
}

/**
 * Everything a replica can do to the notebook is one of five operations.
 * [deps] are the operations this one refers to; a replica holds an operation
 * back until it has applied all of them.
 */
sealed interface Op {
    val id: OpId
    val deps: List<OpId>
}

/** Last-writer-wins register: [entity].[field] = [value]. */
data class SetField(override val id: OpId, val entity: String, val field: String, val value: String) : Op {
    override val deps: List<OpId> get() = emptyList()
}

/** Observed-remove set: add [elem] to [set], tagged with this op's id. */
data class Add(override val id: OpId, val set: String, val elem: String) : Op {
    override val deps: List<OpId> get() = emptyList()
}

/** Observed-remove set: remove the [tags] of [elem] this replica had seen. */
data class Remove(override val id: OpId, val set: String, val elem: String, val tags: List<OpId>) : Op {
    override val deps: List<OpId> get() = tags
}

/** RGA: insert character [ch] into [text] right after [after] (null = at the start). */
data class Insert(override val id: OpId, val text: String, val after: OpId?, val ch: String) : Op {
    override val deps: List<OpId> get() = listOfNotNull(after)
}

/** RGA: tombstone the character [target] in [text]. */
data class Delete(override val id: OpId, val text: String, val target: OpId) : Op {
    override val deps: List<OpId> get() = listOf(target)
}
