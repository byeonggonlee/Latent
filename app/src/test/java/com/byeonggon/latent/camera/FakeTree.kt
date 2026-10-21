package com.byeonggon.latent.camera

/** An in-memory camera storage that counts how often it is asked about its contents. */
internal class FakeTree : RemoteTree {
    private val children = HashMap<Int, MutableList<Int>>()
    private val objects = HashMap<Int, RemoteObject>()
    private var nextHandle = 1

    /** Names of the folders whose contents were listed, in order. */
    val listedFolders = ArrayList<String>()

    /** How many objects were looked up; on a real camera each one is a slow round trip. */
    var lookups = 0

    fun folder(parent: Int, name: String): Int =
        add(parent, nextHandle++, RemoteObject(name, isFolder = true, sizeBytes = 0, modifiedAt = 0))

    fun file(parent: Int, name: String, sizeBytes: Long = 100, modifiedAt: Long = 1_000, handle: Int = nextHandle++): Int =
        add(parent, handle, RemoteObject(name, isFolder = false, sizeBytes = sizeBytes, modifiedAt = modifiedAt))

    /** Lists the existing [handle] among the objects of [parent] too, as a faulty camera might. */
    fun link(parent: Int, handle: Int) {
        children.getOrPut(parent) { ArrayList() } += handle
    }

    override fun childrenOf(parent: Int): IntArray? {
        if (parent != ROOT) listedFolders += objects.getValue(parent).name
        return children[parent].orEmpty().toIntArray()
    }

    override fun objectOf(handle: Int): RemoteObject? {
        lookups++
        return objects[handle]
    }

    private fun add(parent: Int, handle: Int, item: RemoteObject): Int {
        check(objects.put(handle, item) == null) { "Handle $handle is used twice" }
        children.getOrPut(parent) { ArrayList() } += handle
        return handle
    }

    companion object {
        const val ROOT = -1
    }
}
