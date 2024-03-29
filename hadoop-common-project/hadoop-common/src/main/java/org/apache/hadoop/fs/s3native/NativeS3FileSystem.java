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

package org.apache.hadoop.fs.s3native;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import log_events.org.apache.hadoop.FsNamespace;
import org.ngmon.logger.core.SimpleLogger;
import org.ngmon.logger.logtranslator.LogGlobal;
import org.ngmon.logger.core.LoggerFactory;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.BufferedFSInputStream;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FSInputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.fs.s3.S3Exception;
import org.apache.hadoop.io.retry.RetryPolicies;
import org.apache.hadoop.io.retry.RetryPolicy;
import org.apache.hadoop.io.retry.RetryProxy;
import org.apache.hadoop.util.Progressable;

/**
 * <p>
 * A {@link FileSystem} for reading and writing files stored on
 * <a href="http://aws.amazon.com/s3">Amazon S3</a>.
 * Unlike {@link org.apache.hadoop.fs.s3.S3FileSystem} this implementation
 * stores files on S3 in their
 * native form so they can be read by other S3 tools.
 *
 * A note about directories. S3 of course has no "native" support for them.
 * The idiom we choose then is: for any directory created by this class,
 * we use an empty object "#{dirpath}_$folder$" as a marker.
 * Further, to interoperate with other S3 tools, we also accept the following:
 *  - an object "#{dirpath}/' denoting a directory marker
 *  - if there exists any objects with the prefix "#{dirpath}/", then the
 *    directory is said to exist
 *  - if both a file with the name of a directory and a marker for that
 *    directory exists, then the *file masks the directory*, and the directory
 *    is never returned.
 * </p>
 * @see org.apache.hadoop.fs.s3.S3FileSystem
 */
@InterfaceAudience.Public
@InterfaceStability.Stable
public class NativeS3FileSystem extends FileSystem {
  
  public static final /* LogLOG=LogFactory.getLog(NativeS3FileSystem.class); */
			FsNamespace LOG = LoggerFactory.getLogger(FsNamespace.class, new SimpleLogger());
  
  private static final String FOLDER_SUFFIX = "_$folder$";
  static final String PATH_DELIMITER = Path.SEPARATOR;
  private static final int S3_MAX_LISTING_LENGTH = 1000;
  
  static class NativeS3FsInputStream extends FSInputStream {
    
    private NativeFileSystemStore store;
    private Statistics statistics;
    private InputStream in;
    private final String key;
    private long pos = 0;
    
    public NativeS3FsInputStream(NativeFileSystemStore store, Statistics statistics, InputStream in, String key) {
      this.store = store;
      this.statistics = statistics;
      this.in = in;
      this.key = key;
    }
    
    @Override
    public synchronized int read() throws IOException {
      int result = -1;
      try {
        result = in.read();
      } catch (IOException e) {
        /* LOG.info("Received IOException while reading '"+key+"', attempting to reopen.") */
        LOG.received_ioexception_while_reading_attem(key).info();
        seek(pos);
        result = in.read();
      } 
      if (result != -1) {
        pos++;
      }
      if (statistics != null && result != -1) {
        statistics.incrementBytesRead(1);
      }
      return result;
    }
    @Override
    public synchronized int read(byte[] b, int off, int len)
      throws IOException {
      
      int result = -1;
      try {
        result = in.read(b, off, len);
      } catch (IOException e) {
        /* LOG.info("Received IOException while reading '"+key+"', attempting to reopen.") */
        LOG.received_ioexception_while_reading_attem(key).info();
        seek(pos);
        result = in.read(b, off, len);
      }
      if (result > 0) {
        pos += result;
      }
      if (statistics != null && result > 0) {
        statistics.incrementBytesRead(result);
      }
      return result;
    }

    @Override
    public void close() throws IOException {
      in.close();
    }

    @Override
    public synchronized void seek(long pos) throws IOException {
      in.close();
      /* LOG.info("Opening key '"+key+"' for reading at position '"+pos+"'") */
      LOG.opening_key_for_reading_position(key, pos).info();
      in = store.retrieve(key, pos);
      this.pos = pos;
    }
    @Override
    public synchronized long getPos() throws IOException {
      return pos;
    }
    @Override
    public boolean seekToNewSource(long targetPos) throws IOException {
      return false;
    }
  }
  
