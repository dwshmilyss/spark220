/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.storage.memory

import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.LinkedHashMap

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.reflect.ClassTag

import com.google.common.io.ByteStreams

import org.apache.spark.{SparkConf, TaskContext}
import org.apache.spark.internal.Logging
import org.apache.spark.memory.{MemoryManager, MemoryMode}
import org.apache.spark.serializer.{SerializationStream, SerializerManager}
import org.apache.spark.storage._
import org.apache.spark.unsafe.Platform
import org.apache.spark.util.{SizeEstimator, Utils}
import org.apache.spark.util.collection.SizeTrackingVector
import org.apache.spark.util.io.{ChunkedByteBuffer, ChunkedByteBufferOutputStream}

private sealed trait MemoryEntry[T] {
  def size: Long
  def memoryMode: MemoryMode
  def classTag: ClassTag[T]
}

/**
 * 非序列化的存储级别使用(StorageLevel.MEMORY_ONLY)
 * 内部用一个数组保存所有对象的实例
 * @param value
 * @param size
 * @param classTag
 * @tparam T
 */
private case class DeserializedMemoryEntry[T](
    value: Array[T],
    size: Long,
    classTag: ClassTag[T]) extends MemoryEntry[T] {
  val memoryMode: MemoryMode = MemoryMode.ON_HEAP
}

/**
 * 序列化的存储级别。例如 StorageLevel.MEMORY_ONLY_SER
 * 用字节缓冲区（ByteBuffer）来存储二进制数据
 * @param buffer
 * @param memoryMode
 * @param classTag
 * @tparam T
 */
private case class SerializedMemoryEntry[T](
    buffer: ChunkedByteBuffer,
    memoryMode: MemoryMode,
    classTag: ClassTag[T]) extends MemoryEntry[T] {
  def size: Long = buffer.size
}

private[storage] trait BlockEvictionHandler {
  /**
   * Drop a block from memory, possibly putting it on disk if applicable. Called when the memory
   * store reaches its limit and needs to free up space.
   *
   * If `data` is not put on disk, it won't be created.
   *
   * The caller of this method must hold a write lock on the block before calling this method.
   * This method does not release the write lock.
   *
   * @return the block's new effective StorageLevel.
   */
  private[storage] def dropFromMemory[T: ClassTag](
      blockId: BlockId,
      data: () => Either[Array[T], ChunkedByteBuffer]): StorageLevel
}

/**
 * Stores blocks in memory, either as Arrays of deserialized Java objects or as
 * serialized ByteBuffers.
 *
 * @param conf SparkConf
 * @param blockInfoManager 即Block信息管理器BlockInfoManager
 * @param serializerManager 即序列化管理器SerializerManager
 * @param memoryManager 即内存管理器MemoryManager。MemoryStore存储Block，使用的就是MemoryManager内的maxOnHeapStorageMemory和maxOffHeapStorageMemory两块内存池
 * @param blockEvictionHandler Block驱逐处理器。blockEvictionHandler用于将Block从内存中驱逐出去。
 *                             blockEvictionHandler的类型是BlockEvictionHandler，
 *                             BlockEvictionHandler定义了将对象从内存中移除的接口
 *                             本质上就是BlockManager
 */
