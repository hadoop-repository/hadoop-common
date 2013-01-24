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
package org.apache.hadoop.hdfs.server.namenode;

import static org.apache.hadoop.util.Time.now;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PathIsNotDirectoryException;
import org.apache.hadoop.fs.UnresolvedLinkException;
import org.apache.hadoop.fs.permission.PermissionStatus;
import org.apache.hadoop.hdfs.DFSUtil;
import org.apache.hadoop.hdfs.protocol.HdfsConstants;
import org.apache.hadoop.hdfs.protocol.LayoutVersion;
import org.apache.hadoop.hdfs.protocol.LayoutVersion.Feature;
import org.apache.hadoop.hdfs.server.blockmanagement.BlockInfo;
import org.apache.hadoop.hdfs.server.blockmanagement.BlockManager;
import org.apache.hadoop.hdfs.server.common.InconsistentFSStateException;
import org.apache.hadoop.hdfs.server.namenode.INodeDirectory.INodesInPath;
import org.apache.hadoop.hdfs.server.namenode.snapshot.INodeDirectorySnapshottable;
import org.apache.hadoop.hdfs.server.namenode.snapshot.INodeDirectoryWithSnapshot;
import org.apache.hadoop.hdfs.server.namenode.snapshot.Snapshot;
import org.apache.hadoop.hdfs.server.namenode.snapshot.SnapshotFSImageFormat;
import org.apache.hadoop.hdfs.util.ReadOnlyList;
import org.apache.hadoop.io.MD5Hash;
import org.apache.hadoop.io.Text;

/**
 * Contains inner classes for reading or writing the on-disk format for
 * FSImages.
 * 
 * In particular, the format of the FSImage looks like:
 * <pre>
 * FSImage {
 *   layoutVersion: int, namespaceID: int, numberItemsInFSDirectoryTree: long,
 *   namesystemGenerationStamp: long, transactionID: long, 
 *   snapshotCounter: int, numberOfSnapshots: int, numOfSnapshottableDirs: int,
 *   {FSDirectoryTree, FilesUnderConstruction, SecretManagerState} (can be compressed)
 * }
 * 
 * FSDirectoryTree (if {@link Feature#FSIMAGE_NAME_OPTIMIZATION} is supported) {
 *   INodeInfo of root, numberOfChildren of root: int
 *   [list of INodeInfo of root's children],
 *   [list of INodeDirectoryInfo of root's directory children]
 * }
 * 
 * FSDirectoryTree (if {@link Feature#FSIMAGE_NAME_OPTIMIZATION} not supported){
 *   [list of INodeInfo of INodes in topological order]
 * }
 * 
 * INodeInfo {
 *   {
 *     localName: short + byte[]
 *   } when {@link Feature#FSIMAGE_NAME_OPTIMIZATION} is supported
 *   or 
 *   {
 *     fullPath: byte[]
 *   } when {@link Feature#FSIMAGE_NAME_OPTIMIZATION} is not supported
 *   replicationFactor: short, modificationTime: long,
 *   accessTime: long, preferredBlockSize: long,
 *   numberOfBlocks: int (-1 for INodeDirectory, -2 for INodeSymLink),
 *   { 
 *     nsQuota: long, dsQuota: long, 
 *     {
 *       isINodeSnapshottable: byte,
 *       isINodeWithSnapshot: byte (if isINodeSnapshottable is false)
 *     } (when {@link Feature#SNAPSHOT} is supported), 
 *     fsPermission: short, PermissionStatus
 *   } for INodeDirectory
 *   or 
 *   {
 *     symlinkString, fsPermission: short, PermissionStatus
 *   } for INodeSymlink
 *   or
 *   {
 *     containsBlock: byte (when {@link Feature#SNAPSHOT} is supported),
 *     [list of BlockInfo] (when {@link Feature#SNAPSHOT} is not supported or 
 *     containsBlock is true),
 *     {
 *       snapshotFileSize: long,
 *       isINodeFileWithLink: byte (if ComputedFileSize is negative),
 *     } (when {@link Feature#SNAPSHOT} is supported), 
 *     fsPermission: short, PermissionStatus
 *   } for INodeFile
 * }
 * 
 * INodeDirectoryInfo {
 *   fullPath of the directory: short + byte[],
 *   numberOfChildren: int, [list of INodeInfo of children INode],
 *   {
 *     numberOfSnapshots: int,
 *     [list of Snapshot] (when NumberOfSnapshots is positive),
 *     numberOfSnapshotDiffs: int,
 *     [list of SnapshotDiff] (NumberOfSnapshotDiffs is positive),
 *     number of children that are directories,
 *     [list of INodeDirectoryInfo of the directory children] (includes
 *     snapshot copies of deleted sub-directories)
 *   } (when {@link Feature#SNAPSHOT} is supported), 
 * }
 * 
 * Snapshot {
 *   snapshotID: int, root of Snapshot: INodeDirectoryInfo (its local name is 
 *   the name of the snapshot)
 * }
 * 
 * SnapshotDiff {
 *   childrenSize: int, 
 *   full path of the root of the associated Snapshot: short + byte[], 
 *   isSnapshotRoot: byte, 
 *   snapshotINodeIsNotNull: byte (when isSnapshotRoot is false),
 *   snapshotINode: INodeDirectory (when SnapshotINodeIsNotNull is true), Diff 
 * }
 * 
 * Diff {
 *   createdListSize: int, [Local name of INode in created list],
 *   deletedListSize: int, [INode in deleted list: INodeInfo]
 * }
 * </pre>
 */