  private class NativeS3FsOutputStream extends OutputStream {
    
    private Configuration conf;
    private String key;
    private File backupFile;
    private OutputStream backupStream;
    private MessageDigest digest;
    private boolean closed;
    
    public NativeS3FsOutputStream(Configuration conf,
        NativeFileSystemStore store, String key, Progressable progress,
        int bufferSize) throws IOException {
      this.conf = conf;
      this.key = key;
      this.backupFile = newBackupFile();
      /* LOG.info("OutputStream for key '"+key+"' writing to tempfile '"+this.backupFile+"'") */
      LOG.outputstream_for_key_writing_tempfile(key, String.valueOf(this.backupFile)).tag("methodCall").info();
      try {
        this.digest = MessageDigest.getInstance("MD5");
        this.backupStream = new BufferedOutputStream(new DigestOutputStream(
            new FileOutputStream(backupFile), this.digest));
      } catch (NoSuchAlgorithmException e) {
        /* LOG.warn("Cannot load MD5 digest algorithm,"+"skipping message integrity check.",e) */
        LOG.cannot_load_digest_algorithm_skipping_me(e.toString()).warn();
        this.backupStream = new BufferedOutputStream(
            new FileOutputStream(backupFile));
      }
    }

    private File newBackupFile() throws IOException {
      File dir = new File(conf.get("fs.s3.buffer.dir"));
      if (!dir.mkdirs() && !dir.exists()) {
        throw new IOException("Cannot create S3 buffer directory: " + dir);
      }
      File result = File.createTempFile("output-", ".tmp", dir);
      result.deleteOnExit();
      return result;
    }
    
    @Override
    public void flush() throws IOException {
      backupStream.flush();
    }
    
    @Override
    public synchronized void close() throws IOException {
      if (closed) {
        return;
      }

      backupStream.close();
      /* LOG.info("OutputStream for key '"+key+"' closed. Now beginning upload") */
      LOG.outputstream_for_key_closed_now_beginnin(key).info();
      
      try {
        byte[] md5Hash = digest == null ? null : digest.digest();
        store.storeFile(key, backupFile, md5Hash);
      } finally {
        if (!backupFile.delete()) {
          /* LOG.warn("Could not delete temporary s3n file: "+backupFile) */
          LOG.could_not_delete_temporary_file(backupFile.toString()).warn();
        }
        super.close();
        closed = true;
      } 
      /* LOG.info("OutputStream for key '"+key+"' upload complete") */
      LOG.outputstream_for_key_upload_complete(key).info();
    }

    @Override
    public void write(int b) throws IOException {
      backupStream.write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      backupStream.write(b, off, len);
    }
  }
  
  private URI uri;
  private NativeFileSystemStore store;
  private Path workingDir;
  
  public NativeS3FileSystem() {
    // set store in initialize()
  }
  
  public NativeS3FileSystem(NativeFileSystemStore store) {
    this.store = store;
  }

  /**
   * Return the protocol scheme for the FileSystem.
   * <p/>
   *
   * @return <code>s3n</code>
   */
  @Override
  public String getScheme() {
    return "s3n";
  }

  @Override
  public void initialize(URI uri, Configuration conf) throws IOException {
    super.initialize(uri, conf);
    if (store == null) {
      store = createDefaultStore(conf);
    }
    store.initialize(uri, conf);
    setConf(conf);
    this.uri = URI.create(uri.getScheme() + "://" + uri.getAuthority());
    this.workingDir =
      new Path("/user", System.getProperty("user.name")).makeQualified(this.uri, this.getWorkingDirectory());
  }
  
  private static NativeFileSystemStore createDefaultStore(Configuration conf) {
    NativeFileSystemStore store = new Jets3tNativeFileSystemStore();
    
    RetryPolicy basePolicy = RetryPolicies.retryUpToMaximumCountWithFixedSleep(
        conf.getInt("fs.s3.maxRetries", 4),
        conf.getLong("fs.s3.sleepTimeSeconds", 10), TimeUnit.SECONDS);
    Map<Class<? extends Exception>, RetryPolicy> exceptionToPolicyMap =
      new HashMap<Class<? extends Exception>, RetryPolicy>();
    exceptionToPolicyMap.put(IOException.class, basePolicy);
    exceptionToPolicyMap.put(S3Exception.class, basePolicy);
    
    RetryPolicy methodPolicy = RetryPolicies.retryByException(
        RetryPolicies.TRY_ONCE_THEN_FAIL, exceptionToPolicyMap);
    Map<String, RetryPolicy> methodNameToPolicyMap =
      new HashMap<String, RetryPolicy>();
    methodNameToPolicyMap.put("storeFile", methodPolicy);
    methodNameToPolicyMap.put("rename", methodPolicy);
    
    return (NativeFileSystemStore)
      RetryProxy.create(NativeFileSystemStore.class, store,
          methodNameToPolicyMap);
  }
  
