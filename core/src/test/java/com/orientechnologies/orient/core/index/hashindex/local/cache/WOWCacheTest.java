package com.orientechnologies.orient.core.index.hashindex.local.cache;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Random;
import java.util.TreeMap;
import java.util.zip.CRC32;

import com.orientechnologies.common.directmemory.ODirectMemory;
import com.orientechnologies.common.directmemory.ODirectMemoryFactory;
import com.orientechnologies.common.serialization.types.OIntegerSerializer;
import com.orientechnologies.common.serialization.types.OLongSerializer;
import com.orientechnologies.orient.core.Orient;
import com.orientechnologies.orient.core.config.OGlobalConfiguration;
import com.orientechnologies.orient.core.storage.fs.OFileClassic;
import com.orientechnologies.orient.core.storage.impl.local.paginated.OLocalPaginatedStorage;
import com.orientechnologies.orient.core.storage.impl.local.paginated.wal.OLogSequenceNumber;
import com.orientechnologies.orient.core.storage.impl.local.paginated.wal.OWALRecordsFactory;
import com.orientechnologies.orient.core.storage.impl.local.paginated.wal.OWriteAheadLog;
import com.orientechnologies.orient.core.storage.impl.local.paginated.wal.WriteAheadLogTest;

import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * @author Andrey Lomakin
 * @since 26.07.13
 */
@Test
public class WOWCacheTest {
  private int                    systemOffset = 2 * (OIntegerSerializer.INT_SIZE + OLongSerializer.LONG_SIZE);
  private int                    pageSize     = systemOffset + 8;

  private ODirectMemory          directMemory;

  private OLocalPaginatedStorage storageLocal;
  private String                 fileName;

  private OWriteAheadLog         writeAheadLog;

  private OWOWCache              wowCache;

  @BeforeClass
  public void beforeClass() throws IOException {
    OGlobalConfiguration.FILE_LOCK.setValue(Boolean.FALSE);
    directMemory = ODirectMemoryFactory.INSTANCE.directMemory();

    String buildDirectory = System.getProperty("buildDirectory");
    if (buildDirectory == null)
      buildDirectory = ".";

    storageLocal = (OLocalPaginatedStorage) Orient.instance().loadStorage("plocal:" + buildDirectory + "/WOWCacheTest");

    fileName = "wowCacheTest.tst";

    OWALRecordsFactory.INSTANCE.registerNewRecord((byte) 128, WriteAheadLogTest.TestRecord.class);
  }

  @BeforeMethod
  public void beforeMethod() throws IOException {
    closeCacheAndDeleteFile();

    initBuffer();
  }

  private void closeCacheAndDeleteFile() throws IOException {
    if (wowCache != null) {
      wowCache.close();
      wowCache = null;
    }

    if (writeAheadLog != null) {
      writeAheadLog.delete();
      writeAheadLog = null;
    }

    File file = new File(storageLocal.getConfiguration().getDirectory() + File.separator + fileName);
    if (file.exists()) {
      boolean delete = file.delete();
      Assert.assertTrue(delete);
    }
  }

  @AfterClass
  public void afterClass() throws IOException {
    closeCacheAndDeleteFile();

    File file = new File(storageLocal.getConfiguration().getDirectory());
    Assert.assertTrue(file.delete());
  }

  private void initBuffer() throws IOException {
    wowCache = new OWOWCache(true, pageSize, 10000, writeAheadLog, 10, 100, storageLocal, false);
  }

  public void testPutGet() throws IOException {
    Random random = new Random();

    long[] pagePointers = new long[200];
    long fileId = wowCache.openFile(fileName);

    for (int i = 0; i < pagePointers.length; i++) {
      byte[] data = new byte[8];
      random.nextBytes(data);

      pagePointers[i] = directMemory.allocate(new byte[pageSize]);
      directMemory.set(pagePointers[i] + systemOffset, data, 0, data.length);

      final OCachePointer cachePointer = new OCachePointer(directMemory.get(pagePointers[i], pageSize));
      wowCache.put(fileId, i, cachePointer);
    }

    for (int i = 0; i < pagePointers.length; i++) {
      long dataPointer = pagePointers[i];

      byte[] dataOne = directMemory.get(dataPointer + systemOffset, 8);

      OCachePointer cachePointer;
      wowCache.acquireFlushLock(fileId, i);
      try {
        cachePointer = wowCache.get(fileId, i);
        cachePointer.incrementReferrer();
      } finally {
        wowCache.releaseFlushLock(fileId, i);
      }

      byte[] dataTwo = directMemory.get(cachePointer.getDataPointer() + systemOffset, 8);
      cachePointer.decrementReferrer();
      cachePointer = null;

      Assert.assertEquals(dataTwo, dataOne);
    }

    wowCache.flush();

    for (int i = 0; i < pagePointers.length; i++) {
      byte[] dataContent = directMemory.get(pagePointers[i] + systemOffset, 8);
      assertFile(i, dataContent, new OLogSequenceNumber(0, 0));
    }

    for (long pagePointer : pagePointers) {
      directMemory.free(pagePointer);
    }
  }

