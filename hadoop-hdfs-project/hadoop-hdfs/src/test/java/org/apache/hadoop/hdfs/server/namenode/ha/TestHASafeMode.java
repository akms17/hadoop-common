/**
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
package org.apache.hadoop.hdfs.server.namenode.ha;

import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.IOException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.DFSTestUtil;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.MiniDFSNNTopology;
import org.apache.hadoop.hdfs.TestDFSClientFailover;
import org.apache.hadoop.hdfs.server.blockmanagement.BlockManagerTestUtil;
import org.apache.hadoop.hdfs.server.namenode.NameNode;
import org.apache.hadoop.hdfs.server.namenode.NameNodeAdapter;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests that exercise safemode in an HA cluster.
 */
public class TestHASafeMode {
  private static final Log LOG = LogFactory.getLog(TestHASafeMode.class);
  private static final int BLOCK_SIZE = 1024;
  private NameNode nn0;
  private NameNode nn1;
  private FileSystem fs;
  private MiniDFSCluster cluster;
  private Runtime mockRuntime = mock(Runtime.class);
  
  @Before
  public void setupCluster() throws Exception {
    Configuration conf = new Configuration();
    conf.setInt(DFSConfigKeys.DFS_BLOCK_SIZE_KEY, BLOCK_SIZE);
    conf.setInt(DFSConfigKeys.DFS_HEARTBEAT_INTERVAL_KEY, 1);
    cluster = new MiniDFSCluster.Builder(conf)
      .nnTopology(MiniDFSNNTopology.simpleHATopology())
      .numDataNodes(3)
      .waitSafeMode(false)
      .build();
    cluster.waitActive();
    
    nn0 = cluster.getNameNode(0);
    nn1 = cluster.getNameNode(1);
    fs = TestDFSClientFailover.configureFailoverFs(cluster, conf);
    
    nn0.getNamesystem().getEditLogTailer().setRuntime(mockRuntime);

    cluster.transitionToActive(0);
  }
  
  @After
  public void shutdownCluster() throws IOException {
    if (cluster != null) {
      verify(mockRuntime, times(0)).exit(anyInt());
      cluster.shutdown();
    }
  }
  
  private void restartStandby() throws IOException {
    cluster.shutdownNameNode(1);
    // Set the safemode extension to be lengthy, so that the tests
    // can check the safemode message after the safemode conditions
    // have been achieved, without being racy.
    cluster.getConfiguration(1).setInt(
        DFSConfigKeys.DFS_NAMENODE_SAFEMODE_EXTENSION_KEY, 30000);
    cluster.restartNameNode(1);
    nn1 = cluster.getNameNode(1);
    nn1.getNamesystem().getEditLogTailer().setSleepTime(250);
    nn1.getNamesystem().getEditLogTailer().interrupt();
  }
  
  /**
   * Tests the case where, while a standby is down, more blocks are
   * added to the namespace, but not rolled. So, when it starts up,
   * it receives notification about the new blocks during
   * the safemode extension period.
   */
  @Test
  public void testBlocksAddedBeforeStandbyRestart() throws Exception {
    banner("Starting with NN0 active and NN1 standby, creating some blocks");
    DFSTestUtil.createFile(fs, new Path("/test"), 3*BLOCK_SIZE, (short) 3, 1L);
    // Roll edit log so that, when the SBN restarts, it will load
    // the namespace during startup.
    nn0.getRpcServer().rollEditLog();

    banner("Creating some blocks that won't be in the edit log");
    DFSTestUtil.createFile(fs, new Path("/test2"), 5*BLOCK_SIZE, (short) 3, 1L);
    
    banner("Restarting standby");
    restartStandby();

    // We expect it to be stuck in safemode (not the extension) because
    // the block reports are delayed (since they include blocks
    // from /test2 which are too-high genstamps.
    String status = nn1.getNamesystem().getSafemode();
    assertTrue("Bad safemode status: '" + status + "'",
        status.startsWith(
            "Safe mode is ON." +
            "The reported blocks 0 needs additional 3 blocks to reach"));

    banner("Waiting for standby to catch up to active namespace");
    TestEditLogTailer.waitForStandbyToCatchUp(nn0, nn1);

    status = nn1.getNamesystem().getSafemode();
    assertTrue("Bad safemode status: '" + status + "'",
        status.startsWith(
            "Safe mode is ON." +
            "The reported blocks 8 has reached the threshold 0.9990 of " +
            "total blocks 8. Safe mode will be turned off automatically"));
  }
  