  private static String pathToKey(Path path) {
    if (path.toUri().getScheme() != null && path.toUri().getPath().isEmpty()) {
      // allow uris without trailing slash after bucket to refer to root,
      // like s3n://mybucket
      return "";
    }
    if (!path.isAbsolute()) {
      throw new IllegalArgumentException("Path must be absolute: " + path);
    }
    String ret = path.toUri().getPath().substring(1); // remove initial slash
    if (ret.endsWith("/") && (ret.indexOf("/") != ret.length() - 1)) {
      ret = ret.substring(0, ret.length() -1);
  }
    return ret;
  }
  
  private static Path keyToPath(String key) {
    return new Path("/" + key);
  }
  
  private Path makeAbsolute(Path path) {
    if (path.isAbsolute()) {
      return path;
    }
    return new Path(workingDir, path);
  }

  /** This optional operation is not yet supported. */
  @Override
  public FSDataOutputStream append(Path f, int bufferSize,
      Progressable progress) throws IOException {
    throw new IOException("Not supported");
  }
  
  @Override
  public FSDataOutputStream create(Path f, FsPermission permission,
      boolean overwrite, int bufferSize, short replication, long blockSize,
      Progressable progress) throws IOException {

    if (exists(f) && !overwrite) {
      throw new IOException("File already exists:"+f);
    }
    
    if(LogGlobal.isDebugEnabled()) {
      /* LOG.debug("Creating new file '"+f+"' in S3") */
      LOG.creating_new_file(f.toString()).debug();
    }
    Path absolutePath = makeAbsolute(f);
    String key = pathToKey(absolutePath);
    return new FSDataOutputStream(new NativeS3FsOutputStream(getConf(), store,
        key, progress, bufferSize), statistics);
  }
  
  @Override
  public boolean delete(Path f, boolean recurse) throws IOException {
    FileStatus status;
    try {
      status = getFileStatus(f);
    } catch (FileNotFoundException e) {
      if(LogGlobal.isDebugEnabled()) {
        /* LOG.debug("Delete called for '"+f+"' but file does not exist, so returning false") */
        LOG.delete_called_for_but_file_does(f.toString()).debug();
      }
      return false;
    }
    Path absolutePath = makeAbsolute(f);
    String key = pathToKey(absolutePath);
    if (status.isDirectory()) {
      if (!recurse && listStatus(f).length > 0) {
        throw new IOException("Can not delete " + f + " at is a not empty directory and recurse option is false");
      }

      createParent(f);

      if(LogGlobal.isDebugEnabled()) {
        /* LOG.debug("Deleting directory '"+f+"'") */
        LOG.deleting_directory(f.toString()).debug();
      }
      String priorLastKey = null;
      do {
        PartialListing listing = store.list(key, S3_MAX_LISTING_LENGTH, priorLastKey, true);
        for (FileMetadata file : listing.getFiles()) {
          store.delete(file.getKey());
        }
        priorLastKey = listing.getPriorLastKey();
      } while (priorLastKey != null);

      try {
        store.delete(key + FOLDER_SUFFIX);
      } catch (FileNotFoundException e) {
        //this is fine, we don't require a marker
      }
    } else {
      if(LogGlobal.isDebugEnabled()) {
        /* LOG.debug("Deleting file '"+f+"'") */
        LOG.deleting_file(f.toString()).debug();
      }
      createParent(f);
      store.delete(key);
    }
    return true;
  }

