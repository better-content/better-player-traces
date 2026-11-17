package com.bettercontent.betterplayertraces.storage

/** Exact incremental percentile rank: deterministic balanced-priority tree, no history scan per probe. */
internal class StrengthRankTree {
    private class Node(val key: Float, var count: Long) {
        val priority: Int = mixPriority(key.toBits())
        var total: Long = count
        var left: Node? = null
        var right: Node? = null
        companion object {
            private fun mixPriority(bits: Int): Int {
                var x = bits xor (bits ushr 16)
                x *= -0x7a143595
                x = x xor (x ushr 15)
                x *= -0x3d4d51cb
                return x xor (x ushr 16)
            }
        }
    }
    private var root: Node? = null
    fun adjust(key: Float, delta: Long) {
        require(key.isFinite())
        root = update(root, key, delta)
    }
    fun countAtMost(value: Float): Long {
        var node = root
        var count = 0L
        while (node != null) {
            if (value.compareTo(node.key) < 0) node = node.left
            else {
                count += (node.left?.total ?: 0L) + node.count
                node = node.right
            }
        }
        return count
    }
    private fun refresh(node: Node): Node {
        node.total = node.count + (node.left?.total ?: 0L) + (node.right?.total ?: 0L)
        return node
    }
    private fun earlier(a: Node, b: Node): Boolean =
        java.lang.Integer.compareUnsigned(a.priority, b.priority) < 0 ||
            a.priority == b.priority && a.key < b.key

    private fun rotateRight(node: Node): Node {
        val parent = requireNotNull(node.left)
        node.left = parent.right
        parent.right = refresh(node)
        return refresh(parent)
    }
    private fun rotateLeft(node: Node): Node {
        val parent = requireNotNull(node.right)
        node.right = parent.left
        parent.left = refresh(node)
        return refresh(parent)
    }
    private fun merge(left: Node?, right: Node?): Node? {
        if (left == null) return right
        if (right == null) return left
        return if (earlier(left, right)) {
            left.right = merge(left.right, right)
            refresh(left)
        } else {
            right.left = merge(left, right.left)
            refresh(right)
        }
    }
    private fun update(node: Node?, key: Float, delta: Long): Node? {
        if (node == null) {
            require(delta > 0L) { "strength rank underflow" }
            return Node(key, delta)
        }
        if (key.compareTo(node.key) == 0) {
            val updated = node.count + delta
            require(updated >= 0L) { "strength rank underflow" }
            if (updated == 0L) return merge(node.left, node.right)
            node.count = updated
        } else if (key.compareTo(node.key) < 0) {
            node.left = update(node.left, key, delta)
            if (node.left?.let { earlier(it, node) } == true) return rotateRight(node)
        } else {
            node.right = update(node.right, key, delta)
            if (node.right?.let { earlier(it, node) } == true) return rotateLeft(node)
        }
        return refresh(node)
    }
}