private[spark] class MemoryStore(
    conf: SparkConf,
    blockInfoManager: BlockInfoManager,
    serializerManager: SerializerManager,
    memoryManager: MemoryManager,
    blockEvictionHandler: BlockEvictionHandler)
  extends Logging {

  // Note: all changes to memory allocations, notably putting blocks, evicting blocks, and
  // acquiring or releasing unroll memory, must be synchronized on `memoryManager`!

  private val entries = new LinkedHashMap[BlockId, MemoryEntry[_]](32, 0.75f, true)

  // A mapping from taskAttemptId to amount of memory used for unrolling a block (in bytes)
  // All accesses of this map are assumed to have manually synchronized on `memoryManager`
  private val onHeapUnrollMemoryMap = mutable.HashMap[Long, Long]()
  // Note: off-heap unroll memory is only used in putIteratorAsBytes() because off-heap caching
  // always stores serialized values.
  private val offHeapUnrollMemoryMap = mutable.HashMap[Long, Long]()

  // Initial memory to request before unrolling any block
  private val unrollMemoryThreshold: Long =
    conf.getLong("spark.storage.unrollMemoryThreshold", 1024 * 1024)

  /** Total amount of memory available for storage, in bytes.
   * MemoryStore用于存储Block的最大内存，其实质为MemoryManger的maxOnHeapStorageMemory和maxOffHeapStorageMemory之和。
   * 如果MemoryManager为StaticMemoryManager，那么maxMemory的大小是固定的。
   * 如果MemoryManager为UnifiedMemoryManager，那么 maxMemory的大小是动态变化的
   *
   * */
  private def maxMemory: Long = {
    memoryManager.maxOnHeapStorageMemory + memoryManager.maxOffHeapStorageMemory
  }

  if (maxMemory < unrollMemoryThreshold) {
    logWarning(s"Max memory ${Utils.bytesToString(maxMemory)} is less than the initial memory " +
      s"threshold ${Utils.bytesToString(unrollMemoryThreshold)} needed to store a block in " +
      s"memory. Please configure Spark with more memory.")
  }

  logInfo("MemoryStore started with capacity %s".format(Utils.bytesToString(maxMemory)))

  /** Total storage memory used including unroll memory, in bytes. */
  private def memoryUsed: Long = memoryManager.storageMemoryUsed

  /**
   * Amount of storage memory, in bytes, used for caching blocks.
   * This does not include memory used for unrolling.
   */
  private def blocksMemoryUsed: Long = memoryManager.synchronized {
    memoryUsed - currentUnrollMemory
  }

  /**
   * 用于获取BlockId对应MemoryEntry（即Block的内存形式）所占用的大小
   * @param blockId
   * @return
   */
  def getSize(blockId: BlockId): Long = {
    entries.synchronized {
      entries.get(blockId).size
    }
  }

  /**
   * Use `size` to test if there is enough space in MemoryStore. If so, create the ByteBuffer and
   * put it into MemoryStore. Otherwise, the ByteBuffer won't be created.
   *
   * The caller should guarantee that `size` is correct.
   *
   * @return true if the put() succeeded, false otherwise.
   *
   *         将BlockId对应的Block（已经封装为ChunkedByteBuffer）写入内存
   *         1）从MemoryManager中获取用于存储BlockId对应的Block的逻辑内存。如果获取失败则返回false，否则进入下一步
   *         2）调用_bytes函数，获取Block的数据，即ChunkedByteBuffer
   *         3）创建Block对应的SerializedMemoryEntry
   *         4）将SerializedMemoryEntry放入entries缓存
   *         5）返回true
   *
   */
  def putBytes[T: ClassTag](
      blockId: BlockId,
      size: Long,
      memoryMode: MemoryMode,
      _bytes: () => ChunkedByteBuffer): Boolean = {
    require(!contains(blockId), s"Block $blockId is already present in the MemoryStore")
    if (memoryManager.acquireStorageMemory(blockId, size, memoryMode)) {
      // We acquired enough memory for the block, so go ahead and put it
      val bytes = _bytes()
      assert(bytes.size == size)
      val entry = new SerializedMemoryEntry[T](bytes, memoryMode, implicitly[ClassTag[T]])
      entries.synchronized {
        entries.put(blockId, entry)
      }
      logInfo("Block %s stored as bytes in memory (estimated size %s, free %s)".format(
        blockId, Utils.bytesToString(size), Utils.bytesToString(maxMemory - blocksMemoryUsed)))
      true
    } else {
      false
    }
  }

  /**
   * Attempt to put the given block in memory store as values.
   * 尝试将BlockId对应的Block（已经转换为Iterator）写入内存。
   *
   * It's possible that the iterator is too large to materialize and store in memory. To avoid
   * OOM exceptions, this method will gradually unroll the iterator while periodically checking
   * whether there is enough free memory. If the block is successfully materialized, then the
   * temporary unroll memory used during the materialization is "transferred" to storage memory,
   * so we won't acquire more memory than is actually needed to store the block.
   *
   * 有时候放入内存的Block很大，所以一次性将此对象写入内存可能将引发OOM异常。
   * 为了避免这种情况的发生，首先需要 将Block转换为Iterator，
   * 然后渐进式地展开此Iterator，并且周期性地检查是否有足够的展开内存。
   * @return in case of success, the estimated size of the stored data. In case of failure, return
   *         an iterator containing the values of the block. The returned iterator will be backed
   *         by the combination of the partially-unrolled block and the remaining elements of the
   *         original input iterator. The caller must either fully consume this iterator or call
   *         `close()` on it in order to free the storage memory consumed by the partially-unrolled
   *         block.
   */
  private[storage] def putIteratorAsValues[T](
      blockId: BlockId,
      values: Iterator[T],
      classTag: ClassTag[T]): Either[PartiallyUnrolledIterator[T], Long] = {

    require(!contains(blockId), s"Block $blockId is already present in the MemoryStore")

    // Number of elements unrolled so far 目前为止已经展开的元素数量
    var elementsUnrolled = 0
    // Whether there is still enough memory for us to continue unrolling this block
    //MemoryStore是否仍然有足够的内存，以便于继续展开Block(即Iterator)
    var keepUnrolling = true
    // Initial per-task memory to request for unrolling blocks (bytes).
    // 即unrollMemoryThreshold。用来展开任何Block之前，初始请求的内存大小，可以修改属性 spark.storage.unrollMemoryThreshold（默认为1MB）改变大小
    val initialMemoryThreshold = unrollMemoryThreshold
    // How often to check whether we need to request more memory
    // 检查内存是否有足够的阀值，此值固定为16。字面上有周期的含义，但是此周期并非指时间，而是已经展开的元素的数量 elementsUnrolled。
    val memoryCheckPeriod = 16
    // Memory currently reserved by this task for this particular unrolling operation
    // 当前任务用于展开Block所保留的内存
    var memoryThreshold = initialMemoryThreshold
    // Memory to request as a multiple of current vector size
    // 展开内存不充足时，请求增长的因为。此值固定为1.5。
    val memoryGrowthFactor = 1.5
    // Keep track of unroll memory used by this particular block / putIterator() operation
    // Block已经使用的展开内存大小，初始大小为initialMemoryThreshold
    var unrollMemoryUsedByThisBlock = 0L
    // Underlying vector for unrolling the block
    // 用于追踪Block（即Iterator）每次迭代的数据。
    var vector = new SizeTrackingVector[T]()(classTag)

    // Request enough memory to begin unrolling
    // 先申请一次用于unroll的内存
    keepUnrolling =
      reserveUnrollMemoryForThisTask(blockId, initialMemoryThreshold, MemoryMode.ON_HEAP)

    if (!keepUnrolling) {
      logWarning(s"Failed to reserve initial memory threshold of " +
        s"${Utils.bytesToString(initialMemoryThreshold)} for computing block $blockId in memory.")
    } else { //如果申请成功了，那么让unroll已经使用的内存等于初始的unroll预留内存(即1mb)
      unrollMemoryUsedByThisBlock += initialMemoryThreshold
    }

    // Unroll this block safely, checking whether we have exceeded our threshold periodically
    // 不断迭代读取Iterator中的数据，将数据放入追踪器vector中
    while (values.hasNext && keepUnrolling) {
      vector += values.next()
      if (elementsUnrolled % memoryCheckPeriod == 0) { //周期性地检查,每迭代16个元素为一个周期
        // If our vector's size has exceeded the threshold, request more memory
        // 这里估算一下vector已经占用的内存大小
        val currentSize = vector.estimateSize()
        if (currentSize >= memoryThreshold) { //如果vector占用的内存等于或者超过了预留内存(第一次为1mb)，那么需要再次申请用于unroll的内存
          // 计算需要申请多少内存，公式为：当前追踪到的vector的内存 * 1.5 - 初始预留的内存
          val amountToRequest = (currentSize * memoryGrowthFactor - memoryThreshold).toLong
          // 开始真正的申请预留内存
          keepUnrolling =
            reserveUnrollMemoryForThisTask(blockId, amountToRequest, MemoryMode.ON_HEAP)
          //如果申请成功，继续累加unroll已经使用掉的内存
          if (keepUnrolling) {
            unrollMemoryUsedByThisBlock += amountToRequest
          }
          // New threshold is currentSize * memoryGrowthFactor
          // 更新阈值
          memoryThreshold += amountToRequest
        }
      }
      //unroll次数+1
      elementsUnrolled += 1
    }

    if (keepUnrolling) { //如果展开Iterator中所有的数据后，keepUnrolling为true，则说明已经为Block申请到足够多的保留内存
      // We successfully unrolled the entirety of this block
      val arrayValues = vector.toArray
      vector = null
//      将vector中的数据封装为DeserializedMemoryEntry，并重新估算vector的大小size
      val entry =
        new DeserializedMemoryEntry[T](arrayValues, SizeEstimator.estimate(arrayValues), classTag)
      val size = entry.size
      def transferUnrollToStorage(amount: Long): Unit = {//将展开Block的内存转换为存储Block的内存
        // Synchronize so that transfer is atomic
        memoryManager.synchronized {
          releaseUnrollMemoryForThisTask(MemoryMode.ON_HEAP, amount)
          val success = memoryManager.acquireStorageMemory(blockId, amount, MemoryMode.ON_HEAP)
          assert(success, "transferring unroll memory to storage memory failed")
        }
      }
      // Acquire storage memory if necessary to store this block in memory.
      val enoughStorageMemory = {
        if (unrollMemoryUsedByThisBlock <= size) {
          val acquiredExtra =
            memoryManager.acquireStorageMemory(
              blockId, size - unrollMemoryUsedByThisBlock, MemoryMode.ON_HEAP)
          if (acquiredExtra) {
            transferUnrollToStorage(unrollMemoryUsedByThisBlock)
          }
          acquiredExtra
        } else { // unrollMemoryUsedByThisBlock > size
          // If this task attempt already owns more unroll memory than is necessary to store the
          // block, then release the extra memory that will not be used.
          val excessUnrollMemory = unrollMemoryUsedByThisBlock - size
          releaseUnrollMemoryForThisTask(MemoryMode.ON_HEAP, excessUnrollMemory)
          transferUnrollToStorage(size)
          true
        }
      }
      if (enoughStorageMemory) {//如果有足够的内存存储Block，则将BlockId与DeserializedMemoryEntry的映射关系放入entries并返回Right(size)
        entries.synchronized {
          entries.put(blockId, entry)
        }
        logInfo("Block %s stored as values in memory (estimated size %s, free %s)".format(
          blockId, Utils.bytesToString(size), Utils.bytesToString(maxMemory - blocksMemoryUsed)))
        Right(size)
      } else {//如果没有足够的内存存储Block，则创建PartiallyUnrollidIterator并返回Letf
        assert(currentUnrollMemoryForThisTask >= unrollMemoryUsedByThisBlock,
          "released too much unroll memory")
        Left(new PartiallyUnrolledIterator(
          this,
          MemoryMode.ON_HEAP,
          unrollMemoryUsedByThisBlock,
          unrolled = arrayValues.toIterator,
          rest = Iterator.empty))
      }
    } else {//如果展开Iterator中所有的数据后，keepUnrolling为false，说明没有为Block申请到足够多的保留内存，此时将创建PartiallyUnrolledIterator并返回Left。
      // We ran out of space while unrolling the values for this block
      logUnrollFailureMessage(blockId, vector.estimateSize())
      Left(new PartiallyUnrolledIterator(
        this,
        MemoryMode.ON_HEAP,
        unrollMemoryUsedByThisBlock,
        unrolled = vector.iterator,
        rest = values))
    }
  }

  /**
   * Attempt to put the given block in memory store as bytes.
   *
   * It's possible that the iterator is too large to materialize and store in memory. To avoid
   * OOM exceptions, this method will gradually unroll the iterator while periodically checking
   * whether there is enough free memory. If the block is successfully materialized, then the
   * temporary unroll memory used during the materialization is "transferred" to storage memory,
   * so we won't acquire more memory than is actually needed to store the block.
   *
   * @return in case of success, the estimated size of the stored data. In case of failure,
   *         return a handle which allows the caller to either finish the serialization by
   *         spilling to disk or to deserialize the partially-serialized block and reconstruct
   *         the original input iterator. The caller must either fully consume this result
   *         iterator or call `discard()` on it in order to free the storage memory consumed by the
   *         partially-unrolled block.
   */
  private[storage] def putIteratorAsBytes[T](
      blockId: BlockId,
      values: Iterator[T],
      classTag: ClassTag[T],
      memoryMode: MemoryMode): Either[PartiallySerializedBlock[T], Long] = {

    require(!contains(blockId), s"Block $blockId is already present in the MemoryStore")

    val allocator = memoryMode match {
      case MemoryMode.ON_HEAP => ByteBuffer.allocate _
      case MemoryMode.OFF_HEAP => Platform.allocateDirectBuffer _
    }

    // Whether there is still enough memory for us to continue unrolling this block
    var keepUnrolling = true
    // Initial per-task memory to request for unrolling blocks (bytes).
    val initialMemoryThreshold = unrollMemoryThreshold
    // Keep track of unroll memory used by this particular block / putIterator() operation
    var unrollMemoryUsedByThisBlock = 0L
    // Underlying buffer for unrolling the block
    val redirectableStream = new RedirectableOutputStream
    val chunkSize = if (initialMemoryThreshold > Int.MaxValue) {
      logWarning(s"Initial memory threshold of ${Utils.bytesToString(initialMemoryThreshold)} " +
        s"is too large to be set as chunk size. Chunk size has been capped to " +
        s"${Utils.bytesToString(Int.MaxValue)}")
      Int.MaxValue
    } else {
      initialMemoryThreshold.toInt
    }
    val bbos = new ChunkedByteBufferOutputStream(chunkSize, allocator)
    redirectableStream.setOutputStream(bbos)
    val serializationStream: SerializationStream = {
      val autoPick = !blockId.isInstanceOf[StreamBlockId]
      val ser = serializerManager.getSerializer(classTag, autoPick).newInstance()
      ser.serializeStream(serializerManager.wrapForCompression(blockId, redirectableStream))
    }

    // Request enough memory to begin unrolling
    keepUnrolling = reserveUnrollMemoryForThisTask(blockId, initialMemoryThreshold, memoryMode)

    if (!keepUnrolling) {
      logWarning(s"Failed to reserve initial memory threshold of " +
        s"${Utils.bytesToString(initialMemoryThreshold)} for computing block $blockId in memory.")
    } else {
      unrollMemoryUsedByThisBlock += initialMemoryThreshold
    }

    def reserveAdditionalMemoryIfNecessary(): Unit = {
      if (bbos.size > unrollMemoryUsedByThisBlock) {
        val amountToRequest = bbos.size - unrollMemoryUsedByThisBlock
        keepUnrolling = reserveUnrollMemoryForThisTask(blockId, amountToRequest, memoryMode)
        if (keepUnrolling) {
          unrollMemoryUsedByThisBlock += amountToRequest
        }
      }
    }

    // Unroll this block safely, checking whether we have exceeded our threshold
    while (values.hasNext && keepUnrolling) {
      serializationStream.writeObject(values.next())(classTag)
      reserveAdditionalMemoryIfNecessary()
    }

    // Make sure that we have enough memory to store the block. By this point, it is possible that
    // the block's actual memory usage has exceeded the unroll memory by a small amount, so we
    // perform one final call to attempt to allocate additional memory if necessary.
    if (keepUnrolling) {
      serializationStream.close()
      reserveAdditionalMemoryIfNecessary()
    }

    if (keepUnrolling) {
      val entry = SerializedMemoryEntry[T](bbos.toChunkedByteBuffer, memoryMode, classTag)
      // Synchronize so that transfer is atomic
      memoryManager.synchronized {
        releaseUnrollMemoryForThisTask(memoryMode, unrollMemoryUsedByThisBlock)
        val success = memoryManager.acquireStorageMemory(blockId, entry.size, memoryMode)
        assert(success, "transferring unroll memory to storage memory failed")
      }
      entries.synchronized {
        entries.put(blockId, entry)
      }
      logInfo("Block %s stored as bytes in memory (estimated size %s, free %s)".format(
        blockId, Utils.bytesToString(entry.size),
        Utils.bytesToString(maxMemory - blocksMemoryUsed)))
      Right(entry.size)
    } else {
      // We ran out of space while unrolling the values for this block
      logUnrollFailureMessage(blockId, bbos.size)
      Left(
        new PartiallySerializedBlock(
          this,
          serializerManager,
          blockId,
          serializationStream,
          redirectableStream,
          unrollMemoryUsedByThisBlock,
          memoryMode,
          bbos,
          values,
          classTag))
    }
  }

  /**
   * 从内存中读取BlockId对应的Block（已经封装为 ChunkedByteBuffer）
   * getBytes只能获取序列化的Block
   * @param blockId
   * @return
   */
  def getBytes(blockId: BlockId): Option[ChunkedByteBuffer] = {
    val entry = entries.synchronized { entries.get(blockId) }
    entry match {
      case null => None
      case e: DeserializedMemoryEntry[_] =>
        throw new IllegalArgumentException("should only call getBytes on serialized blocks")
      case SerializedMemoryEntry(bytes, _, _) => Some(bytes)
    }
  }

  /**
   * 从内存中读取BlockId对应的Block（已经封装为Iterator）
   * 只能获取非序列化的block
   * @param blockId
   * @return
   */
  def getValues(blockId: BlockId): Option[Iterator[_]] = {
    val entry = entries.synchronized { entries.get(blockId) }
    entry match {
      case null => None
      case e: SerializedMemoryEntry[_] =>
        throw new IllegalArgumentException("should only call getValues on deserialized blocks")
      case DeserializedMemoryEntry(values, _, _) =>
        val x = Some(values)
        x.map(_.iterator)
    }
  }

  /**
   * 从内存中移除BlockId对应的Block
   * 1）将BlockId对应的MemoryEntry从entries中移除，如果entries中存在BlockId对应的MemoryEntry，则进入第2步，否则返回false
   * 2）如果MemoryEntry是SerializedMemoryEntry，则还要将对应的ChunkedByteBuffer清理
   * 3）调用MemoryManager的releaseStorageMemory方法，释放使用的存储内存
   * 4）返回true
   *
   * @param blockId
   * @return
   */
  def remove(blockId: BlockId): Boolean = memoryManager.synchronized {
    val entry = entries.synchronized {
      entries.remove(blockId)
    }
    if (entry != null) {
      entry match {
        case SerializedMemoryEntry(buffer, _, _) => buffer.dispose()
        case _ =>
      }
      memoryManager.releaseStorageMemory(entry.size, entry.memoryMode)
      logDebug(s"Block $blockId of size ${entry.size} dropped " +
        s"from memory (free ${maxMemory - blocksMemoryUsed})")
      true
    } else {
      false
    }
  }

  def clear(): Unit = memoryManager.synchronized {
    entries.synchronized {
      entries.clear()
    }
    onHeapUnrollMemoryMap.clear()
    offHeapUnrollMemoryMap.clear()
    memoryManager.releaseAllStorageMemory()
    logInfo("MemoryStore cleared")
  }

  /**
   * Return the RDD ID that a given block ID is from, or None if it is not an RDD block.
   */
  private def getRddId(blockId: BlockId): Option[Int] = {
    blockId.asRDDId.map(_.rddId)
  }

  /**
   * Try to evict blocks to free up a given amount of space to store a particular block.
   * Can fail if either the block is bigger than our memory or it would require replacing
   * another block from the same RDD (which leads to a wasteful cyclic replacement pattern for
   * RDDs that don't fit into memory that we want to avoid).
   * 尝试移除块以释放一定数量的空间来存储特定块.
   * 以下两种情况会导致移除失败
   * 1. 要放入的block所需内存大于要移除的block内存
   * 2. 需要从同一个RDD中替换另一个块(这导致RDD的循环替换模式并且浪费内存)
   * @param blockId the ID of the block we are freeing space for, if any 要存储的block的blockId
   * @param space the size of this block 驱逐block所腾出的内存大小
   * @param memoryMode the type of memory to free (on- or off-heap) 存储block所需的内存模式
   * @return the amount of memory (in bytes) freed by eviction
   */
  private[spark] def evictBlocksToFreeSpace(
      blockId: Option[BlockId],
      space: Long,
      memoryMode: MemoryMode): Long = {
    assert(space > 0)
    memoryManager.synchronized {
      var freedMemory = 0L //已经释放的内存大小
      val rddToAdd = blockId.flatMap(getRddId) // 获取要添加的RDD的RDDBlockId的rddId(Int型)。rddToAdd实际是通过对BlockId应用getRddId方法得到的
      val selectedBlocks = new ArrayBuffer[BlockId]// 已经选择的用于驱逐的Block的BlockId的数组
      def blockIsEvictable(blockId: BlockId, entry: MemoryEntry[_]): Boolean = {
        // 1. MemoryEntry的内存模式与所需的内存模式一致(即所需的内存模式需要和驱逐的block的内存模式一致)
        // 2. 要放入的BlockId对应的Block不是RDD，或者要放入的和要驱逐的block不是同一个RDD
        entry.memoryMode == memoryMode && (rddToAdd.isEmpty || rddToAdd != getRddId(blockId))
      }
      // This is synchronized to ensure that the set of entries is not changed
      // (because of getValue or getBytes) while traversing the iterator, as that
      // can lead to exceptions.
      entries.synchronized {
        val iterator = entries.entrySet().iterator()
        while (freedMemory < space && iterator.hasNext) {//已经释放的内存大小 < 要驱逐的block所占内存大小，即还有block要驱逐
          // 不断迭代获取entries中的数据，<blockId,MemoryEntry>
          val pair = iterator.next()
          val blockId = pair.getKey
          val entry = pair.getValue
          if (blockIsEvictable(blockId, entry)) {//找出符合条件的block，只有符合该条件的block才会被驱逐
            // We don't want to evict blocks which are currently being read, so we need to obtain
            // an exclusive write lock on blocks which are candidates for eviction. We perform a
            // non-blocking "tryLock" here in order to ignore blocks which are locked for reading:
            //获取block的写锁，然后将此Block的BlockId放入selectedBlocks并且将freedMemory增加Block的大小
            if (blockInfoManager.lockForWriting(blockId, blocking = false).isDefined) {
              selectedBlocks += blockId
              freedMemory += pair.getValue.size
            }
          }
        }
      }

      def dropBlock[T](blockId: BlockId, entry: MemoryEntry[T]): Unit = {
        val data = entry match {
          case DeserializedMemoryEntry(values, _, _) => Left(values)
          case SerializedMemoryEntry(buffer, _, _) => Right(buffer)
        }
//        如果Block从内存中迁移到其它存储（如DiskStore）中
        val newEffectiveStorageLevel =
          blockEvictionHandler.dropFromMemory(blockId, () => data)(entry.classTag)
//        那么需要调用BlockInfoManager的unlock()从当前任务尝试线程获取被迁移的Block的写锁
        if (newEffectiveStorageLevel.isValid) {
          // The block is still present in at least one store, so release the lock
          // but don't delete the block info
          blockInfoManager.unlock(blockId)
        } else {
          // The block isn't present in any store, so delete the block info so that the
          // block can be stored again
          // 如果Block从存储体系中彻底移除，那么需要调用BlockInfoManager的removeBlock方法删除被迁移Block的信息。
          blockInfoManager.removeBlock(blockId)
        }
      }

      //经过第1步的处理，如果freedMemory大于等于space，这说明通过驱逐一定数据的Block，已经为存储BlockId对应的Block腾出了足够的内存空间，
      // 此时需要遍历selectedBlocks中的每个BlockId，并移除每个BlockId对应的Block。
      if (freedMemory >= space) {
        var lastSuccessfulBlock = -1
        try {
          logInfo(s"${selectedBlocks.size} blocks selected for dropping " +
            s"(${Utils.bytesToString(freedMemory)} bytes)")
          (0 until selectedBlocks.size).foreach { idx =>
            val blockId = selectedBlocks(idx)
            val entry = entries.synchronized {
              entries.get(blockId)
            }
            // This should never be null as only one task should be dropping
            // blocks and removing entries. However the check is still here for
            // future safety.
            if (entry != null) {
              dropBlock(blockId, entry)
              afterDropAction(blockId)
            }
            lastSuccessfulBlock = idx
          }
          logInfo(s"After dropping ${selectedBlocks.size} blocks, " +
            s"free memory is ${Utils.bytesToString(maxMemory - blocksMemoryUsed)}")
          freedMemory
        } finally {
          // like BlockManager.doPut, we use a finally rather than a catch to avoid having to deal
          // with InterruptedException
          if (lastSuccessfulBlock != selectedBlocks.size - 1) {
            // the blocks we didn't process successfully are still locked, so we have to unlock them
            (lastSuccessfulBlock + 1 until selectedBlocks.size).foreach { idx =>
              val blockId = selectedBlocks(idx)
              blockInfoManager.unlock(blockId)
            }
          }
        }
      } else {
        blockId.foreach { id =>
          logInfo(s"Will not store $id")
        }
        selectedBlocks.foreach { id =>
          blockInfoManager.unlock(id)
        }
        0L
      }
    }
  }

  // hook for testing, so we can simulate a race
  protected def afterDropAction(blockId: BlockId): Unit = {}

  /**
   * 用于判断本地MemoryStore中是否包含给定的BlockId所应对的Block文件
   * @param blockId
   * @return
   */
  def contains(blockId: BlockId): Boolean = {
    entries.synchronized { entries.containsKey(blockId) }
  }

  private def currentTaskAttemptId(): Long = {
    // In case this is called on the driver, return an invalid task attempt id.
    Option(TaskContext.get()).map(_.taskAttemptId()).getOrElse(-1L)
  }

  /**
   * Reserve memory for unrolling the given block for this task.
   * 为展开尝试执行任务给定的Block 预留或申请 指定内存模式上指定大小的内存
   * unroll就是从指定partition上利用iterator获取非连续存储的数据然后生成block（连续存储）
   * @return whether the request is granted.
   */
  def reserveUnrollMemoryForThisTask(
      blockId: BlockId,
      memory: Long,
      memoryMode: MemoryMode): Boolean = {
    memoryManager.synchronized {
      //调用MemoryManager的acquireUnrollMemory方法获取展开内存
      val success = memoryManager.acquireUnrollMemory(blockId, memory, memoryMode)
      if (success) {
        //如果获取内存成功，则更新 taskAttemptId(当前任务ID) -> 当前任务尝试线程在堆内或堆外内存展开的所有Block占用的内存大小总和 的映射关系
        val taskAttemptId = currentTaskAttemptId()
        val unrollMemoryMap = memoryMode match {
          case MemoryMode.ON_HEAP => onHeapUnrollMemoryMap
          case MemoryMode.OFF_HEAP => offHeapUnrollMemoryMap
        }
        unrollMemoryMap(taskAttemptId) = unrollMemoryMap.getOrElse(taskAttemptId, 0L) + memory
      }
      //返回是否获取成功
      success
    }
  }

  /**
   * Release memory used by this task for unrolling blocks.
   * If the amount is not specified, remove the current task's allocation altogether.
   * 用于释放任务尝试线程占用的内存
   */
  def releaseUnrollMemoryForThisTask(memoryMode: MemoryMode, memory: Long = Long.MaxValue): Unit = {
    val taskAttemptId = currentTaskAttemptId()
    memoryManager.synchronized {
      val unrollMemoryMap = memoryMode match {
        case MemoryMode.ON_HEAP => onHeapUnrollMemoryMap
        case MemoryMode.OFF_HEAP => offHeapUnrollMemoryMap
      }
      if (unrollMemoryMap.contains(taskAttemptId)) {
        //计算实际要释放的内存大小，此大小为指定要释放的大小与任务尝试线程在堆内或堆外内存实际占有的内存大小之和之间的最小值
        val memoryToRelease = math.min(memory, unrollMemoryMap(taskAttemptId))
        if (memoryToRelease > 0) {
          // 更新taskAttemptId与任务尝试线程在堆内存或堆外内存展开的所有Block占用的内存大小之和之间的映射关系(即减掉要释放的最小内存)
          unrollMemoryMap(taskAttemptId) -= memoryToRelease
          //调用 MemoryManager 的 releaseUnrollMemory 方法释放内存
          memoryManager.releaseUnrollMemory(memoryToRelease, memoryMode)
        }
        //如果任务尝试线程在堆内存或堆外在展开的所有Block占用的内存大小之和为0(即此任务已经不占用unroll内存了)，
        // 则清除此taskAttemptId与任务尝试线程在堆内或堆外内存展开的所有Block占用的内存大小之和之间的映射关系
        if (unrollMemoryMap(taskAttemptId) == 0) {
          unrollMemoryMap.remove(taskAttemptId)
        }
      }
    }
  }

  /**
   * Return the amount of memory currently occupied for unrolling blocks across all tasks.
   * RDD 在缓存到存储内存之前，Partition 中的数据一般以迭代器（Iterator）的数据结构来访问，这是 Scala 语言中一种遍历数据集合的方法。
   * 通过 Iterator 可以获取分区中每一条序列化或者非序列化的数据项(Record)，这些 Record 的对象实例在逻辑上占用了
   * JVM 堆内内存的 other 部分的空间，同一 Partition 的不同 Record 的空间并不连续。
   *
   * RDD 在缓存到存储内存之后，Partition 被转换成 Block，Record 在堆内或堆外存储内存中占用一块连续的空间。
   * 将Partition由不连续的存储空间转换为连续存储空间的过程，Spark称之为"展开"（Unroll）
   *
   * 展开Block的行为类似于人们生活中的“占座”，一间教室里有些座位有人，有些则穿着，在座位上放一本书表示有人正在使用，那么别人就不会坐。
   * 这样可以防止在向内存真正写入数据时，内存不足发生溢出。
   */
  def currentUnrollMemory: Long = memoryManager.synchronized {
    onHeapUnrollMemoryMap.values.sum + offHeapUnrollMemoryMap.values.sum
  }

  /**
   * Return the amount of memory currently occupied for unrolling blocks by this task.
   */
  def currentUnrollMemoryForThisTask: Long = memoryManager.synchronized {
    onHeapUnrollMemoryMap.getOrElse(currentTaskAttemptId(), 0L) +
      offHeapUnrollMemoryMap.getOrElse(currentTaskAttemptId(), 0L)
  }

  /**
   * Return the number of tasks currently unrolling blocks.
   */
  private def numTasksUnrolling: Int = memoryManager.synchronized {
    (onHeapUnrollMemoryMap.keys ++ offHeapUnrollMemoryMap.keys).toSet.size
  }

  /**
   * Log information about current memory usage.
   */
  private def logMemoryUsage(): Unit = {
    logInfo(
      s"Memory use = ${Utils.bytesToString(blocksMemoryUsed)} (blocks) + " +
      s"${Utils.bytesToString(currentUnrollMemory)} (scratch space shared across " +
      s"$numTasksUnrolling tasks(s)) = ${Utils.bytesToString(memoryUsed)}. " +
      s"Storage limit = ${Utils.bytesToString(maxMemory)}."
    )
  }

  /**
   * Log a warning for failing to unroll a block.
   *
   * @param blockId ID of the block we are trying to unroll.
   * @param finalVectorSize Final size of the vector before unrolling failed.
   */
  private def logUnrollFailureMessage(blockId: BlockId, finalVectorSize: Long): Unit = {
    logWarning(
      s"Not enough space to cache $blockId in memory! " +
      s"(computed ${Utils.bytesToString(finalVectorSize)} so far)"
    )
    logMemoryUsage()
  }
}