  @Override
  public FileStatus getFileStatus(Path f) throws IOException {
    Path absolutePath = makeAbsolute(f);
    String key = pathToKey(absolutePath);
    
    if (key.length() == 0) { // root always exists
      return newDirectory(absolutePath);
    }
    
    if(LogGlobal.isDebugEnabled()) {
      /* LOG.debug("getFileStatus retrieving metadata for key '"+key+"'") */
      LOG.getfilestatus_retrieving_metadata_for_ke(key).debug();
    }
    FileMetadata meta = store.retrieveMetadata(key);
    if (meta != null) {
      if(LogGlobal.isDebugEnabled()) {
        /* LOG.debug("getFileStatus returning 'file' for key '"+key+"'") */
        LOG.getfilestatus_returning_file_for_key(key).debug();
      }
      return newFile(meta, absolutePath);
    }
    if (store.retrieveMetadata(key + FOLDER_SUFFIX) != null) {
      if(LogGlobal.isDebugEnabled()) {
        /* LOG.debug("getFileStatus returning 'directory' for key '"+key+"' as '"+key+FOLDER_SUFFIX+"' exists") */
        LOG.getfilestatus_returning_directory_for_ke(key, key, FOLDER_SUFFIX).debug();
      }
      return newDirectory(absolutePath);
    }
    
    if(LogGlobal.isDebugEnabled()) {
      /* LOG.debug("getFileStatus listing key '"+key+"'") */
      LOG.getfilestatus_listing_key(key).debug();
    }
    PartialListing listing = store.list(key, 1);
    if (listing.getFiles().length > 0 ||
        listing.getCommonPrefixes().length > 0) {
      if(LogGlobal.isDebugEnabled()) {
        /* LOG.debug("getFileStatus returning 'directory' for key '"+key+"' as it has contents") */
        LOG.getfilestatus_returning_directory_for_ke(key).debug();
      }
      return newDirectory(absolutePath);
    }
    
    if(LogGlobal.isDebugEnabled()) {
      /* LOG.debug("getFileStatus could not find key '"+key+"'") */
      LOG.getfilestatus_could_not_find_key(key).debug();
    }
    throw new FileNotFoundException("No such file or directory '" + absolutePath + "'");
  }

  @Override
  public URI getUri() {
    return uri;
  }

  /**
   * <p>
   * If <code>f</code> is a file, this method will make a single call to S3.
   * If <code>f</code> is a directory, this method will make a maximum of
   * (<i>n</i> / 1000) + 2 calls to S3, where <i>n</i> is the total number of
   * files and directories contained directly in <code>f</code>.
   * </p>
   */
  @Override
  public FileStatus[] listStatus(Path f) throws IOException {

    Path absolutePath = makeAbsolute(f);
    String key = pathToKey(absolutePath);
    
    if (key.length() > 0) {
      FileMetadata meta = store.retrieveMetadata(key);
      if (meta != null) {
        return new FileStatus[] { newFile(meta, absolutePath) };
      }
    }
    
    URI pathUri = absolutePath.toUri();
    Set<FileStatus> status = new TreeSet<FileStatus>();
    String priorLastKey = null;
    do {
      PartialListing listing = store.list(key, S3_MAX_LISTING_LENGTH, priorLastKey, false);
      for (FileMetadata fileMetadata : listing.getFiles()) {
        Path subpath = keyToPath(fileMetadata.getKey());
        String relativePath = pathUri.relativize(subpath.toUri()).getPath();

        if (fileMetadata.getKey().equals(key + "/")) {
          // this is just the directory we have been asked to list
        }
        else if (relativePath.endsWith(FOLDER_SUFFIX)) {
          status.add(newDirectory(new Path(
              absolutePath,
              relativePath.substring(0, relativePath.indexOf(FOLDER_SUFFIX)))));
        }
        else {
          status.add(newFile(fileMetadata, subpath));
        }
      }
      for (String commonPrefix : listing.getCommonPrefixes()) {
        Path subpath = keyToPath(commonPrefix);
        String relativePath = pathUri.relativize(subpath.toUri()).getPath();
        status.add(newDirectory(new Path(absolutePath, relativePath)));
      }
      priorLastKey = listing.getPriorLastKey();
    } while (priorLastKey != null);
    
    if (status.isEmpty() &&
        key.length() > 0 &&
        store.retrieveMetadata(key + FOLDER_SUFFIX) == null) {
      throw new FileNotFoundException("File " + f + " does not exist.");
    }
    
    return status.toArray(new FileStatus[status.size()]);
  }
  