@InterfaceAudience.Private
@InterfaceStability.Evolving
public class FSImageFormat {
  private static final Log LOG = FSImage.LOG;
  
  // Static-only class
  private FSImageFormat() {}
  
  /**
   * A one-shot class responsible for loading an image. The load() function
   * should be called once, after which the getter methods may be used to retrieve
   * information about the image that was loaded, if loading was successful.
   */
  public static class Loader {
    private final Configuration conf;
    /** which namesystem this loader is working for */
    private final FSNamesystem namesystem;

    /** Set to true once a file has been loaded using this loader. */
    private boolean loaded = false;

    /** The transaction ID of the last edit represented by the loaded file */
    private long imgTxId;
    /** The MD5 sum of the loaded file */
    private MD5Hash imgDigest;

    Loader(Configuration conf, FSNamesystem namesystem) {
      this.conf = conf;
      this.namesystem = namesystem;
    }

    /**
     * Return the MD5 checksum of the image that has been loaded.
     * @throws IllegalStateException if load() has not yet been called.
     */
    MD5Hash getLoadedImageMd5() {
      checkLoaded();
      return imgDigest;
    }

    long getLoadedImageTxId() {
      checkLoaded();
      return imgTxId;
    }

    /**
     * Throw IllegalStateException if load() has not yet been called.
     */
    private void checkLoaded() {
      if (!loaded) {
        throw new IllegalStateException("Image not yet loaded!");
      }
    }

    /**
     * Throw IllegalStateException if load() has already been called.
     */
    private void checkNotLoaded() {
      if (loaded) {
        throw new IllegalStateException("Image already loaded!");
      }
    }