  /**
   * Similar to {@link #testBlocksAddedBeforeStandbyRestart()} except that
   * the new blocks are allocated after the SBN has restarted. So, the
   * blocks were not present in the original block reports at startup
   * but are reported separately by blockReceived calls.
   */
  @Test
  public void testBlocksAddedWhileInSafeMode() throws Exception {
    banner("Starting with NN0 active and NN1 standby, creating some blocks");
    DFSTestUtil.createFile(fs, new Path("/test"), 3*BLOCK_SIZE, (short) 3, 1L);
    // Roll edit log so that, when the SBN restarts, it will load
    // the namespace during startup.
    nn0.getRpcServer().rollEditLog();
    
    banner("Restarting standby");
    restartStandby();
    
    String status = nn1.getNamesystem().getSafemode();
    assertTrue("Bad safemode status: '" + status + "'",
        status.startsWith(
            "Safe mode is ON." +
            "The reported blocks 3 has reached the threshold 0.9990 of " +
            "total blocks 3. Safe mode will be turned off automatically"));
    
    // Create a few blocks which will send blockReceived calls to the
    // SBN.
    banner("Creating some blocks while SBN is in safe mode");
    DFSTestUtil.createFile(fs, new Path("/test2"), 5*BLOCK_SIZE, (short) 3, 1L);

    
    banner("Waiting for standby to catch up to active namespace");
    TestEditLogTailer.waitForStandbyToCatchUp(nn0, nn1);

    status = nn1.getNamesystem().getSafemode();
    assertTrue("Bad safemode status: '" + status + "'",
        status.startsWith(
            "Safe mode is ON." +
            "The reported blocks 8 has reached the threshold 0.9990 of " +
            "total blocks 8. Safe mode will be turned off automatically"));
  }

  /**
   * Test for the following case proposed by ATM:
   * 1. Both NNs are up, one is active. There are 100 blocks. Both are
   *    out of safemode.
   * 2. 10 block deletions get processed by NN1. NN2 enqueues these DN messages
   *     until it next reads from a checkpointed edits file.
   * 3. NN2 gets restarted. Its queues are lost.
   * 4. NN2 comes up, reads from all the finalized edits files. Concludes there
   *    should still be 100 blocks.
   * 5. NN2 receives a block report from all the DNs, which only accounts for
   *    90 blocks. It doesn't leave safemode.
   * 6. NN1 dies or is transitioned to standby.
   * 7. NN2 is transitioned to active. It reads all the edits from NN1. It now
   *    knows there should only be 90 blocks, but it's still in safemode.
   * 8. NN2 doesn't ever recheck whether it should leave safemode.
   * 
   * This is essentially the inverse of {@link #testBlocksAddedWhileStandbyShutdown()}
   */
  @Test
  public void testBlocksRemovedBeforeStandbyRestart() throws Exception {
    banner("Starting with NN0 active and NN1 standby, creating some blocks");
    DFSTestUtil.createFile(fs, new Path("/test"), 5*BLOCK_SIZE, (short) 3, 1L);

    // Roll edit log so that, when the SBN restarts, it will load
    // the namespace during startup.
    nn0.getRpcServer().rollEditLog();

    // Delete those blocks again, so they won't get reported to the SBN
    // once it starts up
    banner("Removing the blocks without rolling the edit log");
    fs.delete(new Path("/test"), true);
    BlockManagerTestUtil.computeAllPendingWork(
        nn0.getNamesystem().getBlockManager());
    cluster.triggerHeartbeats();

    banner("Restarting standby");
    restartStandby();
    String status = nn1.getNamesystem().getSafemode();
    assertTrue("Bad safemode status: '" + status + "'",
        status.startsWith(
            "Safe mode is ON." +
            "The reported blocks 0 needs additional 5 blocks to reach"));
    
    banner("Waiting for standby to catch up to active namespace");
    TestEditLogTailer.waitForStandbyToCatchUp(nn0, nn1);
    status = nn1.getNamesystem().getSafemode();
    assertTrue("Bad safemode status: '" + status + "'",
        status.startsWith(
            "Safe mode is ON." +
            "The reported blocks 0 has reached the threshold 0.9990 of " +
            "total blocks 0. Safe mode will be turned off automatically"));
  }
  
