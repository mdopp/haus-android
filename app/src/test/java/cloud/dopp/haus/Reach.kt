package cloud.dopp.haus

/**
 * Reaches main code by name. The tests that hold the contract, the pairing and
 * the live stream's retries were written before the code they test (red
 * first), so they had to compile against a tree that did not have it yet: a
 * missing class, function or property fails the one test that needs it, by
 * name, instead of failing the whole test build. Each lookup states what it
 * wants, so a red run reads as a list of what is missing.
 */
object Reach {

    /** The Kotlin `object` [cls] (its `INSTANCE`). */
    fun obj(cls: String): Any = Class.forName(cls).getField("INSTANCE").get(null)

    /** Call [name] with [args] on the Kotlin `object` [cls]. */
    fun call(cls: String, name: String, vararg args: Any?): Any? {
        val o = obj(cls)
        val m = o.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == args.size }
            ?: throw AssertionError("$cls has no $name with ${args.size} parameters")
        return m.invoke(o, *args)
    }

    /** Set the property [prop] of the Kotlin `object` [cls] through its setter. */
    fun set(cls: String, prop: String, value: Any?) {
        val o = obj(cls)
        val setter = "set" + prop.replaceFirstChar { it.uppercase() }
        val m = o.javaClass.methods.firstOrNull { it.name == setter && it.parameterCount == 1 }
            ?: throw AssertionError("$cls has no settable $prop")
        m.invoke(o, value)
    }

    /** Read the property [prop] of [o] through its getter. */
    fun get(o: Any, prop: String): Any? {
        val getter = "get" + prop.replaceFirstChar { it.uppercase() }
        val m = o.javaClass.methods.firstOrNull { it.name == getter && it.parameterCount == 0 }
            ?: throw AssertionError("${o.javaClass.name} has no $prop")
        return m.invoke(o)
    }
}
