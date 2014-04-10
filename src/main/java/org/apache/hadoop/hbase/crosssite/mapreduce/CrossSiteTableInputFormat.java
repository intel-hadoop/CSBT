package org.apache.hadoop.hbase.crosssite.mapreduce;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map.Entry;
import java.util.NavigableMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.HTableInterface;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.crosssite.CrossSiteHTable;
import org.apache.hadoop.hbase.crosssite.CrossSiteUtil;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.mapreduce.TableInputFormat;
import org.apache.hadoop.hbase.mapreduce.TableMapReduceUtil;
import org.apache.hadoop.hbase.mapreduce.TableRecordReader;
import org.apache.hadoop.hbase.mapreduce.TableSplit;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.util.StringUtils;

public class CrossSiteTableInputFormat extends TableInputFormat{
  private final Log LOG = LogFactory.getLog(CrossSiteTableInputFormat.class);

  @Override
  public void setConf(Configuration configuration) {
    this.conf = configuration;
    String tableName = conf.get(INPUT_TABLE);
    try {
      setHTable(new CrossSiteHTable(new Configuration(conf), tableName));
    } catch (Exception e) {
      LOG.error(StringUtils.stringifyException(e));
    }

    Scan scan = null;

    if (conf.get(SCAN) != null) {
      try {
        scan = TableMapReduceUtil.convertStringToScan(conf.get(SCAN));
      } catch (IOException e) {
        LOG.error("An error occurred.", e);
      }
    } else {
      try {
        scan = new Scan();

        if (conf.get(SCAN_ROW_START) != null) {
          scan.setStartRow(Bytes.toBytes(conf.get(SCAN_ROW_START)));
        }

        if (conf.get(SCAN_ROW_STOP) != null) {
          scan.setStopRow(Bytes.toBytes(conf.get(SCAN_ROW_STOP)));
        }

        if (conf.get(SCAN_COLUMNS) != null) {
          addColumns(scan, conf.get(SCAN_COLUMNS));
        }

        if (conf.get(SCAN_COLUMN_FAMILY) != null) {
          scan.addFamily(Bytes.toBytes(conf.get(SCAN_COLUMN_FAMILY)));
        }

        if (conf.get(SCAN_TIMESTAMP) != null) {
          scan.setTimeStamp(Long.parseLong(conf.get(SCAN_TIMESTAMP)));
        }

        if (conf.get(SCAN_TIMERANGE_START) != null && conf.get(SCAN_TIMERANGE_END) != null) {
          scan.setTimeRange(
              Long.parseLong(conf.get(SCAN_TIMERANGE_START)),
              Long.parseLong(conf.get(SCAN_TIMERANGE_END)));
        }

        if (conf.get(SCAN_MAXVERSIONS) != null) {
          scan.setMaxVersions(Integer.parseInt(conf.get(SCAN_MAXVERSIONS)));
        }

        if (conf.get(SCAN_CACHEDROWS) != null) {
          scan.setCaching(Integer.parseInt(conf.get(SCAN_CACHEDROWS)));
        }

        // false by default, full table scans generate too much BC churn
        scan.setCacheBlocks((conf.getBoolean(SCAN_CACHEBLOCKS, false)));
      } catch (Exception e) {
        LOG.error(StringUtils.stringifyException(e));
      }
    }

    setScan(scan);
  }

  @Override
  public List<InputSplit> getSplits(JobContext context) throws IOException {
    HTable table = getHTable();
    if (table == null) {
      throw new IOException("No table was provided.");
    }
    NavigableMap<HRegionInfo, ServerName> locations = table.getRegionLocations();
    if (!locations.isEmpty()) {
      Scan scan = getScan();
      List<InputSplit> splits = new ArrayList<InputSplit>();
      int i = 0;
      for (Entry<HRegionInfo, ServerName> location : locations.entrySet()) {
        String regionLocation = location.getValue().getHostname();
        byte[] startRow = scan.getStartRow();
        byte[] stopRow = scan.getStopRow();
        if ((startRow.length == 0 || location.getKey().getEndKey().length == 0 || Bytes.compareTo(
            startRow, location.getKey().getEndKey()) < 0)
            && (stopRow.length == 0 || Bytes.compareTo(stopRow, location.getKey().getStartKey()) > 0)) {
          byte[] splitStart = startRow.length == 0
              || Bytes.compareTo(location.getKey().getStartKey(), startRow) >= 0 ? location
              .getKey().getStartKey() : startRow;
          byte[] splitStop = (stopRow.length == 0 || Bytes.compareTo(location.getKey().getEndKey(),
              stopRow) <= 0) && location.getKey().getEndKey().length > 0 ? location.getKey()
              .getEndKey() : stopRow;
          TableSplit split = new TableSplit(location.getKey().getTableName(), splitStart,
              splitStop, regionLocation);
          splits.add(split);
          if (LOG.isDebugEnabled()) {
            LOG.debug("getSplits: split -> " + i++ + " -> " + split);
          }
        }
      }
      return splits;
    } else {
      return Collections.emptyList();
    }
  }

  @Override
  public RecordReader<ImmutableBytesWritable, Result> createRecordReader(InputSplit split,
      TaskAttemptContext context) throws IOException {
    TableSplit tSplit = (TableSplit) split;
    CrossSiteHTable table = (CrossSiteHTable) getHTable();
    if (table == null) {
      throw new IOException("Cannot create a record reader because of a"
          + " previous error. Please look at the previous logs lines from"
          + " the task's full log for more details.");
    }
    String clusterTableName = Bytes.toString(tSplit.getTableName());
    String clusterName = CrossSiteUtil.getClusterName(clusterTableName);
    HTableInterface clusterHTableInterface = table.getClusterHTable(clusterName);
    HTable clusterHTable = null;
    if (clusterHTableInterface instanceof HTable) {
      clusterHTable = (HTable) clusterHTableInterface;
    } else {
      throw new IOException("The cluster table is not an instance of the HTable. Its class is "
          + (clusterHTableInterface == null ? "" : clusterHTableInterface.getClass().getName()));
    }
    TableRecordReader trr = new TableRecordReader();
    Scan sc = new Scan(getScan());
    sc.setStartRow(tSplit.getStartRow());
    sc.setStopRow(tSplit.getEndRow());
    trr.setScan(sc);
    trr.setHTable(clusterHTable);
    try {
      trr.initialize(tSplit, context);
    } catch (InterruptedException e) {
      throw new InterruptedIOException(e.getMessage());
    }
    return trr;
  }
}