    void load(File curFile) throws IOException {
      checkNotLoaded();
      assert curFile != null : "curFile is null";

      long startTime = now();

      //
      // Load in bits
      //
      MessageDigest digester = MD5Hash.getDigester();
      DigestInputStream fin = new DigestInputStream(
           new FileInputStream(curFile), digester);

      DataInputStream in = new DataInputStream(fin);
      try {
        // read image version: first appeared in version -1
        int imgVersion = in.readInt();
        if (getLayoutVersion() != imgVersion) {
          throw new InconsistentFSStateException(curFile, 
              "imgVersion " + imgVersion +
              " expected to be " + getLayoutVersion());
        }

        // read namespaceID: first appeared in version -2
        in.readInt();

        long numFiles = in.readLong();

        // read in the last generation stamp.
        long genstamp = in.readLong();
        namesystem.setGenerationStamp(genstamp); 
        
        // read the transaction ID of the last edit represented by
        // this image
        if (LayoutVersion.supports(Feature.STORED_TXIDS, imgVersion)) {
          imgTxId = in.readLong();
        } else {
          imgTxId = 0;
        }
        
        if (LayoutVersion.supports(Feature.SNAPSHOT, imgVersion)) {
          namesystem.getSnapshotManager().read(in);
        }

        // read compression related info
        FSImageCompression compression;
        if (LayoutVersion.supports(Feature.FSIMAGE_COMPRESSION, imgVersion)) {
          compression = FSImageCompression.readCompressionHeader(conf, in);
        } else {
          compression = FSImageCompression.createNoopCompression();
        }
        in = compression.unwrapInputStream(fin);

        LOG.info("Loading image file " + curFile + " using " + compression);
        // reset INodeId. TODO: remove this after inodeId is persisted in fsimage
        namesystem.resetLastInodeIdWithoutChecking(INodeId.LAST_RESERVED_ID); 
        // load all inodes
        LOG.info("Number of files = " + numFiles);
        if (LayoutVersion.supports(Feature.FSIMAGE_NAME_OPTIMIZATION,
            imgVersion)) {
          if (LayoutVersion.supports(Feature.SNAPSHOT, imgVersion)) {
            loadLocalNameINodesWithSnapshot(in);
          } else {
            loadLocalNameINodes(numFiles, in);
          }
        } else {
          loadFullNameINodes(numFiles, in);
        }

        loadFilesUnderConstruction(in);

        loadSecretManagerState(in);

        // make sure to read to the end of file
        int eof = in.read();
        assert eof == -1 : "Should have reached the end of image file " + curFile;
      } finally {
        in.close();
      }

      imgDigest = new MD5Hash(digester.digest());
      loaded = true;
      
      LOG.info("Image file of size " + curFile.length() + " loaded in " 
          + (now() - startTime)/1000 + " seconds.");
    }

  /** Update the root node's attributes */
  private void updateRootAttr(INode root) {                                                           
    long nsQuota = root.getNsQuota();
    long dsQuota = root.getDsQuota();
    FSDirectory fsDir = namesystem.dir;
    if (nsQuota != -1 || dsQuota != -1) {
      fsDir.rootDir.setQuota(nsQuota, dsQuota, null);
    }
    fsDir.rootDir.cloneModificationTime(root);
    fsDir.rootDir.clonePermissionStatus(root);    
  }
  
    /**
     * Load fsimage files when 1) only local names are stored, 
     * and 2) snapshot is supported.
     * 
     * @param in Image input stream
     */
    private void loadLocalNameINodesWithSnapshot(DataInputStream in)
        throws IOException {
      assert LayoutVersion.supports(Feature.FSIMAGE_NAME_OPTIMIZATION,
          getLayoutVersion());
      assert LayoutVersion.supports(Feature.SNAPSHOT, getLayoutVersion());
      
      // load root
      loadRoot(in);
      // load rest of the nodes recursively
      loadDirectoryWithSnapshot(in);
    }
    
  /** 
   * load fsimage files assuming only local names are stored
   *   
   * @param numFiles number of files expected to be read
   * @param in image input stream
   * @throws IOException
   */  
   private void loadLocalNameINodes(long numFiles, DataInputStream in) 
   throws IOException {
     assert LayoutVersion.supports(Feature.FSIMAGE_NAME_OPTIMIZATION,
         getLayoutVersion());
     assert numFiles > 0;

     // load root
     loadRoot(in);
     // have loaded the first file (the root)
     numFiles--; 

     // load rest of the nodes directory by directory
     while (numFiles > 0) {
       numFiles -= loadDirectory(in);
     }
     if (numFiles != 0) {
       throw new IOException("Read unexpect number of files: " + -numFiles);
     }
   }
   
