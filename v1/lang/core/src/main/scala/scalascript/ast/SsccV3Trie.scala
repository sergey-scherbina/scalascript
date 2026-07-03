package scalascript.ast

import java.io.ByteArrayOutputStream

/** Patricia (radix) trie used as a per-file string dictionary in `.sscc` v3.
 *
 *  Write path:
 *    val trie = new TrieBuilder
 *    val id   = trie.intern("foo")   // first call assigns id 0, then 1, 2, …
 *    val dict = trie.serialize()     // compact binary representation
 *
 *  Read path:
 *    val strings = TrieDecoder.decode(bytes, offset)  // Array[String] indexed by id
 *    val str     = strings(id)
 */

// ─── Varint helpers (LEB128 unsigned) ────────────────────────────────────────

private[ast] object Varint:
  def write(buf: ByteArrayOutputStream, n: Long): Unit =
    var v = n
    while v > 0x7fL do
      buf.write(((v & 0x7fL) | 0x80L).toInt)
      v >>>= 7
    buf.write(v.toInt)

  def read(bytes: Array[Byte], pos: Array[Int]): Long =
    var result = 0L
    var shift  = 0
    var b      = bytes(pos(0)) & 0xff; pos(0) += 1
    result    |= ((b & 0x7fL) << shift); shift += 7
    while (b & 0x80) != 0 do
      b       = bytes(pos(0)) & 0xff; pos(0) += 1
      result |= ((b & 0x7fL) << shift)
      shift  += 7
    result

// ─── TrieBuilder ─────────────────────────────────────────────────────────────

/** Accumulates strings, deduplicates, assigns monotonically increasing IDs,
 *  then serializes to a compact patricia trie binary representation. */
final class TrieBuilder private[ast] ():
  private val index  = scala.collection.mutable.HashMap.empty[String, Int]
  private var nextId = 0

  /** Return the ID for `s`, creating a new entry if needed. */
  def intern(s: String): Int =
    index.getOrElseUpdate(s, { val id = nextId; nextId += 1; id })

  /** Return `Some(id)` if `s` is already interned, `None` otherwise. */
  def lookup(s: String): Option[Int] = index.get(s)

  def size: Int = nextId

  /** Serialize the accumulated strings as a binary patricia trie.
   *
   *  Format (flat byte sequence):
   *    nodeCount: varint
   *    for each node (DFS pre-order, root first):
   *      edgeLen:      varint
   *      edgeBytes:    byte[edgeLen]   (UTF-8 bytes of the edge label)
   *      terminalFlag: byte  0x00=non-terminal, 0x01=terminal
   *      [terminalId:  varint]         (only if terminal)
   *      childCount:   varint
   *      for each child:
   *        firstByte:  byte            (first byte of child edge, for routing)
   *        childIdx:   varint          (1-based DFS pre-order index of child node)
   */
  def serialize(): Array[Byte] =
    val sortedPairs = index.toArray.sortBy(_._1)   // lex order for shared-prefix grouping
    val sortedStrs  = sortedPairs.map(_._1)
    val strToId     = sortedPairs.toMap

    // Build the radix trie in memory
    val root = new TrieNode("")
    for s <- sortedStrs do root.insert(s, strToId(s))

    // DFS-serialize all nodes; assign DFS index first
    val allNodes = scala.collection.mutable.ArrayBuffer.empty[TrieNode]
    def assignIdx(n: TrieNode): Unit =
      allNodes += n
      for (_, child) <- n.children do assignIdx(child)
    assignIdx(root)

    val nodeIndex = allNodes.zipWithIndex.toMap

    val buf = new ByteArrayOutputStream()
    Varint.write(buf, allNodes.length)
    for node <- allNodes do
      val edgeBytes = node.edge.getBytes(java.nio.charset.StandardCharsets.UTF_8)
      Varint.write(buf, edgeBytes.length)
      buf.write(edgeBytes)
      if node.terminalId >= 0 then
        buf.write(0x01)
        Varint.write(buf, node.terminalId)
      else
        buf.write(0x00)
      Varint.write(buf, node.children.length)
      for (firstByte, child) <- node.children do
        buf.write(firstByte & 0xff)
        Varint.write(buf, nodeIndex(child))
    buf.toByteArray

