/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * License); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an AS IS BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.crosssite.locator;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.crosssite.CrossSiteUtil;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.io.Text;

/**
 * Cluster locator that extracts the cluster name as the suffix string after the last appearance of
 * the specified delimiter in the row key. The delimiter has a default value as ",".
 */
public class SuffixClusterLocator extends AbstractClusterLocator {

  private static final Log LOG = LogFactory.getLog(SuffixClusterLocator.class);
  private String delimiter = ",";

  public SuffixClusterLocator() {
    super();
  }

  public SuffixClusterLocator(String delimiter) {
    if (delimiter != null) {
      this.delimiter = delimiter;
    }
  }

  @Override
  public String getClusterName(byte[] row) throws RowNotLocatableException {
    String rowStr = Bytes.toString(row);
    if (rowStr == null)
      throw new RowNotLocatableException(row);

    int pos = rowStr.lastIndexOf(delimiter);
    if (pos < 0)
      throw new RowNotLocatableException(row);

    return rowStr.substring(pos + 1);
  }

  public String getDelimiter() {
    return delimiter;
  }

  @Override
  public void readFields(DataInput in) throws IOException {
    delimiter = Text.readString(in);
  }

  @Override
  public void write(DataOutput out) throws IOException {
    Text.writeString(out, delimiter);
  }

  @Override
  public ClusterLocator createClusterLocatorFromArguments(ArrayList<byte[]> locatorArguments) {
    if (locatorArguments.isEmpty())
      return new SuffixClusterLocator();

    if (locatorArguments.size() != 1) {
      throw new IllegalArgumentException("Incorrect Arguments passed to SuffixClusterLocator. "
          + "Expected: 1 but got: " + locatorArguments.size());
    }
    byte[] delimiter = CrossSiteUtil.convertByteArrayToString(locatorArguments.get(0));
    return new SuffixClusterLocator(Bytes.toString(delimiter));
  }

  @Override
  public void validateArguments(String[] args) {
    if (args.length == 0) {
      LOG.debug("Default delimiter " + delimiter + " will be used");
      return;
    }
    if (args.length != 1) {
      throw new IllegalArgumentException("Incorrect Arguments passed to SuffixClusterLocator. "
          + "Expected: 1 but got: " + args.length);
    }
    this.delimiter = Bytes.toString(CrossSiteUtil.convertByteArrayToString(Bytes.toBytes(args[0])));
  }
}