    /**
     * Load information about root, and use the information to update the root
     * directory of NameSystem.
     * @param in The {@link DataInputStream} instance to read.
     */
    private void loadRoot(DataInputStream in) throws IOException {
      // load root
      if (in.readShort() != 0) {
        throw new IOException("First node is not root");
      }
      INode root = loadINode(in);
      // update the root's attributes
      updateRootAttr(root);
    }
   
    /** Load children nodes for the parent directory. */
    private void loadChildren(INodeDirectory parent, DataInputStream in)
        throws IOException {
      int numChildren = in.readInt();
      for (int i = 0; i < numChildren; i++) {
        // load single inode
        byte[] localName = new byte[in.readShort()];
        in.readFully(localName); // read local name
        INode newNode = loadINode(in); // read rest of inode
        newNode.setLocalName(localName);
        addToParent(parent, newNode);
      }
    }
    
    /**
     * Load a directory when snapshot is supported.
     * @param in The {@link DataInputStream} instance to read.
     */
    private void loadDirectoryWithSnapshot(DataInputStream in)
        throws IOException {
      // Step 1. Identify the parent INode
      String parentPath = FSImageSerialization.readString(in);
      final INodeDirectory parent = INodeDirectory.valueOf(
          namesystem.dir.rootDir.getNode(parentPath, false), parentPath);
      
      // Step 2. Load children nodes under parent
      loadChildren(parent, in);
      
      // Step 3. Load snapshots if parent is snapshottable
      int numSnapshots = in.readInt();
      INodeDirectorySnapshottable snapshottableParent = null;
      if (numSnapshots >= 0) {
        snapshottableParent = (INodeDirectorySnapshottable) parent;
        // load snapshots and snapshotQuota
        SnapshotFSImageFormat.loadSnapshotList(snapshottableParent,
            numSnapshots, in, this);
      }
      
      // Step 4. load SnapshotDiff list
      int numSnapshotDiffs = in.readInt();
      if (numSnapshotDiffs >= 0) {
        INodeDirectoryWithSnapshot parentWithSnapshot = 
            (INodeDirectoryWithSnapshot) parent;
        // load SnapshotDiff list
        SnapshotFSImageFormat.loadSnapshotDiffList(parentWithSnapshot,
            numSnapshotDiffs, in, this);
      }
      
      // Recursively load sub-directories, including snapshot copies of deleted
      // directories
      int numSubTree = in.readInt();
      for (int i = 0; i < numSubTree; i++) {
        loadDirectoryWithSnapshot(in);
      }
    }
    
   /**
    * Load all children of a directory
    * 
    * @param in
    * @return number of child inodes read
    * @throws IOException
    */
   private int loadDirectory(DataInputStream in) throws IOException {
     String parentPath = FSImageSerialization.readString(in);
     FSDirectory fsDir = namesystem.dir;
     final INodeDirectory parent = INodeDirectory.valueOf(
         fsDir.rootDir.getNode(parentPath, true), parentPath);

     int numChildren = in.readInt();
     for(int i=0; i<numChildren; i++) {
       // load single inode
       byte[] localName = new byte[in.readShort()];
       in.readFully(localName); // read local name
       INode newNode = loadINode(in); // read rest of inode

       // add to parent
       newNode.setLocalName(localName);
       addToParent(parent, newNode);
     }
     return numChildren;
   }

  /**
   * load fsimage files assuming full path names are stored
   * 
   * @param numFiles total number of files to load
   * @param in data input stream
   * @throws IOException if any error occurs
   */
  private void loadFullNameINodes(long numFiles,
      DataInputStream in) throws IOException {
    byte[][] pathComponents;
    byte[][] parentPath = {{}};      
    FSDirectory fsDir = namesystem.dir;
    INodeDirectory parentINode = fsDir.rootDir;
    for (long i = 0; i < numFiles; i++) {
      pathComponents = FSImageSerialization.readPathComponents(in);
      INode newNode = loadINode(in);

      if (isRoot(pathComponents)) { // it is the root
        // update the root's attributes
        updateRootAttr(newNode);
        continue;
      }
      // check if the new inode belongs to the same parent
      if(!isParent(pathComponents, parentPath)) {
        parentINode = getParentINodeDirectory(pathComponents);
        parentPath = getParent(pathComponents);
      }

      // add new inode
      newNode.setLocalName(pathComponents[pathComponents.length-1]);
      addToParent(parentINode, newNode);
    }
  }