  /**
   * Similar to {@link #testBlocksRemovedBeforeStandbyRestart()} except that
   * the blocks are removed after the SBN has restarted. So, the
   * blocks were present in the original block reports at startup
   * but are deleted separately later by deletion reports.
   */
  @Test
  public void testBlocksRemovedWhileInSafeMode() throws Exception {
    banner("Starting with NN0 active and NN1 standby, creating some blocks");
    DFSTestUtil.createFile(fs, new Path("/test"), 10*BLOCK_SIZE, (short) 3, 1L);

    // Roll edit log so that, when the SBN restarts, it will load
    // the namespace during startup.
    nn0.getRpcServer().rollEditLog();
 
    banner("Restarting standby");
    restartStandby();
    
    // It will initially have all of the blocks necessary.
    String status = nn1.getNamesystem().getSafemode();
    assertTrue("Bad safemode status: '" + status + "'",
        status.startsWith(
            "Safe mode is ON." +
            "The reported blocks 10 has reached the threshold 0.9990 of " +
            "total blocks 10. Safe mode will be turned off automatically"));

    // Delete those blocks while the SBN is in safe mode - this
    // should reduce it back below the threshold
    banner("Removing the blocks without rolling the edit log");
    fs.delete(new Path("/test"), true);
    BlockManagerTestUtil.computeAllPendingWork(
        nn0.getNamesystem().getBlockManager());
    
    banner("Triggering deletions on DNs and Deletion Reports");
    cluster.triggerHeartbeats();
    TestDNFencing.waitForDNDeletions(cluster);
    cluster.triggerDeletionReports();

    status = nn1.getNamesystem().getSafemode();
    assertTrue("Bad safemode status: '" + status + "'",
        status.startsWith(
            "Safe mode is ON." +
            "The reported blocks 0 needs additional 10 blocks"));

    banner("Waiting for standby to catch up to active namespace");
    TestEditLogTailer.waitForStandbyToCatchUp(nn0, nn1);

    status = nn1.getNamesystem().getSafemode();
    assertTrue("Bad safemode status: '" + status + "'",
        status.startsWith(
            "Safe mode is ON." +
            "The reported blocks 0 has reached the threshold 0.9990 of " +
            "total blocks 0. Safe mode will be turned off automatically"));
  }
  
  /**
   * Set up a namesystem with several edits, both deletions and
   * additions, and failover to a new NN while that NN is in
   * safemode. Ensure that it will exit safemode.
   */
  @Test
  public void testComplexFailoverIntoSafemode() throws Exception {
    banner("Starting with NN0 active and NN1 standby, creating some blocks");
    DFSTestUtil.createFile(fs, new Path("/test"), 3*BLOCK_SIZE, (short) 3, 1L);
    // Roll edit log so that, when the SBN restarts, it will load
    // the namespace during startup and enter safemode.
    nn0.getRpcServer().rollEditLog();

    banner("Creating some blocks that won't be in the edit log");
    DFSTestUtil.createFile(fs, new Path("/test2"), 5*BLOCK_SIZE, (short) 3, 1L);
    
    banner("Deleting the original blocks");
    fs.delete(new Path("/test"), true);
    
    banner("Restarting standby");
    restartStandby();

    // We expect it to be stuck in safemode (not the extension) because
    // the block reports are delayed (since they include blocks
    // from /test2 which are too-high genstamps.
    String status = nn1.getNamesystem().getSafemode();
    assertTrue("Bad safemode status: '" + status + "'",
        status.startsWith(
            "Safe mode is ON." +
            "The reported blocks 0 needs additional 3 blocks to reach"));

    // Initiate a failover into it while it's in safemode
    banner("Initiating a failover into NN1 in safemode");
    NameNodeAdapter.abortEditLogs(nn0);
    cluster.transitionToActive(1);

    status = nn1.getNamesystem().getSafemode();
    assertTrue("Bad safemode status: '" + status + "'",
        status.startsWith(
            "Safe mode is ON." +
            "The reported blocks 5 has reached the threshold 0.9990 of " +
            "total blocks 5. Safe mode will be turned off automatically"));
  }
  
  /**
   * Print a big banner in the test log to make debug easier.
   */
  static void banner(String string) {
    LOG.info("\n\n\n\n================================================\n" +
        string + "\n" +
        "==================================================\n\n");
  }

}