/**
 * The result of a failed [[MemoryStore.putIteratorAsValues()]] call.
 *
 * @param memoryStore  the memoryStore, used for freeing memory.
 * @param memoryMode   the memory mode (on- or off-heap).
 * @param unrollMemory the amount of unroll memory used by the values in `unrolled`.
 * @param unrolled     an iterator for the partially-unrolled values.
 * @param rest         the rest of the original iterator passed to
 *                     [[MemoryStore.putIteratorAsValues()]].
 */
private[storage] class PartiallyUnrolledIterator[T](
    memoryStore: MemoryStore,
    memoryMode: MemoryMode,
    unrollMemory: Long,
    private[this] var unrolled: Iterator[T],
    rest: Iterator[T])
  extends Iterator[T] {

  private def releaseUnrollMemory(): Unit = {
    memoryStore.releaseUnrollMemoryForThisTask(memoryMode, unrollMemory)
    // SPARK-17503: Garbage collects the unrolling memory before the life end of
    // PartiallyUnrolledIterator.
    unrolled = null
  }

  override def hasNext: Boolean = {
    if (unrolled == null) {
      rest.hasNext
    } else if (!unrolled.hasNext) {
      releaseUnrollMemory()
      rest.hasNext
    } else {
      true
    }
  }

  override def next(): T = {
    if (unrolled == null || !unrolled.hasNext) {
      rest.next()
    } else {
      unrolled.next()
    }
  }

  /**
   * Called to dispose of this iterator and free its memory.
   */
  def close(): Unit = {
    if (unrolled != null) {
      releaseUnrollMemory()
    }
  }
}