  private INodeDirectory getParentINodeDirectory(byte[][] pathComponents
      ) throws FileNotFoundException, PathIsNotDirectoryException,
      UnresolvedLinkException {
    if (pathComponents.length < 2) { // root
      return null;
    }
    // Gets the parent INode
    final INodesInPath inodes = namesystem.dir.rootDir.getExistingPathINodes(
        pathComponents, 2, false);
    return INodeDirectory.valueOf(inodes.getINode(0), pathComponents);
  }

  /**
   * Add the child node to parent and, if child is a file, update block map.
   * This method is only used for image loading so that synchronization,
   * modification time update and space count update are not needed.
   */
  private void addToParent(INodeDirectory parent, INode child) {
    // NOTE: This does not update space counts for parents
    if (!parent.addChild(child, false, null)) {
      return;
    }
    namesystem.dir.cacheName(child);

    if (child.isFile()) {
      // Add file->block mapping
      final INodeFile file = (INodeFile)child;
      final BlockInfo[] blocks = file.getBlocks();
      final BlockManager bm = namesystem.getBlockManager();
      for (int i = 0; i < blocks.length; i++) {
        file.setBlock(i, bm.addBlockCollection(blocks[i], file));
      }
    }
  }

    /** @return The FSDirectory of the namesystem where the fsimage is loaded */
    public FSDirectory getFSDirectoryInLoading() {
      return namesystem.dir;
    }
  
  /**
   * load an inode from fsimage except for its name
   * 
   * @param in data input stream from which image is read
   * @return an inode
   */
  public INode loadINode(DataInputStream in) throws IOException {
    long modificationTime = 0;
    long atime = 0;
    long blockSize = 0;
    long computeFileSize = -1;
    boolean snapshottable = false;
    boolean withSnapshot = false;
    boolean withLink = false;
    
    int imgVersion = getLayoutVersion();
    long inodeId = namesystem.allocateNewInodeId();
    
    short replication = in.readShort();
    replication = namesystem.getBlockManager().adjustReplication(replication);
    modificationTime = in.readLong();
    if (LayoutVersion.supports(Feature.FILE_ACCESS_TIME, imgVersion)) {
      atime = in.readLong();
    }
    blockSize = in.readLong();
    int numBlocks = in.readInt();
    BlockInfo blocks[] = null;

    if (numBlocks >= 0) {
      // to indicate INodeFileWithLink, blocks may be set as null while
      // numBlocks is set to 0
      blocks = LayoutVersion.supports(Feature.SNAPSHOT, imgVersion) ? (in
            .readBoolean() ? new BlockInfo[numBlocks] : null)
            : new BlockInfo[numBlocks];
      
      for (int j = 0; j < numBlocks; j++) {
        blocks[j] = new BlockInfo(replication);
        blocks[j].readFields(in);
      }
      if (LayoutVersion.supports(Feature.SNAPSHOT, imgVersion)) {
        computeFileSize = in.readLong();
        if (computeFileSize < 0) {
          withLink = in.readBoolean();
        }
      }
    }
    
    // get quota only when the node is a directory
    long nsQuota = -1L;
    if (blocks == null && numBlocks == -1) {
      nsQuota = in.readLong();
    }
    long dsQuota = -1L;
    if (LayoutVersion.supports(Feature.DISKSPACE_QUOTA, imgVersion)
        && blocks == null && numBlocks == -1) {
      dsQuota = in.readLong();
    }
    if (LayoutVersion.supports(Feature.SNAPSHOT, imgVersion)
        && blocks == null && numBlocks == -1) {
      snapshottable = in.readBoolean();
      if (!snapshottable) {
        withSnapshot = in.readBoolean();
      }
    }
    
    // Read the symlink only when the node is a symlink
    String symlink = "";
    if (numBlocks == -2) {
      symlink = Text.readString(in);
    }
    
    PermissionStatus permissions = PermissionStatus.read(in);

    return INode.newINode(inodeId, permissions, blocks, symlink, replication,
        modificationTime, atime, nsQuota, dsQuota, blockSize, numBlocks,
        withLink, computeFileSize, snapshottable, withSnapshot);
  }

