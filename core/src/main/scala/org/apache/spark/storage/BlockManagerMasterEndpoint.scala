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

package org.apache.spark.storage

import java.util.{HashMap => JHashMap}

import scala.collection.mutable
import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random

import org.apache.spark.SparkConf
import org.apache.spark.annotation.DeveloperApi
import org.apache.spark.internal.Logging
import org.apache.spark.rpc.{RpcCallContext, RpcEndpointRef, RpcEnv, ThreadSafeRpcEndpoint}
import org.apache.spark.scheduler._
import org.apache.spark.storage.BlockManagerMessages._
import org.apache.spark.util.{ThreadUtils, Utils}

/**
 * BlockManagerMasterEndpoint is an [[ThreadSafeRpcEndpoint]] on the master node to track statuses
 * of all slaves' block managers.
 * 这个RPC-endpoint是在SparkEnv上初始化启动的
 * 其实也是运行在driver上的
 *  负责维护各个executor和BlockManager的元数据 BlockManagerInfo、BlockStatus
 */
private[spark]
class BlockManagerMasterEndpoint(
    override val rpcEnv: RpcEnv,
    val isLocal: Boolean,
    conf: SparkConf,
    listenerBus: LiveListenerBus)
  extends ThreadSafeRpcEndpoint with Logging {

  // Mapping from block manager id to the block manager's information.
  // BlockManagerId->BlockManagerInfo的映射
  private val blockManagerInfo = new mutable.HashMap[BlockManagerId, BlockManagerInfo]

  // Mapping from executor ID to block manager ID.
  // executorId->BlockManagerId的映射
  private val blockManagerIdByExecutor = new mutable.HashMap[String, BlockManagerId]

  // Mapping from block id to the set of block managers that have the block.
  /**
   * 每一个block可能会在多个BlockManager上
   * 根据block的存储级别StoreLevel，设置为_2的，就需要将block 备份到其他BlockManager上
   * blockLocations map 保存了每个blockId的对应的BlockManagerId集合
   * 因为使用的是set存储BlockManagerId，所以自动去重
   */
  private val blockLocations = new JHashMap[BlockId, mutable.HashSet[BlockManagerId]]

  private val askThreadPool = ThreadUtils.newDaemonCachedThreadPool("block-manager-ask-thread-pool")
  private implicit val askExecutionContext = ExecutionContext.fromExecutorService(askThreadPool)

  private val topologyMapper = {
    val topologyMapperClassName = conf.get(
      "spark.storage.replication.topologyMapper", classOf[DefaultTopologyMapper].getName)
    val clazz = Utils.classForName(topologyMapperClassName)
    val mapper =
      clazz.getConstructor(classOf[SparkConf]).newInstance(conf).asInstanceOf[TopologyMapper]
    logInfo(s"Using $topologyMapperClassName for getting topology information")
    mapper
  }

  val proactivelyReplicate = conf.get("spark.storage.replication.proactive", "false").toBoolean

  logInfo("BlockManagerMasterEndpoint up")

  /**
   * slave -> master
   * @param context
   * @return
   */
  override def receiveAndReply(context: RpcCallContext): PartialFunction[Any, Unit] = {
    //接收到来自Executor上的BlockManager注册请求的时候,调用register方法开始注册BlockManager，
    case RegisterBlockManager(blockManagerId, maxOnHeapMemSize, maxOffHeapMemSize, slaveEndpoint) =>
      context.reply(register(blockManagerId, maxOnHeapMemSize, maxOffHeapMemSize, slaveEndpoint))

    // 一个Block的更新消息，BlockId作为一个Block的唯一标识，会保存Block所在的节点和位置关系，以及block 存储级别，大小 占用内存和磁盘大小
    case _updateBlockInfo @
        UpdateBlockInfo(blockManagerId, blockId, storageLevel, deserializedSize, size) =>
      context.reply(updateBlockInfo(blockManagerId, blockId, storageLevel, deserializedSize, size))
      listenerBus.post(SparkListenerBlockUpdated(BlockUpdatedInfo(_updateBlockInfo)))

    // 用于获取指定 blockId 对应的 block 所在的 BlockManagerId 列表
      //一个block有可能在多个节点上存在，返回一个节点列表
    case GetLocations(blockId) =>
      context.reply(getLocations(blockId))

    // 获取多个Block所在的位置，位置中会反映Block位于哪个 executor, host 和端口
    case GetLocationsMultipleBlockIds(blockIds) =>
      context.reply(getLocationsMultipleBlockIds(blockIds))

    case GetPeers(blockManagerId) =>
      context.reply(getPeers(blockManagerId))

    // 根据BlockId,获取所在executorEndpointRef 也就是 BlockManagerSlaveEndpoint的引用
    case GetExecutorEndpointRef(executorId) =>
      context.reply(getExecutorEndpointRef(executorId))

    // 获取所有节点上的BlockManager的最大内存和剩余内存
    case GetMemoryStatus =>
      context.reply(memoryStatus)

    // 获取所有节点上的BlockManager的最大磁盘空间和剩余磁盘空间
    case GetStorageStatus =>
      context.reply(storageStatus)

    // 获取一个Block的状态信息，位置，占用内存和磁盘大小
    case GetBlockStatus(blockId, askSlaves) =>
      context.reply(blockStatus(blockId, askSlaves))

    case GetMatchingBlockIds(filter, askSlaves) =>
      context.reply(getMatchingBlockIds(filter, askSlaves))

    // 删除Rdd对应的Block数据
    case RemoveRdd(rddId) =>
      context.reply(removeRdd(rddId))

    // 删除 shuffleId对应的Block
    case RemoveShuffle(shuffleId) =>
      context.reply(removeShuffle(shuffleId))

    // 删除Broadcast对应的Block数据
    case RemoveBroadcast(broadcastId, removeFromDriver) =>
      context.reply(removeBroadcast(broadcastId, removeFromDriver))

    // 删除一个Block数据，会找到数据所在的slave,然后向slave发送一个删除消息
    case RemoveBlock(blockId) =>
      removeBlockFromWorkers(blockId)
      context.reply(true)

    // 从BlockManagerInfo中删除一个BlockManager, 并且删除这个 BlockManager上的所有的Blocks
    case RemoveExecutor(execId) =>
      removeExecutor(execId)
      context.reply(true)

    // 用于停止 driver 或 executor 端的 BlockManager
    case StopBlockManagerMaster =>
      context.reply(true)
      stop()

    // slave 发送心跳给 master , 证明自己还活着
    case BlockManagerHeartbeat(blockManagerId) =>
      context.reply(heartbeatReceived(blockManagerId))

    // 用于检查 executor 是否有缓存 blocks（广播变量的 blocks 不作考虑，因为广播变量的 block 不会汇报给 Master）
    case HasCachedBlocks(executorId) =>
      blockManagerIdByExecutor.get(executorId) match {
        case Some(bm) =>
          if (blockManagerInfo.contains(bm)) {
            val bmInfo = blockManagerInfo(bm)
            context.reply(bmInfo.cachedBlocks.nonEmpty)
          } else {
            context.reply(false)
          }
        case None => context.reply(false)
      }
  }

  private def removeRdd(rddId: Int): Future[Seq[Int]] = {
    // First remove the metadata for the given RDD, and then asynchronously remove the blocks
    // from the slaves.

    // Find all blocks for the given RDD, remove the block from both blockLocations and
    // the blockManagerInfo that is tracking the blocks.
    val blocks = blockLocations.asScala.keys.flatMap(_.asRDDId).filter(_.rddId == rddId)
    blocks.foreach { blockId =>
      val bms: mutable.HashSet[BlockManagerId] = blockLocations.get(blockId)
      bms.foreach(bm => blockManagerInfo.get(bm).foreach(_.removeBlock(blockId)))
      blockLocations.remove(blockId)
    }

    // Ask the slaves to remove the RDD, and put the result in a sequence of Futures.
    // The dispatcher is used as an implicit argument into the Future sequence construction.
    val removeMsg = RemoveRdd(rddId)
    Future.sequence(
      blockManagerInfo.values.map { bm =>
        bm.slaveEndpoint.ask[Int](removeMsg)
      }.toSeq
    )
  }

  private def removeShuffle(shuffleId: Int): Future[Seq[Boolean]] = {
    // Nothing to do in the BlockManagerMasterEndpoint data structures
    val removeMsg = RemoveShuffle(shuffleId)
    Future.sequence(
      blockManagerInfo.values.map { bm =>
        bm.slaveEndpoint.ask[Boolean](removeMsg)
      }.toSeq
    )
  }

  /**
   * Delegate RemoveBroadcast messages to each BlockManager because the master may not notified
   * of all broadcast blocks. If removeFromDriver is false, broadcast blocks are only removed
   * from the executors, but not from the driver.
   */
  private def removeBroadcast(broadcastId: Long, removeFromDriver: Boolean): Future[Seq[Int]] = {
    val removeMsg = RemoveBroadcast(broadcastId, removeFromDriver)
    val requiredBlockManagers = blockManagerInfo.values.filter { info =>
      removeFromDriver || !info.blockManagerId.isDriver
    }
    Future.sequence(
      requiredBlockManagers.map { bm =>
        bm.slaveEndpoint.ask[Int](removeMsg)
      }.toSeq
    )
  }

  private def removeBlockManager(blockManagerId: BlockManagerId) {
    val info = blockManagerInfo(blockManagerId)

    // Remove the block manager from blockManagerIdByExecutor.
    blockManagerIdByExecutor -= blockManagerId.executorId

    // Remove it from blockManagerInfo and remove all the blocks.
    blockManagerInfo.remove(blockManagerId)

    val iterator = info.blocks.keySet.iterator
    while (iterator.hasNext) {
      val blockId = iterator.next
      val locations = blockLocations.get(blockId)
      locations -= blockManagerId
      // De-register the block if none of the block managers have it. Otherwise, if pro-active
      // replication is enabled, and a block is either an RDD or a test block (the latter is used
      // for unit testing), we send a message to a randomly chosen executor location to replicate
      // the given block. Note that we ignore other block types (such as broadcast/shuffle blocks
      // etc.) as replication doesn't make much sense in that context.
      if (locations.size == 0) {
        blockLocations.remove(blockId)
        logWarning(s"No more replicas available for $blockId !")
      } else if (proactivelyReplicate && (blockId.isRDD || blockId.isInstanceOf[TestBlockId])) {
        // As a heursitic, assume single executor failure to find out the number of replicas that
        // existed before failure
        val maxReplicas = locations.size + 1
        val i = (new Random(blockId.hashCode)).nextInt(locations.size)
        val blockLocations = locations.toSeq
        val candidateBMId = blockLocations(i)
        blockManagerInfo.get(candidateBMId).foreach { bm =>
          val remainingLocations = locations.toSeq.filter(bm => bm != candidateBMId)
          val replicateMsg = ReplicateBlock(blockId, remainingLocations, maxReplicas)
          bm.slaveEndpoint.ask[Boolean](replicateMsg)
        }
      }
    }

    listenerBus.post(SparkListenerBlockManagerRemoved(System.currentTimeMillis(), blockManagerId))
    logInfo(s"Removing block manager $blockManagerId")

  }

  private def removeExecutor(execId: String) {
    logInfo("Trying to remove executor " + execId + " from BlockManagerMaster.")
    blockManagerIdByExecutor.get(execId).foreach(removeBlockManager)
  }

  /**
   * Return true if the driver knows about the given block manager. Otherwise, return false,
   * indicating that the block manager should re-register.
   */
  private def heartbeatReceived(blockManagerId: BlockManagerId): Boolean = {
    if (!blockManagerInfo.contains(blockManagerId)) {
      blockManagerId.isDriver && !isLocal
    } else {
      blockManagerInfo(blockManagerId).updateLastSeenMs()
      true
    }
  }

  // Remove a block from the slaves that have it. This can only be used to remove
  // blocks that the master knows about.
  private def removeBlockFromWorkers(blockId: BlockId) {
    val locations = blockLocations.get(blockId)
    if (locations != null) {
      locations.foreach { blockManagerId: BlockManagerId =>
        val blockManager = blockManagerInfo.get(blockManagerId)
        if (blockManager.isDefined) {
          // Remove the block from the slave's BlockManager.
          // Doesn't actually wait for a confirmation and the message might get lost.
          // If message loss becomes frequent, we should add retry logic here.
          blockManager.get.slaveEndpoint.ask[Boolean](RemoveBlock(blockId))
        }
      }
    }
  }

  // Return a map from the block manager id to max memory and remaining memory.
  private def memoryStatus: Map[BlockManagerId, (Long, Long)] = {
    blockManagerInfo.map { case(blockManagerId, info) =>
      (blockManagerId, (info.maxMem, info.remainingMem))
    }.toMap
  }

  private def storageStatus: Array[StorageStatus] = {
    blockManagerInfo.map { case (blockManagerId, info) =>
      new StorageStatus(blockManagerId, info.maxMem, Some(info.maxOnHeapMem),
        Some(info.maxOffHeapMem), info.blocks.asScala)
    }.toArray
  }

  /**
   * Return the block's status for all block managers, if any. NOTE: This is a
   * potentially expensive operation and should only be used for testing.
   *
   * If askSlaves is true, the master queries each block manager for the most updated block
   * statuses. This is useful when the master is not informed of the given block by all block
   * managers.
   */
  private def blockStatus(
      blockId: BlockId,
      askSlaves: Boolean): Map[BlockManagerId, Future[Option[BlockStatus]]] = {
    val getBlockStatus = GetBlockStatus(blockId)
    /*
     * Rather than blocking on the block status query, master endpoint should simply return
     * Futures to avoid potential deadlocks. This can arise if there exists a block manager
     * that is also waiting for this master endpoint's response to a previous message.
     */
    blockManagerInfo.values.map { info =>
      val blockStatusFuture =
        if (askSlaves) {
          info.slaveEndpoint.ask[Option[BlockStatus]](getBlockStatus)
        } else {
          Future { info.getStatus(blockId) }
        }
      (info.blockManagerId, blockStatusFuture)
    }.toMap
  }

  /**
   * Return the ids of blocks present in all the block managers that match the given filter.
   * NOTE: This is a potentially expensive operation and should only be used for testing.
   *
   * If askSlaves is true, the master queries each block manager for the most updated block
   * statuses. This is useful when the master is not informed of the given block by all block
   * managers.
   */
  private def getMatchingBlockIds(
      filter: BlockId => Boolean,
      askSlaves: Boolean): Future[Seq[BlockId]] = {
    val getMatchingBlockIds = GetMatchingBlockIds(filter)
    Future.sequence(
      blockManagerInfo.values.map { info =>
        val future =
          if (askSlaves) {
            info.slaveEndpoint.ask[Seq[BlockId]](getMatchingBlockIds)
          } else {
            Future { info.blocks.asScala.keys.filter(filter).toSeq }
          }
        future
      }
    ).map(_.flatten.toSeq)
  }

  /**
   * Returns the BlockManagerId with topology information populated, if available.
   * 开始注册executor上的BlockManager
   */
  private def register(
      idWithoutTopologyInfo: BlockManagerId,
      maxOnHeapMemSize: Long,
      maxOffHeapMemSize: Long,
      slaveEndpoint: RpcEndpointRef): BlockManagerId = {
    // the dummy id is not expected to contain the topology information.
    // we get that info here and respond back with a more fleshed out block manager id
    //利用从Executor上传过来的BlockManagerId信息重新封装BlockManagerId，并且
    //之前传过来没有拓扑信息，这次直接将拓扑信息也封装进去，得到一个更完整的BlockManagerId
    val id = BlockManagerId(
      idWithoutTopologyInfo.executorId,
      idWithoutTopologyInfo.host,
      idWithoutTopologyInfo.port,
      topologyMapper.getTopologyForHost(idWithoutTopologyInfo.host))

    val time = System.currentTimeMillis()
    //判断当前这个BlockManagerId是否注册过，注册结构为：HashMap[BlockManagerId, BlockManagerInfo]
    //如果没注册过就向下执行开始注册
    if (!blockManagerInfo.contains(id)) {
      /**
       * 首先会根据executorId查找内存缓存结构中是否有对应的BlockManagerId,如果为存在那么就将调用removeExecutor方法,将executor从BlockManagerMaster中移除
       * 首先会移除executorId对应的BlockManagerId,然后在移除该旧的BlockManager。
       * 其实就是移除以前的注册过的旧数据
       */
      blockManagerIdByExecutor.get(id.executorId) match {
        case Some(oldId) =>
          // A block manager of the same executor already exists, so remove it (assumed dead)
          logError("Got two different block manager registrations on same executor - "
              + s" will replace old one $oldId with new one $id")
          removeExecutor(id.executorId)
        case None =>
      }
      logInfo("Registering block manager %s with %s RAM, %s".format(
        id.hostPort, Utils.bytesToString(maxOnHeapMemSize + maxOffHeapMemSize), id))

      //将executorId与BlockManagerId映射起来，放到内存缓存中
      blockManagerIdByExecutor(id.executorId) = id

      //将BlockManagerId与BlockManagerInfo映射起来，放入内存缓存中(BlockManagerInfo相当于是BlockManager的元数据)
      //BlockManagerInfo封装了BlockMangerId，当前注册的事件，最大的内存，BlockManagerSlaveEndpoint的引用
      blockManagerInfo(id) = new BlockManagerInfo(
        id, System.currentTimeMillis(), maxOnHeapMemSize, maxOffHeapMemSize, slaveEndpoint)
    }
    listenerBus.post(SparkListenerBlockManagerAdded(time, id, maxOnHeapMemSize + maxOffHeapMemSize,
        Some(maxOnHeapMemSize), Some(maxOffHeapMemSize)))
    id
  }

  /**
   * 更新blockId和blockManagerId的对应关系（一个blockid可以对应多个blockManagerId）
   * @param blockManagerId
   * @param blockId
   * @param storageLevel
   * @param memSize
   * @param diskSize
   * @return
   */
  private def updateBlockInfo(
      blockManagerId: BlockManagerId,
      blockId: BlockId,
      storageLevel: StorageLevel,
      memSize: Long,
      diskSize: Long): Boolean = {

    if (!blockManagerInfo.contains(blockManagerId)) {
      if (blockManagerId.isDriver && !isLocal) {
        // We intentionally do not register the master (except in local mode),
        // so we should not indicate failure.
        return true
      } else {
        return false
      }
    }

    if (blockId == null) {
      blockManagerInfo(blockManagerId).updateLastSeenMs()
      return true
    }

    //主要的更新block的逻辑
    blockManagerInfo(blockManagerId).updateBlockInfo(blockId, storageLevel, memSize, diskSize)

    var locations: mutable.HashSet[BlockManagerId] = null
    //如果已经存在该blockid的数据，那么获取其对应的blockManagerId的Set
    if (blockLocations.containsKey(blockId)) {
      locations = blockLocations.get(blockId)
    } else {
      locations = new mutable.HashSet[BlockManagerId]
      blockLocations.put(blockId, locations)
    }

    if (storageLevel.isValid) {
      locations.add(blockManagerId)
    } else {
      locations.remove(blockManagerId)
    }

    // Remove the block from master tracking if it has been removed on all slaves.
    if (locations.size == 0) {
      blockLocations.remove(blockId)
    }
    true
  }

  private def getLocations(blockId: BlockId): Seq[BlockManagerId] = {
    if (blockLocations.containsKey(blockId)) blockLocations.get(blockId).toSeq else Seq.empty
  }

  private def getLocationsMultipleBlockIds(
      blockIds: Array[BlockId]): IndexedSeq[Seq[BlockManagerId]] = {
    blockIds.map(blockId => getLocations(blockId))
  }

  /** Get the list of the peers of the given block manager */
  private def getPeers(blockManagerId: BlockManagerId): Seq[BlockManagerId] = {
    val blockManagerIds = blockManagerInfo.keySet
    if (blockManagerIds.contains(blockManagerId)) {
      blockManagerIds.filterNot { _.isDriver }.filterNot { _ == blockManagerId }.toSeq
    } else {
      Seq.empty
    }
  }

  /**
   * Returns an [[RpcEndpointRef]] of the [[BlockManagerSlaveEndpoint]] for sending RPC messages.
   */
  private def getExecutorEndpointRef(executorId: String): Option[RpcEndpointRef] = {
    for (
      blockManagerId <- blockManagerIdByExecutor.get(executorId);
      info <- blockManagerInfo.get(blockManagerId)
    ) yield {
      info.slaveEndpoint
    }
  }

  override def onStop(): Unit = {
    askThreadPool.shutdownNow()
  }
}