  private FileStatus newFile(FileMetadata meta, Path path) {
    return new FileStatus(meta.getLength(), false, 1, getDefaultBlockSize(),
        meta.getLastModified(), path.makeQualified(this.getUri(), this.getWorkingDirectory()));
  }
  
  private FileStatus newDirectory(Path path) {
    return new FileStatus(0, true, 1, 0, 0, path.makeQualified(this.getUri(), this.getWorkingDirectory()));
  }

  @Override
  public boolean mkdirs(Path f, FsPermission permission) throws IOException {
    Path absolutePath = makeAbsolute(f);
    List<Path> paths = new ArrayList<Path>();
    do {
      paths.add(0, absolutePath);
      absolutePath = absolutePath.getParent();
    } while (absolutePath != null);
    
    boolean result = true;
    for (Path path : paths) {
      result &= mkdir(path);
    }
    return result;
  }
  
  private boolean mkdir(Path f) throws IOException {
    try {
      FileStatus fileStatus = getFileStatus(f);
      if (fileStatus.isFile()) {
        throw new IOException(String.format(
            "Can't make directory for path '%s' since it is a file.", f));

      }
    } catch (FileNotFoundException e) {
      if(LogGlobal.isDebugEnabled()) {
        /* LOG.debug("Making dir '"+f+"' in S3") */
        LOG.making_dir(f.toString()).debug();
      }
      String key = pathToKey(f) + FOLDER_SUFFIX;
      store.storeEmptyFile(key);    
    }
    return true;
  }

  @Override
  public FSDataInputStream open(Path f, int bufferSize) throws IOException {
    FileStatus fs = getFileStatus(f); // will throw if the file doesn't exist
    if (fs.isDirectory()) {
      throw new IOException("'" + f + "' is a directory");
    }
    /* LOG.info("Opening '"+f+"' for reading") */
    LOG.opening_for_reading(f.toString()).info();
    Path absolutePath = makeAbsolute(f);
    String key = pathToKey(absolutePath);
    return new FSDataInputStream(new BufferedFSInputStream(
        new NativeS3FsInputStream(store, statistics, store.retrieve(key), key), bufferSize));
  }
  