    private void loadFilesUnderConstruction(DataInputStream in)
    throws IOException {
      FSDirectory fsDir = namesystem.dir;
      int size = in.readInt();

      LOG.info("Number of files under construction = " + size);

      for (int i = 0; i < size; i++) {
        INodeFileUnderConstruction cons =
          FSImageSerialization.readINodeUnderConstruction(in);

        // verify that file exists in namespace
        String path = cons.getLocalName();
        final INodesInPath iip = fsDir.getINodesInPath(path);
        INodeFile oldnode = INodeFile.valueOf(iip.getINode(0), path);
        fsDir.unprotectedReplaceINodeFile(path, oldnode, cons,
            iip.getLatestSnapshot());
        namesystem.leaseManager.addLease(cons.getClientName(), path); 
      }
    }

    private void loadSecretManagerState(DataInputStream in)
        throws IOException {
      int imgVersion = getLayoutVersion();

      if (!LayoutVersion.supports(Feature.DELEGATION_TOKEN, imgVersion)) {
        //SecretManagerState is not available.
        //This must not happen if security is turned on.
        return; 
      }
      namesystem.loadSecretManagerState(in);
    }

    private int getLayoutVersion() {
      return namesystem.getFSImage().getStorage().getLayoutVersion();
    }

    private boolean isRoot(byte[][] path) {
      return path.length == 1 &&
        path[0] == null;    
    }

    private boolean isParent(byte[][] path, byte[][] parent) {
      if (path == null || parent == null)
        return false;
      if (parent.length == 0 || path.length != parent.length + 1)
        return false;
      boolean isParent = true;
      for (int i = 0; i < parent.length; i++) {
        isParent = isParent && Arrays.equals(path[i], parent[i]); 
      }
      return isParent;
    }

    /**
     * Return string representing the parent of the given path.
     */
    String getParent(String path) {
      return path.substring(0, path.lastIndexOf(Path.SEPARATOR));
    }
    
    byte[][] getParent(byte[][] path) {
      byte[][] result = new byte[path.length - 1][];
      for (int i = 0; i < result.length; i++) {
        result[i] = new byte[path[i].length];
        System.arraycopy(path[i], 0, result[i], 0, path[i].length);
      }
      return result;
    }
  }
  
  /**
   * A one-shot class responsible for writing an image file.
   * The write() function should be called once, after which the getter
   * functions may be used to retrieve information about the file that was written.
   */
  static class Saver {
    private final SaveNamespaceContext context;
    /** Set to true once an image has been written */
    private boolean saved = false;
    
    /** The MD5 checksum of the file that was written */
    private MD5Hash savedDigest;

    static private final byte[] PATH_SEPARATOR = DFSUtil.string2Bytes(Path.SEPARATOR);

    /** @throws IllegalStateException if the instance has not yet saved an image */
    private void checkSaved() {
      if (!saved) {
        throw new IllegalStateException("FSImageSaver has not saved an image");
      }
    }
    
    /** @throws IllegalStateException if the instance has already saved an image */
    private void checkNotSaved() {
      if (saved) {
        throw new IllegalStateException("FSImageSaver has already saved an image");
      }
    }
    

    Saver(SaveNamespaceContext context) {
      this.context = context;
    }

    /**
     * Return the MD5 checksum of the image file that was saved.
     */
    MD5Hash getSavedDigest() {
      checkSaved();
      return savedDigest;
    }

