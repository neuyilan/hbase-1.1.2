/**
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.regionserver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.classification.InterfaceAudience;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.monitoring.MonitoredTask;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.util.StringUtils;

/**
 * Default implementation of StoreFlusher.
 */
@InterfaceAudience.Private
public class DefaultStoreFlusher extends StoreFlusher {
  private static final Log LOG = LogFactory.getLog(DefaultStoreFlusher.class);
  private final Object flushLock = new Object();

  public DefaultStoreFlusher(Configuration conf, Store store) {
    super(conf, store);
  }

  @Override
  public List<Path> flushSnapshot(MemStoreSnapshot snapshot, long cacheFlushId,
      MonitoredTask status) throws IOException {
    ArrayList<Path> result = new ArrayList<Path>();
    int cellsCount = snapshot.getCellsCount();
    if (cellsCount == 0) return result; // don't flush if there are no entries
    // Use a store scanner to find which rows to flush.
    long smallestReadPoint = store.getSmallestReadPoint();
    InternalScanner scanner = createScanner(snapshot.getScanner(), smallestReadPoint);
    
    if (scanner == null) {
      return result; // NULL scanner returned from coprocessor hooks means skip normal processing
    }

    StoreFile.Writer writer;
    try {
      // TODO:  We can fail in the below block before we complete adding this flush to
      //        list of store files.  Add cleanup of anything put on filesystem if we fail.
      synchronized (flushLock) {
        status.setStatus("Flushing " + store + ": creating writer");
        // Write the map out to the disk
        writer = store.createWriterInTmp(
            cellsCount, store.getFamily().getCompression(), false, true, true);
        writer.setTimeRangeTracker(snapshot.getTimeRangeTracker());
        IOException e = null;
        try {
          performFlush(scanner, writer, smallestReadPoint);
        } catch (IOException ioe) {
          e = ioe;
          // throw the exception out
          throw ioe;
        } finally {
          if (e != null) {
            writer.close();
          } else {
            finalizeWriter(writer, cacheFlushId, status);
          }
        }
      }
    } finally {
      scanner.close();
    }
    LOG.info("Flushed, sequenceid=" + cacheFlushId +", memsize="
        + StringUtils.humanReadableInt(snapshot.getSize()) +
        ", hasBloomFilter=" + writer.hasGeneralBloom() +
        ", into tmp file " + writer.getPath());
    result.add(writer.getPath());
    return result;
  }
  