  public void testDataUpdate() throws Exception {
    final NavigableMap<Long, Long> pageIndexDataMap = new TreeMap<Long, Long>();
    long fileId = wowCache.openFile(fileName);

    Random random = new Random();

    for (int i = 0; i < 600; i++) {
      long pageIndex = random.nextInt(2048);

      byte[] data = new byte[8];
      random.nextBytes(data);

      long pagePointer = directMemory.allocate(new byte[pageSize]);
      directMemory.set(pagePointer + systemOffset, data, 0, data.length);

      pageIndexDataMap.put(pageIndex, pagePointer);

      final OCachePointer cachePointer = new OCachePointer(directMemory.get(pagePointer, pageSize));
      wowCache.put(fileId, pageIndex, cachePointer);
    }

    for (Map.Entry<Long, Long> entry : pageIndexDataMap.entrySet()) {
      long pageIndex = entry.getKey();
      long pagePointer = entry.getValue();

      byte[] dataOne = directMemory.get(pagePointer + systemOffset, 8);

      OCachePointer cachePointer;
      wowCache.acquireFlushLock(fileId, pageIndex);
      try {
        cachePointer = wowCache.get(fileId, pageIndex);
        cachePointer.incrementReferrer();
      } finally {
        wowCache.releaseFlushLock(fileId, pageIndex);
      }

      byte[] dataTwo = directMemory.get(cachePointer.getDataPointer() + systemOffset, 8);

      cachePointer.decrementReferrer();
      Assert.assertEquals(dataTwo, dataOne);
    }

    for (int i = 0; i < 300; i++) {
      long desiredIndex = random.nextInt(2048);

      Long pageIndex = pageIndexDataMap.ceilingKey(desiredIndex);
      if (pageIndex == null)
        pageIndex = pageIndexDataMap.floorKey(desiredIndex);

      long pagePointer = pageIndexDataMap.get(pageIndex);

      byte[] data = new byte[8];
      random.nextBytes(data);

      directMemory.set(pagePointer + systemOffset, data, 0, data.length);
      final OCachePointer cachePointer = new OCachePointer(directMemory.get(pagePointer, pageSize));
      wowCache.put(fileId, pageIndex, cachePointer);
    }

    for (Map.Entry<Long, Long> entry : pageIndexDataMap.entrySet()) {
      long pageIndex = entry.getKey();
      long pagePointer = entry.getValue();

      byte[] dataOne = directMemory.get(pagePointer + systemOffset, 8);

      OCachePointer cachePointer;
      wowCache.acquireFlushLock(fileId, pageIndex);
      try {
        cachePointer = wowCache.get(fileId, pageIndex);
        cachePointer.incrementReferrer();
      } finally {
        wowCache.releaseFlushLock(fileId, pageIndex);
      }

      byte[] dataTwo = directMemory.get(cachePointer.getDataPointer() + systemOffset, 8);
      cachePointer.decrementReferrer();

      Assert.assertEquals(dataTwo, dataOne);
    }

    for (long pagePointer : pageIndexDataMap.values())
      directMemory.free(pagePointer);
  }

  public void testFlushAllContentEventually() throws Exception {
    Random random = new Random();

    long[] pagePointers = new long[200];
    long fileId = wowCache.openFile(fileName);

    for (int i = 0; i < pagePointers.length; i++) {
      byte[] data = new byte[8];
      random.nextBytes(data);

      pagePointers[i] = directMemory.allocate(new byte[pageSize]);
      directMemory.set(pagePointers[i] + systemOffset, data, 0, data.length);

      final OCachePointer cachePointer = new OCachePointer(directMemory.get(pagePointers[i], pageSize));
      wowCache.put(fileId, i, cachePointer);
    }

    for (int i = 0; i < pagePointers.length; i++) {
      long dataPointer = pagePointers[i];

      byte[] dataOne = directMemory.get(dataPointer + systemOffset, 8);

      OCachePointer cachePointer;
      wowCache.acquireFlushLock(fileId, i);
      try {
        cachePointer = wowCache.get(fileId, i);
        cachePointer.incrementReferrer();
      } finally {
        wowCache.releaseFlushLock(fileId, i);
      }

      byte[] dataTwo = directMemory.get(cachePointer.getDataPointer() + systemOffset, 8);
      cachePointer.decrementReferrer();

      Assert.assertEquals(dataTwo, dataOne);
    }

    Thread.sleep(5000);

    for (int i = 0; i < pagePointers.length; i++) {
      byte[] dataContent = directMemory.get(pagePointers[i] + systemOffset, 8);
      assertFile(i, dataContent, new OLogSequenceNumber(0, 0));
    }

    for (long pagePointer : pagePointers) {
      directMemory.free(pagePointer);
    }
  }

  private void assertFile(long pageIndex, byte[] value, OLogSequenceNumber lsn) throws IOException {
    String path = storageLocal.getConfiguration().getDirectory() + File.separator + fileName;

    OFileClassic fileClassic = new OFileClassic();
    fileClassic.init(path, "r");
    fileClassic.open();
    byte[] content = new byte[8 + systemOffset];
    fileClassic.read(pageIndex * (8 + systemOffset), content, 8 + systemOffset);

    Assert.assertEquals(Arrays.copyOfRange(content, systemOffset, 8 + systemOffset), value);

    long magicNumber = OLongSerializer.INSTANCE.deserializeNative(content, 0);

    Assert.assertEquals(magicNumber, O2QDiskCache.MAGIC_NUMBER);
    CRC32 crc32 = new CRC32();
    crc32.update(content, OIntegerSerializer.INT_SIZE + OLongSerializer.LONG_SIZE, content.length - OIntegerSerializer.INT_SIZE
        - OLongSerializer.LONG_SIZE);

    int crc = OIntegerSerializer.INSTANCE.deserializeNative(content, OLongSerializer.LONG_SIZE);
    Assert.assertEquals(crc, (int) crc32.getValue());

    int segment = OIntegerSerializer.INSTANCE.deserializeNative(content, OLongSerializer.LONG_SIZE + OIntegerSerializer.INT_SIZE);
    long position = OLongSerializer.INSTANCE
        .deserializeNative(content, OLongSerializer.LONG_SIZE + 2 * OIntegerSerializer.INT_SIZE);

    OLogSequenceNumber readLsn = new OLogSequenceNumber(segment, position);

    Assert.assertEquals(readLsn, lsn);

    fileClassic.close();
  }

}