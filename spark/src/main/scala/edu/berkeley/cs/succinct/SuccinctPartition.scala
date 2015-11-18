package edu.berkeley.cs.succinct

import java.io.DataOutputStream

import edu.berkeley.cs.succinct.buffers.SuccinctIndexedFileBuffer
import edu.berkeley.cs.succinct.regex.RegExMatch
import edu.berkeley.cs.succinct.streams.SuccinctIndexedFileStream
import edu.berkeley.cs.succinct.util.container.Range
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.spark.storage.StorageLevel

import scala.collection.JavaConversions._

/**
 * A simple wrapper around `SuccinctIndexedFile` to enable partitions in SuccinctRDD to exploit
 * operations directly supported on succinct's compressed representation of data.
 */
class SuccinctPartition(
    succinctIndexedFile: SuccinctIndexedFile,
    partitionOffset: Long,
    partitionFirstRecordId: Long) {

  /** Returns the range of recordIds for which this partition is responsible (both inclusive) */
  private[succinct] def partitionOffsetRange: Range = {
    // Adjust for 1 extra byte for the EOF byte
    val endOffset = partitionOffset + succinctIndexedFile.getSize - 2
    new Range(partitionOffset, endOffset)
  }

  /** Iterator over all records in the partition. */
  private[succinct] def iterator: Iterator[Array[Byte]] = {
    new Iterator[Array[Byte]] {
      var curRecordId: Int = 0

      override def hasNext: Boolean = curRecordId < succinctIndexedFile.getNumRecords

      override def next(): Array[Byte] = {
        val data = succinctIndexedFile.getRecord(curRecordId)
        curRecordId += 1
        data
      }
    }
  }

  /** Obtain all occurrences in input corresponding to a search query */
  private[succinct] def search(query: Array[Byte]): Iterator[Long] = {
    new Iterator[Long] {
      val it = succinctIndexedFile.searchIterator(query)

      override def hasNext: Boolean = it.hasNext

      override def next(): Long = partitionOffset + it.next()
    }
  }

  /** Count all occurrences of search query in input. */
  private[succinct] def count(query: Array[Byte]): Long = {
    succinctIndexedFile.count(query)
  }

  /** Random access to data at given offset to fetch given number of bytes */
  private[succinct] def extract(offset: Long, length: Int): Array[Byte] = {
    succinctIndexedFile.extract(offset - partitionOffset, length)
  }

  /**
   * Obtain all recordIds and lengths of matches in input corresponding to a
   * regex search query
   */
  private[succinct] def regexSearch(query: String): Iterator[RegExMatch] = {
    succinctIndexedFile.regexSearch(query).iterator
  }

  /** Obtain the total number of records in the partition */
  private[succinct] def count: Long = {
    succinctIndexedFile.getNumRecords
  }

  private[succinct] def sizeInBytes: Int = {
    succinctIndexedFile.getSize
  }

  /** Write partition to output stream */
  private[succinct] def writeToStream(dataOutputStream: DataOutputStream): Unit = {
    succinctIndexedFile.writeToStream(dataOutputStream)
    dataOutputStream.writeLong(partitionOffset)
    dataOutputStream.writeLong(partitionFirstRecordId)
  }

}

/** Factory for [[SuccinctPartition]] instances **/
object SuccinctPartition {
  /** Creates a [[SuccinctPartition]] instance from the partition location and its storageLevel **/
  private[succinct] def apply(partitionLocation: String, storageLevel: StorageLevel) = {
    val path = new Path(partitionLocation)
    val fs = FileSystem.get(path.toUri, new Configuration())
    val is = fs.open(path)
    val succinctIndexedFile = storageLevel match {
      case StorageLevel.MEMORY_ONLY =>
        new SuccinctIndexedFileBuffer(is)
      case StorageLevel.DISK_ONLY =>
        new SuccinctIndexedFileStream(path)
      case _ =>
        new SuccinctIndexedFileBuffer(is)
    }
    val partitionOffset = is.readLong()
    val partitionFirstRecordId = is.readLong()
    is.close()
    new SuccinctPartition(succinctIndexedFile, partitionOffset, partitionFirstRecordId)
  }
}
