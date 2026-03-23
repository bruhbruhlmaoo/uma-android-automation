package com.steve1316.uma_android_automation.utils

/**
 * Represents a single node in a [DoublyLinkedList].
 *
 * @param T The type of value stored in the node.
 * @property list The list that this node belongs to.
 * @property value The value stored in this node.
 * @property next The next node in the list.
 * @property prev The previous node in the list.
 */
open class DoublyLinkedListNode<T>(val list: DoublyLinkedList<T>, var value: T, var next: DoublyLinkedListNode<T>? = null, var prev: DoublyLinkedListNode<T>? = null)

/**
 * A generic doubly linked list implementation.
 *
 * @param T The type of values stored in the list.
 */
open class DoublyLinkedList<T> {
    /** The head node of the list. */
    private var head: DoublyLinkedListNode<T>? = null

    /** The tail node of the list. */
    private var tail: DoublyLinkedListNode<T>? = null

    /** The number of nodes in the list. */
    var size: Int = 0
        private set

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Checks whether the list is empty.
     *
     * @return True if the list has no nodes.
     */
    fun isEmpty(): Boolean {
        return size == 0
    }

    /**
     * Pushes a new [value] to the head of the list.
     *
     * @param value The value to push.
     * @return The newly created node.
     */
    fun push(value: T): DoublyLinkedListNode<T> {
        val newNode: DoublyLinkedListNode<T> = DoublyLinkedListNode(this, value)
        if (isEmpty()) {
            head = newNode
            tail = newNode
        } else {
            newNode.next = head
            head?.prev = newNode
            head = newNode
        }
        size++
        return newNode
    }

    /**
     * Pops the value from the head of the list.
     *
     * @return The value at the head of the list, or null if the list is empty.
     */
    fun pop(): T? {
        if (isEmpty()) {
            return null
        }
        val result: T? = head?.value
        head = head?.next
        head?.prev = null
        size--
        if (isEmpty()) {
            tail = null
        }
        return result
    }

    /**
     * Appends a new [value] to the tail of the list.
     *
     * @param value The value to append.
     * @return The newly created node.
     */
    fun append(value: T): DoublyLinkedListNode<T> {
        val newNode: DoublyLinkedListNode<T> = DoublyLinkedListNode(this, value)
        if (isEmpty()) {
            head = newNode
            tail = newNode
        } else {
            newNode.prev = tail
            tail?.next = newNode
            tail = newNode
        }
        size++
        return newNode
    }

    /**
     * Finds the first node that contains the specified [value].
     *
     * @param value The value to find.
     * @return The node containing the value, or null if not found.
     */
    fun find(value: T): DoublyLinkedListNode<T>? {
        var curr = head
        while (curr != null) {
            if (curr.value == value) {
                return curr
            }
            curr = curr.next
        }
        return null
    }

    /**
     * Finds the index of the first node that contains the specified [value].
     *
     * @param value The value to find.
     * @return The index of the value, or null if not found.
     */
    fun findIndex(value: T): Int? {
        var curr = head
        var i = 0
        while (curr != null) {
            if (curr.value == value) {
                return i
            }
            curr = curr.next
            i++
        }
        return null
    }

    /**
     * Retrieves all the values in the list.
     *
     * @return A list containing all values from the head to the tail.
     */
    fun getValues(): List<T> {
        val res: MutableList<T> = mutableListOf()
        var curr = head
        while (curr != null) {
            res.add(curr.value)
            curr = curr.next
        }
        return res.toList()
    }

    override fun toString(): String {
        if (isEmpty()) {
            return "Empty List"
        }
        val sb = StringBuilder()
        var current = head
        while (current != null) {
            sb.append(current.value).append(if (current.next != null) " <-> " else "")
            current = current.next
        }
        return sb.toString()
    }
}
