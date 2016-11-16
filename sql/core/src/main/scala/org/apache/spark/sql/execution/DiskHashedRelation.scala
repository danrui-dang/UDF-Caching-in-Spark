package org.apache.spark.sql.execution

import java.io._
import java.nio.file.{Path, StandardOpenOption, Files}
import java.util.{ArrayList => JavaArrayList}

import org.apache.spark.SparkException
import org.apache.spark.sql.catalyst.expressions.{Projection, Row}
import org.apache.spark.sql.execution.CS143Utils._

import scala.collection.JavaConverters._

/**
 * This trait represents a regular relation that is hash partitioned and spilled to
 * disk.
 */
private[sql] sealed trait DiskHashedRelation {
  /**
   *
   * @return an iterator of the [[DiskPartition]]s that make up this relation.
   */
  def getIterator(): Iterator[DiskPartition]

  /**
   * Close all the partitions for this relation. This should involve deleting the files hashed into.
   */
  def closeAllPartitions()
}

/**
 * A general implementation of [[DiskHashedRelation]].
 *
 * @param partitions the disk partitions that we are going to spill to
 */
protected [sql] final class GeneralDiskHashedRelation(partitions: Array[DiskPartition])
    extends DiskHashedRelation with Serializable {
  private val diskPartitionArray: Array[DiskPartition] = partitions

  override def getIterator() = {
    diskPartitionArray.toIterator
  }

  override def closeAllPartitions() = {
    for (x <- diskPartitionArray) {
      x.closePartition()
    }
  }

  // Note: I still find the logic for the DiskHashedRelation interface and singleton object confusing
  // Took me some time to figure this out
}