    void save(File newFile, FSImageCompression compression) throws IOException {
      checkNotSaved();

      final FSNamesystem sourceNamesystem = context.getSourceNamesystem();
      FSDirectory fsDir = sourceNamesystem.dir;
      long startTime = now();
      //
      // Write out data
      //
      MessageDigest digester = MD5Hash.getDigester();
      FileOutputStream fout = new FileOutputStream(newFile);
      DigestOutputStream fos = new DigestOutputStream(fout, digester);
      DataOutputStream out = new DataOutputStream(fos);
      try {
        out.writeInt(HdfsConstants.LAYOUT_VERSION);
        // We use the non-locked version of getNamespaceInfo here since
        // the coordinating thread of saveNamespace already has read-locked
        // the namespace for us. If we attempt to take another readlock
        // from the actual saver thread, there's a potential of a
        // fairness-related deadlock. See the comments on HDFS-2223.
        out.writeInt(sourceNamesystem.unprotectedGetNamespaceInfo()
            .getNamespaceID());
        out.writeLong(fsDir.rootDir.numItemsInTree());
        out.writeLong(sourceNamesystem.getGenerationStamp());
        out.writeLong(context.getTxId());
        sourceNamesystem.getSnapshotManager().write(out);
        
        // write compression info and set up compressed stream
        out = compression.writeHeaderAndWrapStream(fos);
        LOG.info("Saving image file " + newFile +
                 " using " + compression);

        byte[] byteStore = new byte[4*HdfsConstants.MAX_PATH_LENGTH];
        ByteBuffer strbuf = ByteBuffer.wrap(byteStore);
        // save the root
        FSImageSerialization.saveINode2Image(fsDir.rootDir, out);
        // save the rest of the nodes
        saveImage(strbuf, fsDir.rootDir, out, null);
        // save files under construction
        sourceNamesystem.saveFilesUnderConstruction(out);
        context.checkCancelled();
        sourceNamesystem.saveSecretManagerState(out);
        strbuf = null;
        context.checkCancelled();
        out.flush();
        context.checkCancelled();
        fout.getChannel().force(true);
      } finally {
        out.close();
      }

      saved = true;
      // set md5 of the saved image
      savedDigest = new MD5Hash(digester.digest());

      LOG.info("Image file of size " + newFile.length() + " saved in " 
          + (now() - startTime)/1000 + " seconds.");
    }

    /**
     * Save children INodes.
     * @param children The list of children INodes
     * @param out The DataOutputStream to write
     * @return Number of children that are directory
     */
    private int saveChildren(ReadOnlyList<INode> children, DataOutputStream out)
        throws IOException {
      // Write normal children INode. 
      out.writeInt(children.size());
      int dirNum = 0;
      int i = 0;
      for(INode child : children) {
        // print all children first
        FSImageSerialization.saveINode2Image(child, out);
        if (child.isDirectory()) {
          dirNum++;
        }
        if (i++ % 50 == 0) {
          context.checkCancelled();
        }
      }
      return dirNum;
    }
    
    /**
     * The nonSnapshotPath is a path without snapshot in order to enable buffer
     * reuse. If the snapshot is not null, we need to compute a snapshot path.
     * E.g., when nonSnapshotPath is "/test/foo/bar/" and the snapshot is s1 of
     * /test, we actually want to save image for directory /test/foo/bar/ under
     * snapshot s1 of /test, and the path to save thus should be
     * "/test/.snapshot/s1/foo/bar/".
     * 
     * @param nonSnapshotPath The path without snapshot related information.
     * @param snapshot The snapshot associated with the inode that the path 
     *                 actually leads to.
     * @return The snapshot path.                
     */
    private String computeSnapshotPath(String nonSnapshotPath, 
        Snapshot snapshot) {
      String snapshotParentFullPath = snapshot.getRoot().getParent()
          .getFullPathName();
      String snapshotName = snapshot.getRoot().getLocalName();
      String relativePath = nonSnapshotPath.equals(snapshotParentFullPath) ? 
          Path.SEPARATOR : nonSnapshotPath.substring(
               snapshotParentFullPath.length());
      String snapshotFullPath = snapshotParentFullPath + Path.SEPARATOR
          + HdfsConstants.DOT_SNAPSHOT_DIR + Path.SEPARATOR + snapshotName
          + relativePath;
      return snapshotFullPath;
    }
    