  /************************************************************************/
  /**
   * write index info, write by qihouliang
   * @param snapshot
   */
	public void write2Index(MemStoreSnapshot indexSnapshot) {
		long smallestReadPoint = store.getSmallestReadPoint();
		
		HRegionInfo hri = store.getRegionInfo();
		byte[] regionStartKey = hri.getStartKey();
		byte[] indexedColumnFamily;
		byte[] indexedColumnName;
		byte[] dataValuePerColumn;
		byte[] dataKey;
		long ts;
		
		HRegion region = ((HStore)store).getHRegion();
		
		try {
			
			InternalScanner scanner = createScanner(indexSnapshot.getScanner(),
					smallestReadPoint);
			if (scanner == null) {
				return;
			}
			int compactionKVMax = conf.getInt(HConstants.COMPACTION_KV_MAX,
					HConstants.COMPACTION_KV_MAX_DEFAULT);

			ScannerContext scannerContext = ScannerContext.newBuilder()
					.setBatchLimit(compactionKVMax).build();

			List<Cell> kvs = new ArrayList<Cell>();
			boolean hasMore;
			do {
				hasMore = scanner.next(kvs, scannerContext);
				if (!kvs.isEmpty()) {
					for (Cell c : kvs) {
						dataKey =CellUtil.cloneRow(c);
						dataValuePerColumn = CellUtil.cloneValue(c);
						indexedColumnFamily = CellUtil.cloneFamily(c);
						indexedColumnName = CellUtil.cloneQualifier(c);
						ts = c.getTimestamp();
						try {
							//read base table result and delete index table
							readBaseTableAndDeleteOldIndex(dataKey, Bytes.toString(regionStartKey), region,ts);
							
							//put to index 
							putToIndex(region,Bytes.toString(regionStartKey),indexedColumnFamily, indexedColumnName,
									dataValuePerColumn, dataKey,ts);
						} catch (IOException e1) {
							e1.printStackTrace();
						}
//						System.out.println("************ come in method write2Index in DefaultStoreFlusher class "+c.getTypeByte()+","
//										+ Bytes.toString(CellUtil.cloneRow(c))
//										+ ","+ Bytes.toString(CellUtil
//												.cloneFamily(c))
//										+ ","+ Bytes.toString(CellUtil
//												.cloneQualifier(c))
//										+ ","+ Bytes.toString(CellUtil.cloneValue(c))
//										+ ","+c.getTimestamp());
					}
					kvs.clear();
				}
//				System.out.println("************ come in method write2Index in DefaultStoreFlusher class, the cell is empty ");
			} while (hasMore);

		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	/**
	 * write the local index to the local index table(another column family in the same table about the base table)
	 * @param region	the region which to write
	 * @param regionStartKey	the region start key, this info is part of the index row key
	 * @param columnFamily	the data column family(cf), this info is part of the index row key
	 * @param columnName	the column name(qualifier), the info is part of the index row key
	 * @param dataValue		the data value, the info is part of the index row key
	 * @param dataKey	the data key,the info is the index row's value
	 * @throws IOException
	 */
	public void putToIndex(Region region, String regionStartKey,
			byte[] columnFamily, byte[] columnName, byte[] dataValue,
			byte[] dataKey,long timestamp) throws IOException {
		
		byte[] INDEXTABLE_COLUMNFAMILY = Bytes
				.toBytes("INDEX_CF"); 
		byte[] INDEXTABLE_SPACEHOLDER = Bytes.toBytes("EMPTY");
		
		String indexRowkey = regionStartKey + "#"
				+ Bytes.toString(columnFamily) + "#"
				+ Bytes.toString(columnName) + "#" + Bytes.toString(dataValue)
				+ "#" + Bytes.toString(dataKey);
//		System.out.println("************ come in method putToIndex in DefaultStoreFlusher class----indexRowkey:"+indexRowkey);
		Put put2Index = new Put(Bytes.toBytes(indexRowkey));
		put2Index.addColumn(INDEXTABLE_COLUMNFAMILY,
				INDEXTABLE_SPACEHOLDER,timestamp,
				INDEXTABLE_SPACEHOLDER);
		put2Index.setAttribute("index_put", Bytes.toBytes("1"));//is index put ,do not print the log information
		region.put(put2Index);
//		System.out.println("************ come in method putToIndex in DefaultStoreFlusher class----- region.toString():"+region.toString());
	}
	
	
    private void readBaseTableAndDeleteOldIndex(byte[] dataKey,String regionStartKey,Region region,long timestamp) throws IOException {
		Get get = null;
		get = new Get(dataKey);
		System.out.println("************* timestamp: "+timestamp);
		get.setTimeRange(0, timestamp);
		get.setMaxVersions(3+1);
		Result readResultOld = region.get(get);
		
		List<Cell> list = readResultOld.listCells();
        byte[] indexedColumnFamily;
        byte[] indexedColumnName;
        byte[] oldDataValuePerColumn;
        long ts;
        if(list==null){
        	return;
        }
        System.out.println("********* list size is: "+list.size());
        for(Cell cell : list){
        	oldDataValuePerColumn  = CellUtil.cloneValue(cell);
        	indexedColumnFamily = CellUtil.cloneFamily(cell);
        	indexedColumnName = CellUtil.cloneQualifier(cell);
        	ts = cell.getTimestamp();
        	boolean isDelete = deleteOldFromIndexTable(regionStartKey,indexedColumnFamily, indexedColumnName,
        			oldDataValuePerColumn, dataKey,region);
        	System.out.println("************* ts: "+ts);
        	if(isDelete){
        		deleteFromBaseTable(region,dataKey,ts);
//        		System.out.println("************ come in method readBaseTableAndDeleteOldIndex in DefaultStoreFlusher class, delete true");
        	}else{
//        		System.out.println("************ come in method readBaseTableAndDeleteOldIndex in DefaultStoreFlusher class, delete false");
        	}
//            break;// only needs to remove the first value in index table
        }
    }
    
    public boolean deleteOldFromIndexTable(String regionStartKey,byte[] columnFamily, byte[] columnName,
			byte[] dataValue, byte[] dataKey,Region region) throws IOException {
    	
    	String indexRowkey = regionStartKey+"#"+Bytes.toString(columnFamily)+"#"+Bytes.toString(columnName)+"#"
				+Bytes.toString(dataValue)+"#"+Bytes.toString(dataKey);
		Get get = new Get(Bytes.toBytes(indexRowkey));
	    Result r = region.get(get);
	    if(r.isEmpty()){
	    	System.out.println("************ come in method deleteOldFromIndexTable in DefaultStoreFlusher class, indexRowkey: "+indexRowkey+ ", "
	    			+ "delete false");
        	return false;
        }else{
        	Delete del = new Delete(Bytes.toBytes(indexRowkey));
        	del.addColumn(Bytes.toBytes("INDEX_CF"), Bytes.toBytes("EMPTY"));
            region.delete(del);
        }
	    System.out.println("************ come in method deleteOldFromIndexTable in DefaultStoreFlusher class, indexRowkey: "+indexRowkey+ ", "
    			+ "delete true");
        return true;
    } 
    
    public void deleteFromBaseTable (Region region,byte [] dataKey,long ts) throws IOException{
    	Delete delete = new Delete(dataKey);
    	delete.setTimestamp(ts);
    	region.delete(delete);
    }
    
	/************************************************************************/
  
}