private[sql] class DiskPartition (
                                  filename: String,
                                  blockSize: Int) {
  private val path: Path = Files.createTempFile("", filename)
  private val data: JavaArrayList[Row] = new JavaArrayList[Row]
  private val outStream: OutputStream = Files.newOutputStream(path)
  private val inStream: InputStream = Files.newInputStream(path)
  private val chunkSizes: JavaArrayList[Int] = new JavaArrayList[Int]()
  private var writtenToDisk: Boolean = false
  private var inputClosed: Boolean = false

  /**
   * This method inserts a new row into this particular partition. If the size of the partition
   * exceeds the blockSize, the partition is spilled to disk.
   *
   * @param row the [[Row]] we are adding
   */
  def insert(row: Row) = {
    if (inputClosed) {
      throw new SparkException("Cannot insert after closing input!")
    }
    // Note: each block should not exceed its size, so if this insertion would cause size to go over, we spill the partition before inserting?
    //       Or, if insertion causes size to go over, we then spill the partition?
    // Here we use the former, and adds a method CS143Utils.getBytesFromRow
    if (this.measurePartitionSize() + CS143Utils.getBytesFromRow(row).size > this.blockSize) {
      // Here we can assume that row size is smaller than blockSize, so when we spill here, we always have something to write
      this.spillPartitionToDisk()
      // Note: interestingly, spillPartitionToDisk does not clear this.data by itself
      this.data.clear()
    }
    this.data.add(row)
    this.writtenToDisk = false
  }

  /**
   * This method converts the data to a byte array and returns the size of the byte array
   * as an estimation of the size of the partition.
   *
   * @return the estimated size of the data
   */
  private[this] def measurePartitionSize(): Int = {
    CS143Utils.getBytesFromList(data).size
  }

  /**
   * Uses the [[Files]] API to write a byte array representing data to a file.
   */
  private[this] def spillPartitionToDisk() = {
    val bytes: Array[Byte] = getBytesFromList(data)

    // This array list stores the sizes of chunks written in order to read them back correctly.
    chunkSizes.add(bytes.size)

    // Note: This does not use the outputStream, intended?
    Files.write(path, bytes, StandardOpenOption.APPEND)
    writtenToDisk = true
  }

  /**
   * If this partition has been closed, this method returns an Iterator of all the
   * data that was written to disk by this partition.
   *
   * @return the [[Iterator]] of the data
   */
  def getData(): Iterator[Row] = {
    if (!inputClosed) {
      throw new SparkException("Should not be reading from file before closing input. Bad things will happen!")
    }

    new Iterator[Row] {
      var currentIterator: Iterator[Row] = data.iterator.asScala
      val chunkSizeIterator: Iterator[Int] = chunkSizes.iterator().asScala
      var byteArray: Array[Byte] = null

      override def next() = {
        // Scala note: val vs var, iterator hasNext() vs hasNext
        if (currentIterator.hasNext) {
          currentIterator.next()
        } else {
          if (fetchNextChunk()) {
            currentIterator = CS143Utils.getListFromBytes(byteArray).iterator.asScala
            currentIterator.next()
          } else {
            null
          }
        }
      }

      override def hasNext() = {
        if (currentIterator.hasNext) {
          true
        } else {
          // NOTE: here we avoid calling fetchNextChunk, as we don't want to overwrite the byteArray
          // Calling fetchNextChunk() once in either hasNext, or next should be fine;
          // For here, knowing if chunkSizeIterator hasNext is enough for knowing if there are more rows.
          chunkSizeIterator.hasNext
        }
      }

      /**
       * Fetches the next chunk of the file and updates the iterator. Should return true
       * unless the iterator is empty.
       *
       * @return true unless the iterator is empty.
       */
      private[this] def fetchNextChunk(): Boolean = {
        if (chunkSizeIterator.hasNext) {
          byteArray = CS143Utils.getNextChunkBytes(inStream, chunkSizeIterator.next(), byteArray)
          true
        } else {
          false
        }
      }
    }
  }

  /**
   * Closes this partition, implying that no more data will be written to this partition. If getData()
   * is called without closing the partition, an error will be thrown.
   *
   * If any data has not been written to disk yet, it should be written. The output stream should
   * also be closed.
   */
  def closeInput() = {
    if (!writtenToDisk) {
      // so that we don't add potential data length 0 to the chunkSizes array
      if (data.size() > 0) {
        this.spillPartitionToDisk()
        this.data.clear()
      } else {
        writtenToDisk = true
      }
    }

    outStream.close()
    inputClosed = true
  }

  /**
   * Closes this partition. This closes the input stream and deletes the file backing the partition.
   */
  private[sql] def closePartition() = {
    inStream.close()
    Files.deleteIfExists(path)
  }
}

private[sql] object DiskHashedRelation {
  /**
   * Given an input iterator, partitions each row into one of a number of [[DiskPartition]]s
   * and constructors a [[DiskHashedRelation]].
   *
   * This executes the first phase of external hashing -- using a course-grained hash function
   * to partition the tuples to disk.
   *
   * The block size is approximately set to 64k because that is a good estimate of the average
   * buffer page.
   *
   * @param input the input [[Iterator]] of [[Row]]s
   * @param keyGenerator a [[Projection]] that generates the keys for the input
   * @param size the number of [[DiskPartition]]s
   * @param blockSize the threshold at which each partition will spill
   * @return the constructed [[DiskHashedRelation]]
   */
  def apply (
                input: Iterator[Row],
                keyGenerator: Projection,
                size: Int = 64,
                blockSize: Int = 64000) = {

    var diskPartitionArray: Array[DiskPartition] = new Array[DiskPartition](size)
    for (i <- 0 until diskPartitionArray.length) {
      // we initialize all of them, even if some may end up with no rows
      diskPartitionArray(i) = new DiskPartition("temp" + Integer.toString(i), blockSize)
    }

    while (input.hasNext) {
      val row: Row = input.next()
      val hashedKey: Int = keyGenerator.apply(row).hashCode() % size
      diskPartitionArray(hashedKey).insert(row)
    }
    for (i <- 0 until diskPartitionArray.length) {
      diskPartitionArray(i).closeInput()
    }

    val resultHashedRelation: GeneralDiskHashedRelation = new GeneralDiskHashedRelation(diskPartitionArray)
    resultHashedRelation
  }
}
