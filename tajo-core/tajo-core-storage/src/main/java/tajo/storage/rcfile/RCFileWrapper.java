/*
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

package tajo.storage.rcfile;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.compress.DefaultCodec;
import tajo.catalog.Column;
import tajo.catalog.TableMeta;
import tajo.catalog.statistics.TableStat;
import tajo.datum.ArrayDatum;
import tajo.datum.DatumFactory;
import tajo.storage.*;
import tajo.storage.exception.AlreadyExistsStorageException;
import tajo.util.Bytes;
import tajo.util.TUtil;

import java.io.IOException;
import java.util.ArrayList;

public class RCFileWrapper {

  public static class RCFileAppender extends FileAppender {
    private FileSystem fs;
    private RCFile.Writer writer;

    private TableStatistics stats = null;

    public RCFileAppender(Configuration conf, TableMeta meta, Path path) throws IOException {
      super(conf, meta, path);
    }

    public void init() throws IOException {
      fs = path.getFileSystem(conf);

      if (fs.exists(path)) {
        throw new AlreadyExistsStorageException(path);
      }

      conf.setInt(RCFile.COLUMN_NUMBER_CONF_STR, schema.getColumnNum());
      boolean compress = meta.getOption("rcfile.compress") != null &&
          meta.getOption("rcfile.compress").equalsIgnoreCase("true");
      if (compress) {
        writer = new RCFile.Writer(fs, conf, path, null, new DefaultCodec());
      } else {
        writer = new RCFile.Writer(fs, conf, path, null, null);
      }

      if (enabledStats) {
        this.stats = new TableStatistics(this.schema);
      }

      super.init();
    }

    @Override
    public long getOffset() throws IOException {
      return 0;
    }

    @Override
    public void addTuple(Tuple t) throws IOException {

      BytesRefArrayWritable byteRef =
          new BytesRefArrayWritable(schema.getColumnNum());
      BytesRefWritable cu;
      Column col;
      byte [] bytes;
      for (int i = 0; i < schema.getColumnNum(); i++) {
        if (enabledStats) {
          stats.analyzeField(i, t.get(i));
        }

        if (t.isNull(i)) {
          cu = new BytesRefWritable(new byte[0]);
          byteRef.set(i, cu);
        } else {
          col = schema.getColumn(i);
          switch (col.getDataType()) {
            case BOOLEAN:
            case BYTE:
            case CHAR:
              cu = new BytesRefWritable(t.get(i).asByteArray(), 0, 1);
              byteRef.set(i, cu);
              break;

            case SHORT:
            case INT:
            case LONG:
            case FLOAT:
            case DOUBLE:
            case STRING:
            case STRING2:
            case BYTES:
            case IPv4:
            case IPv6:
              bytes = t.get(i).asByteArray();
              cu = new BytesRefWritable(bytes, 0, bytes.length);
              byteRef.set(i, cu);
              break;
            case ARRAY: {
              ArrayDatum array = (ArrayDatum) t.get(i);
              String json = array.toJSON();
              bytes = json.getBytes();
              cu = new BytesRefWritable(bytes, 0, bytes.length);
              byteRef.set(i, cu);
              break;
            }
            default:
              throw new IOException("ERROR: Unsupported Data Type");
          }
        }
      }

      writer.append(byteRef);

      // Statistical section
      if (enabledStats) {
        stats.incrementRow();
      }
    }

    @Override
    public void flush() throws IOException {
    }

    @Override
    public void close() throws IOException {
      writer.close();
    }

    @Override
    public TableStat getStats() {
      if (enabledStats) {
        return stats.getTableStat();
      } else {
        return null;
      }
    }
  }

  public static class RCFileScanner extends FileScanner {
    private FileSystem fs;
    private RCFile.Reader reader;
    private LongWritable rowId;
    private Integer [] projectionMap;

    BytesRefArrayWritable column;
    private boolean more;
    long end;

    public RCFileScanner(Configuration conf, final TableMeta meta,
                          final Fragment fragment) throws IOException {
      super(conf, meta, fragment);
      fs = fragment.getPath().getFileSystem(conf);

      reader = new RCFile.Reader(fs, fragment.getPath(), conf);
      if (fragment.getStartOffset() > reader.getPosition()) {
        reader.sync(fragment.getStartOffset()); // sync to start
      }
      end = fragment.getStartOffset() + fragment.getLength();
      more = fragment.getStartOffset() < end;

      rowId = new LongWritable();
      column = new BytesRefArrayWritable();
    }

    @Override
    public void init() throws IOException {
      if (targets == null) {
        targets = schema.toArray();
      }

      prepareProjection(targets);

      super.init();
    }

    private void prepareProjection(Column [] targets) {
      projectionMap = new Integer[targets.length];
      int tid;
      for (int i = 0; i < targets.length; i++) {
        tid = schema.getColumnIdByName(targets[i].getColumnName());
        projectionMap[i] = tid;
      }
      ArrayList<Integer> projectionIdList = new ArrayList<Integer>(TUtil.newList(projectionMap));
      ColumnProjectionUtils.setReadColumnIDs(conf, projectionIdList);
    }

    protected boolean next(LongWritable key) throws IOException {
      if (!more) {
        return false;
      }

      more = reader.next(key);
      if (!more) {
        return false;
      }

      long lastSeenSyncPos = reader.lastSeenSyncPos();
      if (lastSeenSyncPos >= end) {
        more = false;
        return more;
      }
      return more;
    }

    @Override
    public Tuple next() throws IOException {
      if (!next(rowId)) {
        return null;
      }

      column.clear();
      reader.getCurrentRow(column);
      column.resetValid(schema.getColumnNum());
      Tuple tuple = new VTuple(schema.getColumnNum());
      int tid; // target column id
      for (int i = 0; i < projectionMap.length; i++) {
        tid = projectionMap[i];
        // if the column is byte[0], it presents a NULL value.
        if (column.get(tid).getLength() == 0) {
          tuple.put(tid, DatumFactory.createNullDatum());
        } else {
          switch (targets[i].getDataType()) {
            case BOOLEAN:
              tuple.put(tid,
                  DatumFactory.createBool(column.get(tid).getBytesCopy()[0]));
              break;
            case BYTE:
              tuple.put(tid,
                  DatumFactory.createByte(column.get(tid).getBytesCopy()[0]));
              break;
            case CHAR:
              tuple.put(tid,
                  DatumFactory.createChar(column.get(tid).getBytesCopy()[0]));
              break;

            case SHORT:
              tuple.put(tid,
                  DatumFactory.createShort(Bytes.toShort(
                      column.get(tid).getBytesCopy())));
              break;
            case INT:
              tuple.put(tid,
                  DatumFactory.createInt(Bytes.toInt(
                      column.get(tid).getBytesCopy())));
              break;

            case LONG:
              tuple.put(tid,
                  DatumFactory.createLong(Bytes.toLong(
                      column.get(tid).getBytesCopy())));
              break;

            case FLOAT:
              tuple.put(tid,
                  DatumFactory.createFloat(Bytes.toFloat(
                      column.get(tid).getBytesCopy())));
              break;

            case DOUBLE:
              tuple.put(tid,
                  DatumFactory.createDouble(Bytes.toDouble(
                      column.get(tid).getBytesCopy())));
              break;

            case IPv4:
              tuple.put(tid,
                  DatumFactory.createIPv4(column.get(tid).getBytesCopy()));
              break;

            case STRING:
              tuple.put(tid,
                  DatumFactory.createString(
                      Bytes.toString(column.get(tid).getBytesCopy())));
              break;

            case STRING2:
              tuple.put(tid,
                  DatumFactory.createString2(
                      column.get(tid).getBytesCopy()));
              break;

            case BYTES:
              tuple.put(tid,
                  DatumFactory.createBytes(column.get(tid).getBytesCopy()));
              break;

            default:
              throw new IOException("Unsupport data type");
          }
        }
      }

      return tuple;
    }

    @Override
    public void reset() throws IOException {
      reader.seek(0);
    }

    @Override
    public void close() throws IOException {
      reader.close();
    }

    @Override
    public boolean isProjectable() {
      return true;
    }

    @Override
    public boolean isSelectable() {
      return false;
    }
  }
}