// Internal mutable trie node — package-private for SsccFormatV3
private[ast] final class TrieNode(var edge: String):
  var terminalId: Int = -1
  var children: Array[(Byte, TrieNode)] = Array.empty

  def insert(s: String, id: Int): Unit = insertAt(s, 0, id)

  private def insertAt(s: String, offset: Int, id: Int): Unit =
    if offset == s.length then
      terminalId = id
      return

    val firstByte = s.charAt(offset).toByte
    val childIdx  = children.indexWhere(_._1 == firstByte)

    if childIdx < 0 then
      // No matching child — create one with remaining suffix
      val child = new TrieNode(s.substring(offset))
      child.terminalId = id
      children = (children :+ (firstByte, child)).sortBy(_._1.toInt & 0xff)
    else
      val (_, child) = children(childIdx)
      // Find common prefix between child.edge and s.substring(offset)
      val ce     = child.edge
      val suffix = s.substring(offset)
      val cpLen  = commonPrefixLen(ce, suffix)
      if cpLen == ce.length then
        // Child edge is a prefix of suffix — recurse deeper
        child.insertAt(s, offset + cpLen, id)
      else
        // Split the existing child at cpLen
        val shared    = ce.substring(0, cpLen)
        val remainder = ce.substring(cpLen)
        val split     = new TrieNode(shared)
        child.edge    = remainder
        split.children = Array((remainder.charAt(0).toByte, child)).sortBy(_._1.toInt & 0xff)
        children(childIdx) = (firstByte, split)

        if cpLen == suffix.length then
          split.terminalId = id
        else
          val newChild = new TrieNode(suffix.substring(cpLen))
          newChild.terminalId = id
          split.children = (split.children :+ (newChild.edge.charAt(0).toByte, newChild)).sortBy(_._1.toInt & 0xff)

  private def commonPrefixLen(a: String, b: String): Int =
    var i = 0
    while i < a.length && i < b.length && a.charAt(i) == b.charAt(i) do i += 1
    i

// ─── TrieDecoder ─────────────────────────────────────────────────────────────

private[ast] object TrieDecoder:
  /** Decode a patricia trie from `bytes` starting at `pos(0)`.
   *  Returns an `Array[String]` indexed by terminal ID.
   *  Updates `pos(0)` to the first byte after the trie. */
  def decode(bytes: Array[Byte], pos: Array[Int]): Array[String] =
    val nodeCount = Varint.read(bytes, pos).toInt
    if nodeCount == 0 then return Array.empty

    val edges        = new Array[String](nodeCount)
    val terminals    = new Array[Int](nodeCount)   // -1 = non-terminal
    val childIndices = new Array[Array[Int]](nodeCount)

    var maxId = -1
    var i = 0
    while i < nodeCount do
      val edgeLen = Varint.read(bytes, pos).toInt
      edges(i) = new String(bytes, pos(0), edgeLen, java.nio.charset.StandardCharsets.UTF_8)
      pos(0) += edgeLen
      val isTerminal = bytes(pos(0)) & 0xff; pos(0) += 1
      val tid = if isTerminal == 0x01 then Varint.read(bytes, pos).toInt else -1
      terminals(i) = tid
      if tid > maxId then maxId = tid
      val cc = Varint.read(bytes, pos).toInt
      val cix = new Array[Int](cc)
      var j = 0
      while j < cc do
        pos(0) += 1  // firstByte: read and discard (used for lookup, not needed in decode DFS)
        cix(j) = Varint.read(bytes, pos).toInt
        j += 1
      childIndices(i) = cix
      i += 1

    if maxId < 0 then return Array.empty
    val result = new Array[String](maxId + 1)

    // DFS via trim-stack StringBuilder: avoids one String allocation per node.
    // stackPfxLen[i] = sb.length when stackIdx[i] was pushed — restored on pop.
    val sb          = new java.lang.StringBuilder(128)
    val stackIdx    = new Array[Int](nodeCount + 1)
    val stackPfxLen = new Array[Int](nodeCount + 1)
    stackIdx(0)    = 0
    stackPfxLen(0) = 0
    var top = 1
    while top > 0 do
      top -= 1
      val idx    = stackIdx(top)
      sb.setLength(stackPfxLen(top))
      sb.append(edges(idx))
      val fullLen = sb.length
      if terminals(idx) >= 0 then result(terminals(idx)) = sb.toString
      val kids = childIndices(idx).length
      var k = kids - 1  // push in reverse so DFS order (left-to-right) is preserved
      while k >= 0 do
        stackIdx(top)    = childIndices(idx)(k)
        stackPfxLen(top) = fullLen
        top += 1
        k -= 1

    result