  // rename() and delete() use this method to ensure that the parent directory
  // of the source does not vanish.
  private void createParent(Path path) throws IOException {
    Path parent = path.getParent();
    if (parent != null) {
      String key = pathToKey(makeAbsolute(parent));
      if (key.length() > 0) {
          store.storeEmptyFile(key + FOLDER_SUFFIX);
      }
    }
  }
  
    
  @Override
  public boolean rename(Path src, Path dst) throws IOException {

    String srcKey = pathToKey(makeAbsolute(src));
    final String debugPreamble = "Renaming '" + src + "' to '" + dst + "' - ";

    if (srcKey.length() == 0) {
      // Cannot rename root of file system
      if (LogGlobal.isDebugEnabled()) {
        /* LOG.debug(debugPreamble+"returning false as cannot rename the root of a filesystem") */
        LOG.returning_false_cannot_rename_root_files(debugPreamble).debug();
      }
      return false;
    }

    //get status of source
    boolean srcIsFile;
    try {
      srcIsFile = getFileStatus(src).isFile();
    } catch (FileNotFoundException e) {
      //bail out fast if the source does not exist
      if (LogGlobal.isDebugEnabled()) {
        /* LOG.debug(debugPreamble+"returning false as src does not exist") */
        LOG.returning_false_src_does_not_exist(debugPreamble).debug();
      }
      return false;
    }
    // Figure out the final destination
    String dstKey = pathToKey(makeAbsolute(dst));

    try {
      boolean dstIsFile = getFileStatus(dst).isFile();
      if (dstIsFile) {
        //destination is a file.
        //you can't copy a file or a directory onto an existing file
        //except for the special case of dest==src, which is a no-op
        if(LogGlobal.isDebugEnabled()) {
          /* LOG.debug(debugPreamble+"returning without rename as dst is an already existing file") */
          LOG.returning_without_rename_dst_already_exi(debugPreamble).debug();
        }
        //exit, returning true iff the rename is onto self
        return srcKey.equals(dstKey);
      } else {
        //destination exists and is a directory
        if(LogGlobal.isDebugEnabled()) {
          /* LOG.debug(debugPreamble+"using dst as output directory") */
          LOG.using_dst_output_directory(debugPreamble).debug();
        }
        //destination goes under the dst path, with the name of the
        //source entry
        dstKey = pathToKey(makeAbsolute(new Path(dst, src.getName())));
      }
    } catch (FileNotFoundException e) {
      //destination does not exist => the source file or directory
      //is copied over with the name of the destination
      if(LogGlobal.isDebugEnabled()) {
        /* LOG.debug(debugPreamble+"using dst as output destination") */
        LOG.using_dst_output_destination(debugPreamble).debug();
      }
      try {
        if (getFileStatus(dst.getParent()).isFile()) {
          if(LogGlobal.isDebugEnabled()) {
            /* LOG.debug(debugPreamble+"returning false as dst parent exists and is a file") */
            LOG.returning_false_dst_parent_exists_and(debugPreamble).debug();
          }
          return false;
        }
      } catch (FileNotFoundException ex) {
        if(LogGlobal.isDebugEnabled()) {
          /* LOG.debug(debugPreamble+"returning false as dst parent does not exist") */
          LOG.returning_false_dst_parent_does_not(debugPreamble).debug();
        }
        return false;
      }
    }

    //rename to self behavior follows Posix rules and is different
    //for directories and files -the return code is driven by src type
    if (srcKey.equals(dstKey)) {
      //fully resolved destination key matches source: fail
      if (LogGlobal.isDebugEnabled()) {
        /* LOG.debug(debugPreamble+"renamingToSelf; returning true") */
        LOG.renamingtoself_returning_true(debugPreamble).debug();
      }
      return true;
    }
    if (srcIsFile) {
      //source is a file; COPY then DELETE
      if(LogGlobal.isDebugEnabled()) {
        /* LOG.debug(debugPreamble+"src is file, so doing copy then delete in S3") */
        LOG.src_file_doing_copy_then_delete(debugPreamble).debug();
      }
      store.copy(srcKey, dstKey);
      store.delete(srcKey);
    } else {
      //src is a directory
      if(LogGlobal.isDebugEnabled()) {
        /* LOG.debug(debugPreamble+"src is directory, so copying contents") */
        LOG.src_directory_copying_contents(debugPreamble).debug();
      }
      //Verify dest is not a child of the parent
      if (dstKey.startsWith(srcKey + "/")) {
        if (LogGlobal.isDebugEnabled()) {
          /* LOG.debug(debugPreamble+"cannot rename a directory to a subdirectory of self") */
          LOG.cannot_rename_directory_subdirectory_sel(debugPreamble).debug();
        }
        return false;
      }
      //create the subdir under the destination
      store.storeEmptyFile(dstKey + FOLDER_SUFFIX);

      List<String> keysToDelete = new ArrayList<String>();
      String priorLastKey = null;
      do {
        PartialListing listing = store.list(srcKey, S3_MAX_LISTING_LENGTH, priorLastKey, true);
        for (FileMetadata file : listing.getFiles()) {
          keysToDelete.add(file.getKey());
          store.copy(file.getKey(), dstKey + file.getKey().substring(srcKey.length()));
        }
        priorLastKey = listing.getPriorLastKey();
      } while (priorLastKey != null);

      if(LogGlobal.isDebugEnabled()) {
        /* LOG.debug(debugPreamble+"all files in src copied, now removing src files") */
        LOG.all_files_src_copied_now_removing(debugPreamble).debug();
      }
      for (String key: keysToDelete) {
        store.delete(key);
      }

      try {
        store.delete(srcKey + FOLDER_SUFFIX);
      } catch (FileNotFoundException e) {
        //this is fine, we don't require a marker
      }
      if(LogGlobal.isDebugEnabled()) {
        /* LOG.debug(debugPreamble+"done") */
        LOG.done(debugPreamble).debug();
      }
    }

    return true;
  }
  
  @Override
  public long getDefaultBlockSize() {
    return getConf().getLong("fs.s3n.block.size", 64 * 1024 * 1024);
  }

  /**
   * Set the working directory to the given directory.
   */
  @Override
  public void setWorkingDirectory(Path newDir) {
    workingDir = newDir;
  }
  
  @Override
  public Path getWorkingDirectory() {
    return workingDir;
  }

  @Override
  public String getCanonicalServiceName() {
    // Does not support Token
    return null;
  }
}