/**
 * A wrapper which allows an open [[OutputStream]] to be redirected to a different sink.
 */
private[storage] class RedirectableOutputStream extends OutputStream {
  private[this] var os: OutputStream = _
  def setOutputStream(s: OutputStream): Unit = { os = s }
  override def write(b: Int): Unit = os.write(b)
  override def write(b: Array[Byte]): Unit = os.write(b)
  override def write(b: Array[Byte], off: Int, len: Int): Unit = os.write(b, off, len)
  override def flush(): Unit = os.flush()
  override def close(): Unit = os.close()
}

/**
 * The result of a failed [[MemoryStore.putIteratorAsBytes()]] call.
 *
 * @param memoryStore the MemoryStore, used for freeing memory.
 * @param serializerManager the SerializerManager, used for deserializing values.
 * @param blockId the block id.
 * @param serializationStream a serialization stream which writes to [[redirectableOutputStream]].
 * @param redirectableOutputStream an OutputStream which can be redirected to a different sink.
 * @param unrollMemory the amount of unroll memory used by the values in `unrolled`.
 * @param memoryMode whether the unroll memory is on- or off-heap
 * @param bbos byte buffer output stream containing the partially-serialized values.
 *                     [[redirectableOutputStream]] initially points to this output stream.
 * @param rest         the rest of the original iterator passed to
 *                     [[MemoryStore.putIteratorAsValues()]].
 * @param classTag the [[ClassTag]] for the block.
 */