@DeveloperApi
case class BlockStatus(storageLevel: StorageLevel, memSize: Long, diskSize: Long) {
  def isCached: Boolean = memSize + diskSize > 0
}

@DeveloperApi
object BlockStatus {
  def empty: BlockStatus = BlockStatus(StorageLevel.NONE, memSize = 0L, diskSize = 0L)
}

private[spark] class BlockManagerInfo(
    val blockManagerId: BlockManagerId,
    timeMs: Long,
    val maxOnHeapMem: Long,
    val maxOffHeapMem: Long,
    val slaveEndpoint: RpcEndpointRef)
  extends Logging {

  val maxMem = maxOnHeapMem + maxOffHeapMem

  private var _lastSeenMs: Long = timeMs
  private var _remainingMem: Long = maxMem

  // Mapping from block id to its status.
  //BlockManagerInfo 管理了每个BlockManager内部的BlockId->BlockStatus映射
  private val _blocks = new JHashMap[BlockId, BlockStatus]

  // Cached blocks held by this BlockManager. This does not include broadcast blocks.
  private val _cachedBlocks = new mutable.HashSet[BlockId]

  def getStatus(blockId: BlockId): Option[BlockStatus] = Option(_blocks.get(blockId))

  def updateLastSeenMs() {
    _lastSeenMs = System.currentTimeMillis()
  }

  def updateBlockInfo(
      blockId: BlockId,
      storageLevel: StorageLevel,
      memSize: Long,
      diskSize: Long) {

    updateLastSeenMs()

    val blockExists = _blocks.containsKey(blockId)
    var originalMemSize: Long = 0
    var originalDiskSize: Long = 0
    var originalLevel: StorageLevel = StorageLevel.NONE

    if (blockExists) {
      // The block exists on the slave already.
      val blockStatus: BlockStatus = _blocks.get(blockId)
      originalLevel = blockStatus.storageLevel
      originalMemSize = blockStatus.memSize
      originalDiskSize = blockStatus.diskSize

      // 判断storeLevel是否使用内存，是，就给剩余内存数量加上当前内存数量
      if (originalLevel.useMemory) {
        _remainingMem += originalMemSize
      }
    }

    if (storageLevel.isValid) {
      /* isValid means it is either stored in-memory or on-disk.
       * The memSize here indicates the data size in or dropped from memory,
       * externalBlockStoreSize here indicates the data size in or dropped from externalBlockStore,
       * and the diskSize here indicates the data size in or dropped to disk.
       * They can be both larger than 0, when a block is dropped from memory to disk.
       * Therefore, a safe way to set BlockStatus is to set its info in accurate modes. */
      var blockStatus: BlockStatus = null
      if (storageLevel.useMemory) {
        blockStatus = BlockStatus(storageLevel, memSize = memSize, diskSize = 0)
        _blocks.put(blockId, blockStatus)
        _remainingMem -= memSize
        if (blockExists) {
          logInfo(s"Updated $blockId in memory on ${blockManagerId.hostPort}" +
            s" (current size: ${Utils.bytesToString(memSize)}," +
            s" original size: ${Utils.bytesToString(originalMemSize)}," +
            s" free: ${Utils.bytesToString(_remainingMem)})")
        } else {
          logInfo(s"Added $blockId in memory on ${blockManagerId.hostPort}" +
            s" (size: ${Utils.bytesToString(memSize)}," +
            s" free: ${Utils.bytesToString(_remainingMem)})")
        }
      }
      if (storageLevel.useDisk) {
        blockStatus = BlockStatus(storageLevel, memSize = 0, diskSize = diskSize)
        _blocks.put(blockId, blockStatus)
        if (blockExists) {
          logInfo(s"Updated $blockId on disk on ${blockManagerId.hostPort}" +
            s" (current size: ${Utils.bytesToString(diskSize)}," +
            s" original size: ${Utils.bytesToString(originalDiskSize)})")
        } else {
          logInfo(s"Added $blockId on disk on ${blockManagerId.hostPort}" +
            s" (size: ${Utils.bytesToString(diskSize)})")
        }
      }
      if (!blockId.isBroadcast && blockStatus.isCached) {
        _cachedBlocks += blockId
      }
    } else if (blockExists) {//如果存储级别非法而当前的内存中又保存过该block的信息，那么就要移除
      // If isValid is not true, drop the block.
      _blocks.remove(blockId)
      _cachedBlocks -= blockId
      if (originalLevel.useMemory) {
        logInfo(s"Removed $blockId on ${blockManagerId.hostPort} in memory" +
          s" (size: ${Utils.bytesToString(originalMemSize)}," +
          s" free: ${Utils.bytesToString(_remainingMem)})")
      }
      if (originalLevel.useDisk) {
        logInfo(s"Removed $blockId on ${blockManagerId.hostPort} on disk" +
          s" (size: ${Utils.bytesToString(originalDiskSize)})")
      }
    }
  }

  def removeBlock(blockId: BlockId) {
    if (_blocks.containsKey(blockId)) {
      _remainingMem += _blocks.get(blockId).memSize
      _blocks.remove(blockId)
    }
    _cachedBlocks -= blockId
  }

  def remainingMem: Long = _remainingMem

  def lastSeenMs: Long = _lastSeenMs

  def blocks: JHashMap[BlockId, BlockStatus] = _blocks

  // This does not include broadcast blocks.
  def cachedBlocks: collection.Set[BlockId] = _cachedBlocks

  override def toString: String = "BlockManagerInfo " + timeMs + " " + _remainingMem

  def clear() {
    _blocks.clear()
  }
}
