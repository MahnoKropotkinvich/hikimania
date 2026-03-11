// Fixed-capacity list that auto-evicts oldest entries on add.
// Not serializable — convert to/from List<T> for persistence.
class BoundedList<T>(private val capacity: Int, initial: List<T> = emptyList()) : Iterable<T> {
    private val buf = ArrayDeque<T>(capacity)

    init {
        val start = if (initial.size > capacity) initial.size - capacity else 0
        for (i in start until initial.size) buf.addLast(initial[i])
    }

    val size get() = buf.size

    fun add(element: T) {
        if (buf.size >= capacity) buf.removeFirst()
        buf.addLast(element)
    }

    fun toList(): List<T> = buf.toList()

    fun takeLast(n: Int): List<T> = buf.toList().takeLast(n)

    override fun iterator(): Iterator<T> = buf.iterator()
}