private[storage] class PartiallySerializedBlock[T](
    memoryStore: MemoryStore,
    serializerManager: SerializerManager,
    blockId: BlockId,
    private val serializationStream: SerializationStream,
    private val redirectableOutputStream: RedirectableOutputStream,
    val unrollMemory: Long,
    memoryMode: MemoryMode,
    bbos: ChunkedByteBufferOutputStream,
    rest: Iterator[T],
    classTag: ClassTag[T]) {

  private lazy val unrolledBuffer: ChunkedByteBuffer = {
    bbos.close()
    bbos.toChunkedByteBuffer
  }

  // If the task does not fully consume `valuesIterator` or otherwise fails to consume or dispose of
  // this PartiallySerializedBlock then we risk leaking of direct buffers, so we use a task
  // completion listener here in order to ensure that `unrolled.dispose()` is called at least once.
  // The dispose() method is idempotent, so it's safe to call it unconditionally.
  Option(TaskContext.get()).foreach { taskContext =>
    taskContext.addTaskCompletionListener { _ =>
      // When a task completes, its unroll memory will automatically be freed. Thus we do not call
      // releaseUnrollMemoryForThisTask() here because we want to avoid double-freeing.
      unrolledBuffer.dispose()
    }
  }

  // Exposed for testing
  private[storage] def getUnrolledChunkedByteBuffer: ChunkedByteBuffer = unrolledBuffer

  private[this] var discarded = false
  private[this] var consumed = false

  private def verifyNotConsumedAndNotDiscarded(): Unit = {
    if (consumed) {
      throw new IllegalStateException(
        "Can only call one of finishWritingToStream() or valuesIterator() and can only call once.")
    }
    if (discarded) {
      throw new IllegalStateException("Cannot call methods on a discarded PartiallySerializedBlock")
    }
  }

  /**
   * Called to dispose of this block and free its memory.
   */
  def discard(): Unit = {
    if (!discarded) {
      try {
        // We want to close the output stream in order to free any resources associated with the
        // serializer itself (such as Kryo's internal buffers). close() might cause data to be
        // written, so redirect the output stream to discard that data.
        redirectableOutputStream.setOutputStream(ByteStreams.nullOutputStream())
        serializationStream.close()
      } finally {
        discarded = true
        unrolledBuffer.dispose()
        memoryStore.releaseUnrollMemoryForThisTask(memoryMode, unrollMemory)
      }
    }
  }

  /**
   * Finish writing this block to the given output stream by first writing the serialized values
   * and then serializing the values from the original input iterator.
   */
  def finishWritingToStream(os: OutputStream): Unit = {
    verifyNotConsumedAndNotDiscarded()
    consumed = true
    // `unrolled`'s underlying buffers will be freed once this input stream is fully read:
    ByteStreams.copy(unrolledBuffer.toInputStream(dispose = true), os)
    memoryStore.releaseUnrollMemoryForThisTask(memoryMode, unrollMemory)
    redirectableOutputStream.setOutputStream(os)
    while (rest.hasNext) {
      serializationStream.writeObject(rest.next())(classTag)
    }
    serializationStream.close()
  }

  /**
   * Returns an iterator over the values in this block by first deserializing the serialized
   * values and then consuming the rest of the original input iterator.
   *
   * If the caller does not plan to fully consume the resulting iterator then they must call
   * `close()` on it to free its resources.
   */
  def valuesIterator: PartiallyUnrolledIterator[T] = {
    verifyNotConsumedAndNotDiscarded()
    consumed = true
    // Close the serialization stream so that the serializer's internal buffers are freed and any
    // "end-of-stream" markers can be written out so that `unrolled` is a valid serialized stream.
    serializationStream.close()
    // `unrolled`'s underlying buffers will be freed once this input stream is fully read:
    val unrolledIter = serializerManager.dataDeserializeStream(
      blockId, unrolledBuffer.toInputStream(dispose = true))(classTag)
    // The unroll memory will be freed once `unrolledIter` is fully consumed in
    // PartiallyUnrolledIterator. If the iterator is not consumed by the end of the task then any
    // extra unroll memory will automatically be freed by a `finally` block in `Task`.
    new PartiallyUnrolledIterator(
      memoryStore,
      memoryMode,
      unrollMemory,
      unrolled = unrolledIter,
      rest = rest)
  }
}