    /**
     * Save file tree image starting from the given root.
     * This is a recursive procedure, which first saves all children and 
     * snapshot diffs of a current directory and then moves inside the 
     * sub-directories.
     * 
     * @param currentDirName A ByteBuffer storing the path leading to the 
     *                       current node. For a snapshot node, the path is
     *                       (the snapshot path - ".snapshot/snapshot_name")
     * @param current The current node
     * @param out The DataoutputStream to write the image
     * @param snapshot The possible snapshot associated with the current node
     */
    private void saveImage(ByteBuffer currentDirName, INodeDirectory current,
        DataOutputStream out, Snapshot snapshot)
        throws IOException {
      final ReadOnlyList<INode> children = current.getChildrenList(null);
      int dirNum = 0;
      Map<Snapshot, List<INodeDirectory>> snapshotDirMap = null;
      if (current instanceof INodeDirectoryWithSnapshot) {
        snapshotDirMap = new HashMap<Snapshot, List<INodeDirectory>>();
        dirNum += ((INodeDirectoryWithSnapshot) current).
            getSnapshotDirectory(snapshotDirMap);
      }
      
      // 1. Print prefix (parent directory name)
      int prefixLen = currentDirName.position();
      if (snapshot == null) {
        if (prefixLen == 0) {  // root
          out.writeShort(PATH_SEPARATOR.length);
          out.write(PATH_SEPARATOR);
        } else {  // non-root directories
          out.writeShort(prefixLen);
          out.write(currentDirName.array(), 0, prefixLen);
        }
      } else {
        String nonSnapshotPath = prefixLen == 0 ? Path.SEPARATOR : DFSUtil
            .bytes2String(currentDirName.array(), 0, prefixLen);
        String snapshotFullPath = computeSnapshotPath(nonSnapshotPath, 
            snapshot);
        byte[] snapshotFullPathBytes = DFSUtil.string2Bytes(snapshotFullPath);
        out.writeShort(snapshotFullPathBytes.length);
        out.write(snapshotFullPathBytes);
      }
      
      // 2. Write children INode 
      dirNum += saveChildren(children, out);
      
      // 3. Write INodeDirectorySnapshottable#snapshotsByNames to record all
      // Snapshots
      if (current instanceof INodeDirectorySnapshottable) {
        INodeDirectorySnapshottable snapshottableNode = 
            (INodeDirectorySnapshottable) current;
        SnapshotFSImageFormat.saveSnapshots(snapshottableNode, out);
      } else {
        out.writeInt(-1); // # of snapshots
      }
      
      // 4. Write SnapshotDiff lists.
      if (current instanceof INodeDirectoryWithSnapshot) {
        INodeDirectoryWithSnapshot sNode = (INodeDirectoryWithSnapshot) current;
        SnapshotFSImageFormat.saveSnapshotDiffs(sNode, out);
      } else {
        out.writeInt(-1); // # of SnapshotDiffs
      }
      
      // Write sub-tree of sub-directories, including possible snapshots of 
      // deleted sub-directories
      out.writeInt(dirNum); // the number of sub-directories
      for(INode child : children) {
        if(!child.isDirectory())
          continue;
        currentDirName.put(PATH_SEPARATOR).put(child.getLocalNameBytes());
        saveImage(currentDirName, (INodeDirectory)child, out, snapshot);
        currentDirName.position(prefixLen);
      }
      if (snapshotDirMap != null) {
        for (Snapshot ss : snapshotDirMap.keySet()) {
          List<INodeDirectory> snapshotSubDirs = snapshotDirMap.get(ss);
          for (INodeDirectory subDir : snapshotSubDirs) {
            currentDirName.put(PATH_SEPARATOR).put(subDir.getLocalNameBytes());
            saveImage(currentDirName, subDir, out, ss);
            currentDirName.position(prefixLen);
          }
        }
      }
    }
  }
}